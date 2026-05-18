import java.nio.charset.StandardCharsets;

public class AppCodec {
    private static final char SEP = 0x1F;

    public static byte[] encode(AppMessage m) {
        String s = safe(m.toNick) + SEP + safe(m.fromNick) + SEP + safe(m.text);
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static AppMessage decode(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);

        // Очищаем от управляющих символов (кроме SEP)
        s = cleanString(s);

        String[] parts = s.split(String.valueOf(SEP), 3);
        String to = parts.length > 0 ? unescape(parts[0]) : "";
        String from = parts.length > 1 ? unescape(parts[1]) : "";
        String text = parts.length > 2 ? unescape(parts[2]) : "";

        return new AppMessage(to, from, text);
    }

    private static String cleanString(String s) {
        // Удаляем все управляющие символы, кроме SEP (0x1F)
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == SEP || (c >= 0x20 && c <= 0x7E) || Character.isLetterOrDigit(c) ||
                    c == ' ' || c == ',' || c == '.' || c == '!' || c == '?' || c == '\n') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        // Убираем странные символы вроде квадратиков
        return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "").trim();
    }

    private static String safe(String x) { return x == null ? "" : x; }
}