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

    public void sendLink() throws IOException {
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
        lastSent = new LastSent(dstAddr, myAddr, FrameIO.TYPE_I, raw);
        sys("I sent to " + dstAddr + (dstAddr == Frame.BROADCAST ? " (broadcast)" : ""));
    }

    private void rxLoop() {
        while (running) {
            try {
                Frame f = FrameIO.readFrame(in);

                // === ИСПРАВЛЕНИЕ: пропускаем null-кадры ===
                if (f == null) continue;

                boolean forMe = (f.dst == myAddr);
                boolean broadcast = (f.dst == Frame.BROADCAST);

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
                String text = new String(f.data, StandardCharsets.UTF_8);

                // ========== ПАРСИМ APPMESSAGE ==========
                String[] parts = text.split(String.valueOf((char)0x1F), 3);
                String toNick = (parts.length > 0) ? parts[0] : "*";
                String fromNick = (parts.length > 1) ? parts[1] : users.getOrDefault(f.src, "addr:" + f.src);
                String messageText = (parts.length > 2) ? parts[2] : "";

                // ========== ТОЛЬКО ЕСЛИ ДЛЯ НАС ИЛИ BROADCAST ==========
                if (toNick.equals("*") || toNick.isEmpty()) {
                    // Это broadcast или сообщение для всех
                    if (cb != null) cb.onChat(fromNick, f.src, messageText);

                    if (f.dst != Frame.BROADCAST) {
                        FrameIO.writeFrame(out, f.src, myAddr, FrameIO.TYPE_ACK, null);
                    }
                } else if (toNick.equalsIgnoreCase(myNick)) {
                    // Это приватное сообщение для нас
                    if (cb != null) cb.onChat(fromNick, f.src, messageText);
                    FrameIO.writeFrame(out, f.src, myAddr, FrameIO.TYPE_ACK, null);
                }
                // Если сообщение не для нас — игнорируем
            }
            case FrameIO.TYPE_LINK -> {
                parseUsers(f.data);
                if (cb != null) cb.onUsers(users);
                sys("LINK received from " + f.src);
            }
            case FrameIO.TYPE_UPLINK -> {
                sys("UPLINK received. Disconnect.");
                if (cb != null) cb.onDisconnected();
            }
            case FrameIO.TYPE_ACK -> {
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
        users.put(myAddr, myNick);
    }

    private void sys(String s) {
        if (cb != null) cb.onSystem(s);
    }
}