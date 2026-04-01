import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ChatApplicationLayer {

    public final BlockingQueue<AppMessage> incomingQueue = new LinkedBlockingQueue<>();
    public final BlockingQueue<AppMessage> outgoingQueue = new LinkedBlockingQueue<>();
    public final BlockingQueue<SystemPacket> systemQueue = new LinkedBlockingQueue<>();

    private final DataLinkLayer dll;
    private final String myNick;

    private volatile boolean running;
    private Thread txThread;

    // текущая “адресная книга”
    private volatile Map<Integer, String> users;

    public ChatApplicationLayer(DataLinkLayer dll, String myNick) {
        this.dll = dll;
        this.myNick = myNick;
    }

    public void start() {
        running = true;
        txThread = new Thread(this::txLoop, "APP-TX");
        txThread.setDaemon(true);
        txThread.start();
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "Application layer started"));
    }

    public void stop() {
        running = false;
        if (txThread != null) {
            try { txThread.join(400); } catch (InterruptedException ignored) {}
        }
        systemQueue.offer(new SystemPacket(SystemEventId.DISCONNECT, "Application layer stopped"));
    }

    /** Отправить сообщение всем. */
    public void sendBroadcast(String text) {
        outgoingQueue.offer(new AppMessage("*", myNick, text));
    }

    /** Отправить личное сообщение по нику (будем искать адрес по users). */
    public void sendToNick(String toNick, String text) {
        outgoingQueue.offer(new AppMessage(toNick, myNick, text));
    }

    /** Должен вызываться из callback onUsers(...) канального уровня */
    public void updateUsers(Map<Integer, String> users) {
        this.users = users;
    }

    /** Должен вызываться из callback onChat(...) канального уровня */
    public void onFrameText(String fromName, int fromAddr, String text) {
        // text — это полезные данные I-кадра. Мы считаем, что это закодированный AppMessage.
        try {
            AppMessage m = AppCodec.decode(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            incomingQueue.offer(m);
        } catch (Exception e) {
            // если пришёл "старый" текст не в нашем формате — просто покажем как обычный
            incomingQueue.offer(new AppMessage("*", fromName, text));
        }
    }

    /** Должен вызываться из callback onSystem(...) канального уровня */
    public void onSystemText(String s) {
        // маппинг на события — примитивный (можно улучшить)
        if (s.contains("ACK received")) systemQueue.offer(new SystemPacket(SystemEventId.ACK, s));
        else if (s.contains("RX stopped")) systemQueue.offer(new SystemPacket(SystemEventId.DISCONNECT, s));
        else systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, s));
    }

    private void txLoop() {
        while (running) {
            try {
                AppMessage m = outgoingQueue.take();

                // Если toNick="*" => broadcast
                if (m.toNick.equals("*")) {
                    // кладём в DATA сериализованный пакет
                    byte[] bytes = AppCodec.encode(m);
                    dll.sendChatBroadcast(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    continue;
                }

                // иначе ищем адрес по нику
                Integer addr = findAddrByNick(m.toNick);
                if (addr == null) {
                    systemQueue.offer(new SystemPacket(SystemEventId.NO_ACK, "Unknown user: " + m.toNick));
                    continue;
                }

                byte[] bytes = AppCodec.encode(m);
                dll.sendChatTo(addr, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));

            } catch (InterruptedException ie) {
                break;
            } catch (IOException ioe) {
                systemQueue.offer(new SystemPacket(SystemEventId.DISCONNECT, "Send failed: " + ioe.getMessage()));
            }
        }
    }

    private Integer findAddrByNick(String nick) {
        Map<Integer, String> u = this.users;
        if (u == null) return null;
        for (Map.Entry<Integer, String> e : u.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(nick)) return e.getKey();
        }
        return null;
    }
}