import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
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
    private final Map<Integer, String>[] usersRef;

    private volatile boolean running;
    private Thread txThread;

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

    @SuppressWarnings("unchecked")
    public ChatApplicationLayer(DataLinkLayer dll, String myNick) {
        this.dll = dll;
        this.myNick = myNick;
        this.usersRef = (Map<Integer, String>[]) new Map[1];
        this.usersRef[0] = new HashMap<>();
        new File(downloadDirectory).mkdirs();
    }

    public void start() {
        running = true;
        txThread = new Thread(this::txLoop, "APP-TX");
        txThread.setDaemon(true);
        txThread.start();
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "Start"));
    }

    public void stop() {
        running = false;
        if (txThread != null) {
            try { txThread.join(400); } catch (InterruptedException ignored) {}
        }
        systemQueue.offer(new SystemPacket(SystemEventId.DISCONNECT, "Application layer stopped"));
    }

    public void updateUsers(Map<Integer, String> users) {
        this.usersRef[0] = new HashMap<>(users);
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "Users updated: " + this.usersRef[0]));
    }

    public void setDownloadDirectory(String dir) {
        this.downloadDirectory = dir;
        new File(dir).mkdirs();
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "Download directory set to: " + dir));
    }

    public String getDownloadDirectory() {
        return downloadDirectory;
    }

    public void sendFile(String toNick, String filePath) throws IOException, InterruptedException {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            systemQueue.offer(new SystemPacket(SystemEventId.NO_ACK, "File not found: " + filePath));
            return;
        }

        Integer addr = findAddrByNick(toNick);
        if (addr == null) {
            systemQueue.offer(new SystemPacket(SystemEventId.NO_ACK, "Unknown user: " + toNick));
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

        int blockSize = 120;
        int totalBlocks = (int) Math.ceil((double) fileData.length / blockSize);

        dll.sendChatTo(addr, "FILE_START:" + fileName + ":" + totalBlocks + ":" + fileSize);
        Thread.sleep(100);

        for (int i = 0; i < totalBlocks; i++) {
            int from = i * blockSize;
            int to = Math.min(from + blockSize, fileData.length);
            byte[] block = new byte[to - from];
            System.arraycopy(fileData, from, block, 0, block.length);

            String encodedBlock = Base64.getEncoder().encodeToString(block);
            dll.sendChatTo(addr, "FILE_DATA:" + i + ":" + encodedBlock);

            int progress = (int) ((i + 1) * 100 / totalBlocks);
            systemQueue.offer(new SystemPacket(SystemEventId.CONNECT,
                    "Progress: " + progress + "% (" + (i + 1) + "/" + totalBlocks + " blocks)"));
            Thread.sleep(50);
        }

        dll.sendChatTo(addr, "FILE_END:" + fileName);
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "File sent: " + fileName));
    }

    private void handleFileTransfer(String fromNick, String text) throws IOException {
        if (text.startsWith("FILE_START:")) {
            String[] parts = text.split(":");
            if (parts.length >= 4) {
                String fileName = parts[1];
                int totalBlocks = Integer.parseInt(parts[2]);
                long fileSize = Long.parseLong(parts[3]);

                String fullPath = downloadDirectory + File.separator + fileName;
                new File(downloadDirectory).mkdirs();
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
            FileReceiveState state = activeDownloads.remove(fromNick);
            if (state != null && state.fileStream != null) {
                state.fileStream.close();
                systemQueue.offer(new SystemPacket(SystemEventId.CONNECT,
                        "File saved: " + downloadDirectory + File.separator + state.fileName));
            }
        }
    }

    public void sendBroadcast(String text) {
        outgoingQueue.offer(new AppMessage("*", myNick, text));
    }

    public void sendToNick(String toNick, String text) {
        outgoingQueue.offer(new AppMessage(toNick, myNick, text));
    }

    public void onFrameText(String fromName, int fromAddr, String text) {
        // Файловые сообщения
        if (text.startsWith("FILE_START:") || text.startsWith("FILE_DATA:") || text.startsWith("FILE_END:")) {
            try {
                handleFileTransfer(fromName, text);
            } catch (IOException e) {
                systemQueue.offer(new SystemPacket(SystemEventId.DISCONNECT, "File receive error: " + e.getMessage()));
            }
            return;
        }

        // Обычные текстовые сообщения (без AppCodec)
        incomingQueue.offer(new AppMessage("*", fromName, text));
    }

    public void onSystemText(String s) {
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, s));
    }

    private void txLoop() {
        while (running) {
            try {
                AppMessage m = outgoingQueue.take();
                // Отправляем просто текст (без AppCodec)
                String data = m.text;

                if (m.toNick.equals("*")) {
                    dll.sendChatBroadcast(data);
                } else {
                    Integer addr = findAddrByNick(m.toNick);
                    if (addr == null) {
                        systemQueue.offer(new SystemPacket(SystemEventId.NO_ACK, "Unknown user: " + m.toNick));
                        continue;
                    }
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
        Map<Integer, String> u = usersRef[0];
        if (u == null) return null;
        for (Map.Entry<Integer, String> e : u.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(nick)) {
                return e.getKey();
            }
        }
        return null;
    }
}