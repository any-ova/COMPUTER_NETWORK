import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class ChatApplicationLayer {

    public final BlockingQueue<AppMessage> incomingQueue = new LinkedBlockingQueue<>();
    public final BlockingQueue<AppMessage> outgoingQueue = new LinkedBlockingQueue<>();
    public final BlockingQueue<SystemPacket> systemQueue = new LinkedBlockingQueue<>();

    private final DataLinkLayer dll;
    private final String myNick;

    private volatile boolean running;
    private Thread txThread;
    private volatile Map<Integer, String> users;

    // ========== ДЛЯ ПЕРЕДАЧИ ФАЙЛОВ ==========
    private String downloadDirectory = "./downloads/";
    private final Map<String, FileReceiveState> activeDownloads = new ConcurrentHashMap<>();

    private static class FileReceiveState {
        String fileName;
        FileOutputStream fileStream;
        int expectedBlocks;
        int receivedBlocks;

        FileReceiveState(String fileName, int expectedBlocks) {
            this.fileName = fileName;
            this.expectedBlocks = expectedBlocks;
            this.receivedBlocks = 0;
        }
    }
    // ==========================================

    public ChatApplicationLayer(DataLinkLayer dll, String myNick) {
        this.dll = dll;
        this.myNick = myNick;
        this.users = new ConcurrentHashMap<>();
        new File(downloadDirectory).mkdirs();
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

    public void setDownloadDirectory(String dir) {
        this.downloadDirectory = dir;
        new File(dir).mkdirs();
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "Download directory set to: " + dir));
    }

    public String getDownloadDirectory() {
        return downloadDirectory;
    }

    // ========== ОТПРАВКА ФАЙЛА ==========
    public void sendFile(String toNick, String filePath) throws IOException, InterruptedException {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            systemQueue.offer(new SystemPacket(SystemEventId.NO_ACK, "File not found: " + filePath));
            return;
        }

        String fileName = file.getName();
        long fileSize = file.length();
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT,
                "Sending file: " + fileName + " (" + fileSize + " bytes) to " + toNick));

        byte[] fileData = new byte[(int) fileSize];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(fileData);
        }

        int blockSize = 200;
        int totalBlocks = (int) Math.ceil((double) fileData.length / blockSize);

        String fileInfo = "FILE_START:" + fileName + ":" + totalBlocks + ":" + fileSize;
        sendToNick(toNick, fileInfo);
        Thread.sleep(100);

        for (int i = 0; i < totalBlocks; i++) {
            int from = i * blockSize;
            int to = Math.min(from + blockSize, fileData.length);
            byte[] block = new byte[to - from];
            System.arraycopy(fileData, from, block, 0, block.length);

            String encodedBlock = Base64.getEncoder().encodeToString(block);
            String blockMsg = "FILE_DATA:" + i + ":" + encodedBlock;
            sendToNick(toNick, blockMsg);

            int progress = (int) ((i + 1) * 100 / totalBlocks);
            systemQueue.offer(new SystemPacket(SystemEventId.CONNECT,
                    "Progress: " + progress + "% (" + (i + 1) + "/" + totalBlocks + " blocks)"));

            Thread.sleep(50);
        }

        sendToNick(toNick, "FILE_END:" + fileName);
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "File sent: " + fileName));
    }

    // ========== ПРИЁМ ФАЙЛА ==========
    private void handleFileTransfer(String fromNick, String text) throws IOException {
        if (text.startsWith("FILE_START:")) {
            String[] parts = text.split(":");
            if (parts.length >= 4) {
                String fileName = parts[1];
                int totalBlocks = Integer.parseInt(parts[2]);
                long fileSize = Long.parseLong(parts[3]);

                String fullPath = downloadDirectory + File.separator + fileName;
                FileReceiveState state = new FileReceiveState(fileName, totalBlocks);
                state.fileStream = new FileOutputStream(fullPath);
                activeDownloads.put(fromNick, state);

                systemQueue.offer(new SystemPacket(SystemEventId.CONNECT,
                        "Receiving file: " + fileName + " (" + fileSize + " bytes)"));
            }
        }
        else if (text.startsWith("FILE_DATA:")) {
            String[] parts = text.split(":", 3);
            if (parts.length >= 3) {
                int blockNum = Integer.parseInt(parts[1]);
                byte[] blockData = Base64.getDecoder().decode(parts[2]);

                FileReceiveState state = activeDownloads.get(fromNick);
                if (state != null && state.fileStream != null) {
                    state.fileStream.write(blockData);
                    state.receivedBlocks++;

                    int progress = (state.receivedBlocks * 100) / state.expectedBlocks;
                    if (progress % 10 == 0 || state.receivedBlocks == state.expectedBlocks) {
                        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT,
                                "Receiving progress: " + progress + "%"));
                    }
                }
            }
        }
        else if (text.startsWith("FILE_END:")) {
            String[] parts = text.split(":");
            String fileName = parts.length > 1 ? parts[1] : "unknown";

            FileReceiveState state = activeDownloads.remove(fromNick);
            if (state != null && state.fileStream != null) {
                state.fileStream.close();
                systemQueue.offer(new SystemPacket(SystemEventId.CONNECT,
                        "File saved: " + downloadDirectory + File.separator + fileName));
            }
        }
    }

    public void sendBroadcast(String text) {
        outgoingQueue.offer(new AppMessage("*", myNick, text));
    }

    public void sendToNick(String toNick, String text) {
        outgoingQueue.offer(new AppMessage(toNick, myNick, text));
    }

    public void updateUsers(Map<Integer, String> users) {
        this.users = users;
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "Users table updated: " + users));
    }

    public void onFrameText(String fromName, int fromAddr, String text) {
        if (text.startsWith("FILE_START:") || text.startsWith("FILE_DATA:") || text.startsWith("FILE_END:")) {
            try {
                handleFileTransfer(fromName, text);
            } catch (IOException e) {
                systemQueue.offer(new SystemPacket(SystemEventId.DISCONNECT,
                        "File receive error: " + e.getMessage()));
            }
            return;
        }

        // text содержит: toNick + SEP + fromNick + SEP + messageText
        // Парсим вручную, не полагаясь на AppCodec
        String[] parts = text.split(String.valueOf((char)0x1F), 3);

        String toNick = (parts.length > 0) ? parts[0] : "*";
        String senderNick = (parts.length > 1) ? parts[1] : fromName;
        String messageText = (parts.length > 2) ? parts[2] : "";

        AppMessage m = new AppMessage(toNick, senderNick, messageText);
        incomingQueue.offer(m);
    }

    public void onSystemText(String s) {
        if (s.contains("ACK received")) systemQueue.offer(new SystemPacket(SystemEventId.ACK, s));
        else if (s.contains("RX stopped")) systemQueue.offer(new SystemPacket(SystemEventId.DISCONNECT, s));
        else systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, s));
    }

    private void txLoop() {
        while (running) {
            try {
                AppMessage m = outgoingQueue.take();
                System.err.println("[APP-TX] AppMessage: to='" + m.toNick + "' from='" + m.fromNick + "' text='" + m.text + "'");

                byte[] bytes = AppCodec.encode(m);
                System.err.println("[APP-TX] Encoded bytes len=" + bytes.length + " content=" +
                        new String(bytes, StandardCharsets.UTF_8).replace('\n', '|').replace((char)0x1F, '§'));

                String data = new String(bytes, StandardCharsets.UTF_8);

                if (m.toNick.equals("*")) {
                    System.err.println("[APP-TX] Broadcasting via DLL");
                    dll.sendChatBroadcast(data);
                } else {
                    Integer addr = findAddrByNick(m.toNick);
                    if (addr == null) {
                        System.err.println("[APP-TX] ERROR: Unknown user " + m.toNick);
                        systemQueue.offer(new SystemPacket(SystemEventId.NO_ACK, "Unknown user: " + m.toNick));
                        continue;
                    }
                    System.err.println("[APP-TX] Sending to " + m.toNick + " (addr=" + addr + ")");
                    dll.sendChatTo(addr, data);
                }
            } catch (InterruptedException ie) {
                break;
            } catch (IOException ioe) {
                systemQueue.offer(new SystemPacket(SystemEventId.DISCONNECT, "Send failed: " + ioe.getMessage()));
            }
        }
    }

    private Integer findAddrByNick(String nick) {
        Map<Integer, String> u = this.users;
        if (u == null) {
            systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "DEBUG: users map is null"));
            return null;
        }
        for (Map.Entry<Integer, String> e : u.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(nick)) {
                return e.getKey();
            }
        }
        return null;
    }
}