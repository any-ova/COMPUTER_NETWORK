import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AppLogger — пишет все события в лог-файл (config.ini).
 *
 * Файл состоит из двух частей:
 *   [config] — последние настройки порта (перезаписываются при каждом запуске)
 *   [log]    — хронологический журнал всех событий (накапливается)
 */
public class AppLogger {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String filePath;
    private PrintWriter logWriter;

    public AppLogger(String filePath) {
        this.filePath = filePath;
        try {
            logWriter = new PrintWriter(new FileWriter(filePath, true), true);
        } catch (IOException e) {
            System.err.println("[AppLogger] Cannot open log file: " + e.getMessage());
        }
    }

    // Сохраняет/обновляет секцию [config]. Вызывать при каждом запуске.
    public void saveConfig(String port, int addr, String nick,
                           int baud, int dataBits, String parity,
                           String downloadDir) {
        try {
            // Читаем файл, вырезаем старый [config]
            StringBuilder rest = new StringBuilder();
            File f = new File(filePath);
            if (f.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    boolean inConfig = false;
                    while ((line = br.readLine()) != null) {
                        if (line.trim().equals("[config]")) { inConfig = true; continue; }
                        if (inConfig && line.trim().startsWith("[")) inConfig = false;
                        if (!inConfig) rest.append(line).append(System.lineSeparator());
                    }
                }
            }

            // Пишем: новый [config] + старое содержимое
            try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
                pw.println("[config]");
                pw.println("port="        + port);
                pw.println("addr="        + addr);
                pw.println("nick="        + nick);
                pw.println("baud="        + baud);
                pw.println("dataBits="    + dataBits);
                pw.println("parity="      + parity);
                pw.println("stopBits=1");
                pw.println("downloadDir=" + downloadDir);
                pw.println("lastSaved="   + now());
                pw.println();
                pw.print(rest);
            }

            // Переоткрываем append-writer после перезаписи файла
            if (logWriter != null) logWriter.close();
            logWriter = new PrintWriter(new FileWriter(filePath, true), true);

        } catch (IOException e) {
            System.err.println("[AppLogger] saveConfig error: " + e.getMessage());
        }
    }

    // Универсальный метод — пишет любую строку с меткой времени
    public void log(String message) {
        if (logWriter == null) return;
        logWriter.println(now() + " " + message);
    }

    // --- Специализированные методы ---

    public void logSys(String message) {
        log("[SYS] " + message);
    }

    public void logMsgOut(String fromNick, String toNick, String text) {
        log("[MSG->] " + fromNick + " -> " + toNick + ": " + text);
    }

    public void logMsgIn(String fromNick, String toNick, String text) {
        log("[MSG<-] " + fromNick + " -> " + toNick + ": " + text);
    }

    public void logFileSendStart(String fromNick, String toNick, String fileName, long bytes) {
        log("[FILE->] " + fromNick + " -> " + toNick
                + " START: " + fileName + " (" + bytes + " bytes)");
    }

    public void logFileProgress(String direction, String fileName, int percent) {
        log("[FILE" + direction + "] " + fileName + " " + percent + "%");
    }

    public void logFileSent(String toNick, String fileName) {
        log("[FILE->] SENT " + fileName + " -> " + toNick);
    }

    public void logFileReceived(String fromNick, String fileName, String savePath) {
        log("[FILE<-] RECEIVED " + fileName + " from " + fromNick + " saved: " + savePath);
    }

    public void logFileError(String message) {
        log("[FILE ERR] " + message);
    }

    public void logPortClosed(String portName) {
        log("[SYS] COM port closed: " + portName);
    }

    public void logConnectionLost(String reason) {
        log("[ERR] Connection lost: " + reason);
    }

    public void close() {
        if (logWriter != null) {
            log("[SYS] Logger closed. Session end.");
            logWriter.flush();
            logWriter.close();
            logWriter = null;
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}