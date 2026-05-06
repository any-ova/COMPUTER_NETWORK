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

    // ========== ДОБАВЛЕНО ДЛЯ ПЕРЕДАЧИ ФАЙЛОВ ==========
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
    // ===================================================

    public ChatApplicationLayer(DataLinkLayer dll, String myNick) {
        this.dll = dll;
        this.myNick = myNick;
        // Создаём папку для загрузок, если её нет
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

    // ========== ДОБАВЛЕНО: Управление папкой загрузок ==========
    public void setDownloadDirectory(String dir) {
        this.downloadDirectory = dir;
        new File(dir).mkdirs();
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "Download directory set to: " + dir));
    }
    
    public String getDownloadDirectory() {
        return downloadDirectory;
    }
    // ========================================================

    // ========== ДОБАВЛЕНО: Отправка файла ==========
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
        
        // Читаем весь файл
        byte[] fileData = new byte[(int) fileSize];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(fileData);
        }
        
        // Размер блока: 200 байт (чтобы поместилось в кадр после кодирования)
        int blockSize = 200;
        int totalBlocks = (int) Math.ceil((double) fileData.length / blockSize);
        
        // Отправляем информацию о файле
        String fileInfo = "FILE_START:" + fileName + ":" + totalBlocks + ":" + fileSize;
        sendToNick(toNick, fileInfo);
        Thread.sleep(100);
        
        // Отправляем блоки
        for (int i = 0; i < totalBlocks; i++) {
            int from = i * blockSize;
            int to = Math.min(from + blockSize, fileData.length);
            byte[] block = new byte[to - from];
            System.arraycopy(fileData, from, block, 0, block.length);
            
            // Кодируем блок в Base64 (чтобы безопасно передавать бинарные данные)
            String encodedBlock = Base64.getEncoder().encodeToString(block);
            String blockMsg = "FILE_DATA:" + i + ":" + encodedBlock;
            sendToNick(toNick, blockMsg);
            
            // Обновляем прогресс
            int progress = (int) ((i + 1) * 100 / totalBlocks);
            systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, 
                "Progress: " + progress + "% (" + (i + 1) + "/" + totalBlocks + " blocks)"));
            
            Thread.sleep(50); // Небольшая задержка, чтобы не забить канал
        }
        
        // Отправляем завершение
        sendToNick(toNick, "FILE_END:" + fileName);
        systemQueue.offer(new SystemPacket(SystemEventId.CONNECT, "File sent: " + fileName));
    }
    // ================================================

    // ========== ДОБАВЛЕНО: Приём файла (вызывается из onFrameText) ==========
    private void handleFileTransfer(String fromNick, String text) throws IOException {
        if (text.startsWith("FILE_START:")) {
            // Формат: FILE_START:имя_файла:количество_блоков:размер
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
            // Формат: FILE_DATA:номер_блока:данные_base64
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
            // Формат: FILE_END:имя_файла
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
    // ====================================================

    public void sendBroadcast(String text) {
        outgoingQueue.offer(new AppMessage("*", myNick, text));
    }

    public void sendToNick(String toNick, String text) {
        outgoingQueue.offer(new AppMessage(toNick, myNick, text));
    }

    public void updateUsers(Map<Integer, String> users) {
        this.users = users;
    }

    public void onFrameText(String fromName, int fromAddr, String text) {
        // Проверяем, не является ли сообщение частью файловой передачи
        if (text.startsWith("FILE_START:") || text.startsWith("FILE_DATA:") || text.startsWith("FILE_END:")) {
            try {
                handleFileTransfer(fromName, text);
            } catch (IOException e) {
                systemQueue.offer(new SystemPacket(SystemEventId.DISCONNECT, 
                    "File receive error: " + e.getMessage()));
            }
            return;
        }
        
        // Обычное текстовое сообщение
        try {
            AppMessage m = AppCodec.decode(text.getBytes(StandardCharsets.UTF_8));
            incomingQueue.offer(m);
        } catch (Exception e) {
            incomingQueue.offer(new AppMessage("*", fromName, text));
        }
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
                byte[] bytes = AppCodec.encode(m);
                String data = new String(bytes, StandardCharsets.UTF_8);

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
        Map<Integer, String> u = this.users;
        if (u == null) return null;
        for (Map.Entry<Integer, String> e : u.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(nick)) return e.getKey();
        }
        return null;
    }
}