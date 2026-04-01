import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataLinkLayer {

    public interface Callbacks {
        void onChat(String fromName, int fromAddr, String text);
        void onSystem(String text);
        void onUsers(Map<Integer, String> users);
        void onDisconnected();
    }

    private final InputStream in;
    private final OutputStream out;

    private final int myAddr;          // 1..126
    private final String myNick;

    private final Callbacks cb;

    private final ConcurrentHashMap<Integer, String> users = new ConcurrentHashMap<>();

    private volatile boolean running;
    private Thread rxThread;

    // “��оследний отправленный кадр” (для RET по методичке)
    private volatile LastSent lastSent;

    private static class LastSent {
        final int dst, src, type;
        final byte[] rawData;
        LastSent(int dst, int src, int type, byte[] rawData) {
            this.dst = dst; this.src = src; this.type = type;
            this.rawData = (rawData == null) ? new byte[0] : rawData;
        }
    }

    public DataLinkLayer(InputStream in, OutputStream out, int myAddr, String myNick, Callbacks cb) {
        this.in = in;
        this.out = out;
        this.myAddr = myAddr & 0xFF;
        this.myNick = myNick;
        this.cb = cb;

        users.put(this.myAddr, myNick);
    }

    public void start() {
        running = true;
        rxThread = new Thread(this::rxLoop, "DLL-RX");
        rxThread.setDaemon(true);
        rxThread.start();
        sys("DLL started. addr=" + myAddr + " nick=" + myNick);
    }

    public void stop() {
        running = false;
        try { if (rxThread != null) rxThread.join(400); } catch (InterruptedException ignored) {}
    }

    // --- API (что вызывает приложение) ---

    public void sendLink() throws IOException {
        // LINK data: список пользователей "addr=nick\n..."
        // Для 2 ПК достаточно послать себя, но можно и весь users
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : users.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
        }
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);

        FrameIO.writeFrame(out, Frame.BROADCAST, myAddr, FrameIO.TYPE_LINK, raw);
        lastSent = new LastSent(Frame.BROADCAST, myAddr, FrameIO.TYPE_LINK, raw);
        sys("LINK sent (broadcast)");
    }

    public void sendUplink() throws IOException {
        FrameIO.writeFrame(out, Frame.BROADCAST, myAddr, FrameIO.TYPE_UPLINK, null);
        lastSent = new LastSent(Frame.BROADCAST, myAddr, FrameIO.TYPE_UPLINK, null);
        sys("UPLINK sent (broadcast)");
    }

    public void sendChatBroadcast(String text) throws IOException {
        sendChat(Frame.BROADCAST, text);
    }

    public void sendChatTo(int dstAddr, String text) throws IOException {
        sendChat(dstAddr, text);
    }

    private void sendChat(int dstAddr, String text) throws IOException {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        FrameIO.writeFrame(out, dstAddr, myAddr, FrameIO.TYPE_I, raw);

        // “последний кадр” для RET
        lastSent = new LastSent(dstAddr, myAddr, FrameIO.TYPE_I, raw);

        // По методичке ACK/RET без данных — подтверждаем “тестовое сообщение”.
        // Для 2 ПК: если dst не broadcast, ожидается ACK/RET со стороны получателя.
        sys("I sent to " + dstAddr + (dstAddr == Frame.BROADCAST ? " (broadcast)" : ""));
    }

    // --- RX loop ---

    private void rxLoop() {
        while (running) {
            try {
                Frame f = FrameIO.readFrame(in);

                boolean forMe = (f.dst == myAddr);
                boolean broadcast = (f.dst == Frame.BROADCAST);

                // В point-to-point 2 ПК ретрансляции нет.

                if (forMe || broadcast) {
                    handleFrame(f);
                }

            } catch (Exception ex) {
                sys("RX stopped: " + ex.getMessage());
                if (cb != null) cb.onDisconnected();
                break;
            }
        }
    }

    private void handleFrame(Frame f) throws IOException {
        switch (f.type) {
            case FrameIO.TYPE_I -> {
                String from = users.getOrDefault(f.src, "addr:" + f.src);
                String text = new String(f.data, StandardCharsets.UTF_8);

                if (cb != null) cb.onChat(from, f.src, text);

                // ACK только если это unicast (по методичке: получатель не должен быть broadcast)
                if (f.dst != Frame.BROADCAST) {
                    FrameIO.writeFrame(out, f.src, myAddr, FrameIO.TYPE_ACK, null);
                }
            }
            case FrameIO.TYPE_LINK -> {
                // data: "addr=nick\n..."
                parseUsers(f.data);
                if (cb != null) cb.onUsers(users);
                sys("LINK received from " + f.src);

                // можно ответить своим LINK, чтобы синхронизировать таблицы
                // (для 2 ПК удобно):
                // sendLink();
            }
            case FrameIO.TYPE_UPLINK -> {
                sys("UPLINK received. Disconnect.");
                if (cb != null) cb.onDisconnected();
            }
            case FrameIO.TYPE_ACK -> {
                // по методичке без данных: считаем что "последний I доставлен"
                sys("ACK received from " + f.src);
            }
            case FrameIO.TYPE_RET -> {
                sys("RET received from " + f.src + ", resending last frame...");
                resendLast();
            }
            default -> sys("Unknown frame type: " + f.type);
        }
    }

    private void resendLast() throws IOException {
        LastSent ls = lastSent;
        if (ls == null) {
            sys("No last frame to resend.");
            return;
        }
        FrameIO.writeFrame(out, ls.dst, ls.src, ls.type, ls.rawData);
    }

    private void parseUsers(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8);
        for (String line : s.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("=")) continue;
            String[] parts = line.split("=", 2);
            try {
                int addr = Integer.parseInt(parts[0].trim());
                String nick = parts[1].trim();
                if (addr >= 1 && addr <= 0x7E && !nick.isEmpty()) {
                    users.put(addr, nick);
                }
            } catch (Exception ignored) {}
        }
        // всегда держим себя
        users.put(myAddr, myNick);
    }

    private void sys(String s) {
        if (cb != null) cb.onSystem(s);
    }
}