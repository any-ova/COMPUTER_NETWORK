import java.nio.charset.StandardCharsets;

public class AppCodec {
    private static final char SEP = 0x1F;

    public static byte[] encode(AppMessage m) {
        String s = safe(m.toNick) + SEP + safe(m.fromNick) + SEP + safe(m.text);
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static AppMessage decode(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        String[] parts = s.split(String.valueOf(SEP), 3);
        String to = parts.length > 0 ? parts[0] : "";
        String from = parts.length > 1 ? parts[1] : "";
        String text = parts.length > 2 ? parts[2] : "";
        return new AppMessage(to, from, text);
    }

    private static String safe(String x) { return x == null ? "" : x; }
}