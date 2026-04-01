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
        System.out.println("  /to <addr> <text>         - private message to address");
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
                // дублируем в своём окне (как в методичке)
                String self = now() + " " + nick + " (" + myAddr + ")> " + text;
                history.add(self);
                System.out.println(self);

                dll.sendChatBroadcast(text);
                continue;
            }

            if (line.startsWith("/to ")) {
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 3) {
                    System.out.println("Usage: /to <addr> <text>");
                    continue;
                }
                int dst = Integer.parseInt(parts[1]);
                String text = parts[2];

                // дублируем в своём окне (как в методичке)
                String self = now() + " " + nick + " (" + myAddr + ") [to " + dst + "]> " + text;
                history.add(self);
                System.out.println(self);

                dll.sendChatTo(dst, text);
                continue;
            }

            // по умолчанию — broadcast (и дублируем)
            if (!line.isBlank()) {
                String self = now() + " " + nick + " (" + myAddr + ")> " + line;
                history.add(self);
                System.out.println(self);

                dll.sendChatBroadcast(line);
            }
        }

        dll.stop();
        phy.close();
        System.out.println("Bye.");
    }
}