import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class DemoChatApp {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Сохранённые настройки
    private static Properties config = new Properties();
    private static File configFile = new File("config.properties");

    // Выбранный файл для отправки
    private static String selectedFilePath = null;

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
        System.out.println("  /sendselected <nick>      - send previously selected file");
        System.out.println("  /downloaddir <path>       - set download directory (default: ./downloads/)");
        System.out.println("  /setport <COM>            - change COM port (restart required)");
        System.out.println("  /setparams <baud> <bits> <NONE/EVEN/ODD> - set port params");
        System.out.println("  /ls                       - list files in current directory");
        System.out.println("  /select <number>          - select file by number from /ls");
        System.out.println("  /history show             - show history");
        System.out.println("  /history clear            - clear history");
        System.out.println("  /history save <filePath>  - save history to file");
        System.out.println("  /disconnect               - send UPLINK (broadcast)");
        System.out.println("  /exit                     - quit app");
        System.out.println("Any text without command is sent as broadcast.");
    }

    // Загрузка сохранённых настроек
    private static void loadConfig() {
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                config.load(fis);
            } catch (IOException e) {
                System.out.println("Could not load config: " + e.getMessage());
            }
        }
    }

    // Сохранение настроек
    private static void saveConfig() {
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            config.store(fos, "Chat Application Settings");
        } catch (IOException e) {
            System.out.println("Could not save config: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        // Загружаем сохранённые настройки
        loadConfig();

        // Показываем доступные порты
        System.out.println("Available ports:");
        String[] ports = SerialPhysicalLayer.listPorts();
        if (ports.length == 0) System.out.println(" (none)");
        for (String p : ports) System.out.println(" - " + p);

        // Используем сохранённый порт если есть
        String defaultPort = config.getProperty("port", "");
        String portName;
        if (!defaultPort.isEmpty()) {
            System.out.print("Enter COM port (default: " + defaultPort + "): ");
            String input = sc.nextLine().trim();
            portName = input.isEmpty() ? defaultPort : input;
        } else {
            System.out.print("Enter COM port (e.g., COM1): ");
            portName = sc.nextLine().trim();
        }

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

        // Используем сохранённые параметры порта
        String savedBaud = config.getProperty("baud", "9600");
        String savedDataBits = config.getProperty("dataBits", "8");
        String savedParity = config.getProperty("parity", "NONE");

        SerialConfig serialConfig = new SerialConfig(
                portName,
                Integer.parseInt(savedBaud),
                Integer.parseInt(savedDataBits),
                1,
                savedParity.equals("EVEN") ? SerialPhysicalLayer.PARITY_EVEN :
                        (savedParity.equals("ODD") ? SerialPhysicalLayer.PARITY_ODD : SerialPhysicalLayer.PARITY_NONE),
                SerialPhysicalLayer.FLOW_NONE,
                true,
                true
        );

        SerialPhysicalLayer phy = new SerialPhysicalLayer(serialConfig);
        phy.open();

        final Map<Integer, String>[] usersRef = new Map[]{new HashMap<>()};

        // ========== СОЗДАЁМ ПРИКЛАДНОЙ УРОВЕНЬ ПОЗЖЕ, ПОСЛЕ DLL ==========
        // Сначала создадим переменную, потом заполним
        final ChatApplicationLayer[] appLayerRef = new ChatApplicationLayer[1];

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
                        // ========== ВАЖНО: обновляем список пользователей в прикладном уровне ==========
                        if (appLayerRef[0] != null) {
                            appLayerRef[0].updateUsers(usersRef[0]);
                        }
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
        appLayerRef[0] = appLayer;  // сохраняем ссылку для колбэка
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

            if (line.equalsIgnoreCase("/exit")) {
                // Сохраняем настройки перед выходом
                saveConfig();
                break;
            }

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

            // ========== КОМАНДА: смена COM-порта ==========
            if (line.startsWith("/setport ")) {
                String newPort = line.substring(9).trim();
                config.setProperty("port", newPort);
                saveConfig();
                System.out.println("COM port changed to " + newPort + ". Please restart the application.");
                continue;
            }

            // ========== КОМАНДА: настройка параметров порта ==========
            if (line.startsWith("/setparams ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    try {
                        int baud = Integer.parseInt(parts[1]);
                        int dataBits = Integer.parseInt(parts[2]);
                        String parityStr = parts[3].toUpperCase();

                        config.setProperty("baud", String.valueOf(baud));
                        config.setProperty("dataBits", String.valueOf(dataBits));
                        config.setProperty("parity", parityStr);
                        saveConfig();

                        System.out.println("Settings saved. Restart to apply: " + baud + " " + dataBits + " " + parityStr);
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid parameters. Usage: /setparams <baud> <bits> <NONE/EVEN/ODD>");
                    }
                } else {
                    System.out.println("Usage: /setparams <baud> <bits> <NONE/EVEN/ODD>");
                }
                continue;
            }

            // ========== КОМАНДА: список файлов в директории ==========
            if (line.equalsIgnoreCase("/ls")) {
                File folder = new File(".");
                File[] files = folder.listFiles();
                if (files != null) {
                    System.out.println("Files in current directory:");
                    int i = 1;
                    for (File f : files) {
                        if (f.isFile()) {
                            System.out.println("  " + i + ". " + f.getName() + " (" + f.length() + " bytes)");
                            i++;
                        }
                    }
                    if (i == 1) {
                        System.out.println("  (no files found)");
                    }
                }
                continue;
            }

            // ========== КОМАНДА: выбор файла по номеру ==========
            if (line.startsWith("/select ")) {
                String numStr = line.substring(8).trim();
                try {
                    int num = Integer.parseInt(numStr) - 1;
                    File folder = new File(".");
                    File[] files = folder.listFiles();
                    if (files != null) {
                        int fileIndex = 0;
                        for (File f : files) {
                            if (f.isFile()) {
                                if (fileIndex == num) {
                                    selectedFilePath = f.getAbsolutePath();
                                    System.out.println("Selected file: " + f.getName() + " (" + f.length() + " bytes)");
                                    break;
                                }
                                fileIndex++;
                            }
                        }
                        if (selectedFilePath == null) {
                            System.out.println("Invalid file number. Use /ls to see available files.");
                        }
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Usage: /select <number>");
                }
                continue;
            }

            // ========== КОМАНДА: отправить выбранный файл ==========
            if (line.startsWith("/sendselected ")) {
                String[] parts = line.split("\\s+", 2);
                if (parts.length < 2) {
                    System.out.println("Usage: /sendselected <nick>");
                    continue;
                }
                String toNick = parts[1];

                if (selectedFilePath == null) {
                    System.out.println("No file selected. Use /ls and /select <number> first.");
                    continue;
                }

                try {
                    appLayer.sendFile(toNick, selectedFilePath);
                } catch (Exception e) {
                    System.out.println("Error sending file: " + e.getMessage());
                }
                continue;
            }

            // ========== КОМАНДА: установка папки загрузок ==========
            if (line.startsWith("/downloaddir ")) {
                String dir = line.substring(13).trim();
                appLayer.setDownloadDirectory(dir);
                System.out.println("Download directory set to: " + dir);
                continue;
            }

            // ========== КОМАНДА: отправка файла ==========
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
                appLayer.sendBroadcast(text);
                continue;
            }

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