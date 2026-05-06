import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class DemoChatApp {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  /link                     - send LINK (broadcast) and update users list");
        System.out.println("  /users                    - show known users");
        System.out.println("  /all <text>               - broadcast message");
        System.out.println("  /to <nick> <text>         - private message to nickname");
        System.out.println("  /sendfile <nick> <path>   - send file to nickname");
        System.out.println("  /downloaddir <path>       - set download directory (default: ./downloads/)");
        System.out.println("  /history show             - show history");
        System.out.println("  /history clear            - clear history");
        System.out.println("  /history save <filePath>  - save history to file");
        System.out.println("  /disconnect               - send UPLINK (broadcast)");
        System.out.println("  /exit                     - quit app");
        System.out.println("Any text without command is sent as broadcast.");
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        System.out.println("Available ports:");
        String[] ports = SerialPhysicalLayer.listPorts();
        if (ports.length == 0) System.out.println(" (none)");
        for (String p : ports) System.out.println(" - " + p);

        System.out.print("Enter COM port (e.g., COM1): ");
        String portName = sc.nextLine().trim();

        System.out.print("Enter your address (1..126): ");
        int myAddr = Integer.parseInt(sc.nextLine().trim());
        if (myAddr < 1 || myAddr > 0x7E) {
            System.out.println("Bad address. Must be 1..126");
            return;
        }

        System.out.print("Enter your nickname: ");
        String nick = sc.nextLine().trim();
        if (nick.isEmpty()) {
            System.out.println("Nickname cannot be empty.");
            return;
        }

        // --- history (application-level) ---
        List<String> history = Collections.synchronizedList(new ArrayList<>());

        SerialPhysicalLayer phy = new SerialPhysicalLayer(SerialConfig.defaults(portName));
        phy.open();

        final Map<Integer, String>[] usersRef = new Map[]{new HashMap<>()};

        DataLinkLayer dll = new DataLinkLayer(
                phy.getInputStream(),
                phy.getOutputStream(),
                myAddr,
                nick,
                new DataLinkLayer.Callbacks() {
                    @Override
                    public void onChat(String fromName, int fromAddr, String text) {
                        String line = now() + " " + fromName + " (" + fromAddr + ")> " + text;
                        history.add(line);
                        System.out.print("\n" + line + "\n> ");
                    }

                    @Override
                    public void onSystem(String text) {
                        String line = now() + " SYSTEM> " + text;
                        history.add(line);
                        System.out.print("\n" + line + "\n> ");
                    }

                    @Override
                    public void onUsers(Map<Integer, String> users) {
                        usersRef[0] = new HashMap<>(users);
                        String line = now() + " SYSTEM> USERS UPDATED: " + usersRef[0];
                        history.add(line);
                        System.out.print("\n" + line + "\n> ");
                    }

                    @Override
                    public void onDisconnected() {
                        String line = now() + " SYSTEM> DISCONNECTED";
                        history.add(line);
                        System.out.print("\n" + line + "\n> ");
                    }
                }
        );

        dll.start();

        // ========== СОЗДАЁМ ПРИКЛАДНОЙ УРОВЕНЬ ==========
        ChatApplicationLayer appLayer = new ChatApplicationLayer(dll, nick);
        appLayer.start();
        
        // Поток для обработки входящих сообщений из очереди
        Thread receiverThread = new Thread(() -> {
            while (true) {
                try {
                    AppMessage msg = appLayer.incomingQueue.take();
                    String line = now() + " " + msg.fromNick + "> " + msg.text;
                    history.add(line);
                    System.out.print("\n" + line + "\n> ");
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        receiverThread.setDaemon(true);
        receiverThread.start();
        
        // Поток для обработки системных сообщений
        Thread systemThread = new Thread(() -> {
            while (true) {
                try {
                    SystemPacket pkt = appLayer.systemQueue.take();
                    String line = now() + " [SYS] " + pkt.info;
                    history.add(line);
                    System.out.print("\n" + line + "\n> ");
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        systemThread.setDaemon(true);
        systemThread.start();
        // ================================================

        System.out.println();
        System.out.println("Connected as " + nick + " (addr=" + myAddr + ") on " + portName);
        System.out.println("------------------------------------------------------");
        printHelp();
        System.out.println("------------------------------------------------------");

        while (true) {
            System.out.print("> ");
            String line = sc.nextLine();

            if (line.equalsIgnoreCase("/exit")) break;

            if (line.equalsIgnoreCase("/help")) {
                printHelp();
                continue;
            }

            if (line.equalsIgnoreCase("/link")) {
                dll.sendLink();
                continue;
            }

            if (line.equalsIgnoreCase("/users")) {
                System.out.println("Known users: " + usersRef[0]);
                continue;
            }

            if (line.equalsIgnoreCase("/disconnect")) {
                dll.sendUplink();
                continue;
            }

            // ========== НОВАЯ КОМАНДА: установка папки загрузок ==========
            if (line.startsWith("/downloaddir ")) {
                String dir = line.substring(13).trim();
                appLayer.setDownloadDirectory(dir);
                System.out.println("Download directory set to: " + dir);
                continue;
            }

            // ========== НОВАЯ КОМАНДА: отправка файла ==========
            if (line.startsWith("/sendfile ")) {
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 3) {
                    System.out.println("Usage: /sendfile <nick> <filepath>");
                    continue;
                }
                String toNick = parts[1];
                String filePath = parts[2];
                
                try {
                    appLayer.sendFile(toNick, filePath);
                } catch (Exception e) {
                    System.out.println("Error sending file: " + e.getMessage());
                }
                continue;
            }

            if (line.startsWith("/history ")) {
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 2) {
                    System.out.println("Usage: /history show|clear|save <filePath>");
                    continue;
                }
                String action = parts[1];

                if (action.equalsIgnoreCase("show")) {
                    System.out.println("----- HISTORY (" + history.size() + ") -----");
                    synchronized (history) {
                        for (String h : history) System.out.println(h);
                    }
                    System.out.println("----- END HISTORY -----");
                } else if (action.equalsIgnoreCase("clear")) {
                    history.clear();
                    System.out.println("History cleared.");
                } else if (action.equalsIgnoreCase("save")) {
                    if (parts.length < 3) {
                        System.out.println("Usage: /history save <filePath>");
                        continue;
                    }
                    String path = parts[2];
                    try (FileWriter fw = new FileWriter(path, false)) {
                        synchronized (history) {
                            for (String h : history) fw.write(h + System.lineSeparator());
                        }
                    }
                    System.out.println("History saved to: " + path);
                } else {
                    System.out.println("Unknown history action: " + action);
                }
                continue;
            }

            if (line.startsWith("/all ")) {
                String text = line.substring(5);
                String self = now() + " " + nick + " (" + myAddr + ")> " + text;
                history.add(self);
                System.out.println(self);
                // Используем прикладной уровень вместо прямого вызова DLL
                appLayer.sendBroadcast(text);
                continue;
            }

            // ========== ИЗМЕНЕНО: /to теперь по нику, а не по адресу ==========
            if (line.startsWith("/to ")) {
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 3) {
                    System.out.println("Usage: /to <nick> <text>");
                    continue;
                }
                String toNick = parts[1];
                String text = parts[2];

                String self = now() + " " + nick + " (" + myAddr + ") [to " + toNick + "]> " + text;
                history.add(self);
                System.out.println(self);

                appLayer.sendToNick(toNick, text);
                continue;
            }

            // по умолчанию — broadcast
            if (!line.isBlank()) {
                String self = now() + " " + nick + " (" + myAddr + ")> " + line;
                history.add(self);
                System.out.println(self);
                appLayer.sendBroadcast(line);
            }
        }

        appLayer.stop();
        dll.stop();
        phy.close();
        System.out.println("Bye.");
    }
}