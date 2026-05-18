import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class FrameIO {
    public static final int FLAG = 0xFF;
    public static final int BROADCAST = 0x7F;
    public static final int ESC = 0xFE;

    public static final int TYPE_I      = 0x01;
    public static final int TYPE_LINK   = 0x02;
    public static final int TYPE_UPLINK = 0x03;
    public static final int TYPE_ACK    = 0x04;
    public static final int TYPE_RET    = 0x05;

    private static final int MAX_FRAME_SIZE = 512;

    public static void writeFrame(OutputStream out, int dst, int src, int type, byte[] rawData) throws IOException {
        System.err.println("[FRAME-OUT] ===== START OF FRAME =====");
        System.err.println("[FRAME-OUT] Header: dst=" + dst + " src=" + src + " type=" + type +
                " dataLen=" + (rawData == null ? 0 : rawData.length));

        // === СОБИРАЕМ ВЕСЬ КАДР В БУФЕР ===
        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();

        // START FLAG
        frameBuffer.write(FLAG);

        // HEADER
        frameBuffer.write(dst & 0xFF);
        frameBuffer.write(src & 0xFF);
        frameBuffer.write(type & 0xFF);

        if (hasData(type)) {
            if (rawData == null) rawData = new byte[0];
            if (rawData.length > 255) throw new IOException("Data too long: " + rawData.length);

            byte[] coded = Code74.encode(rawData);
            byte[] stuffed = stuff(coded);

            System.err.println("[FRAME-OUT] rawDataLen=" + rawData.length + " codedLen=" + coded.length + " stuffedLen=" + stuffed.length);

            // RAW LENGTH
            frameBuffer.write(rawData.length & 0xFF);

            // DATA
            frameBuffer.write(stuffed);
        }

        // STOP FLAG
        frameBuffer.write(FLAG);

        // === ВЫВОДИМ ВСЕ БАЙТЫ ПЕРЕД ОТПРАВКОЙ ===
        byte[] frame = frameBuffer.toByteArray();
        System.err.println("[FRAME-OUT] Total frame size: " + frame.length + " bytes");
        System.err.print("[FRAME-OUT] Frame bytes HEX: ");
        for (int i = 0; i < frame.length; i++) {
            System.err.print(String.format("%02X ", frame[i] & 0xFF));
            if ((i + 1) % 20 == 0) System.err.print("\n                        ");
        }
        System.err.println();

        // === ОТПРАВЛЯЕМ ПО ОДНОМУ БАЙТУ ===
        for (int i = 0; i < frame.length; i++) {
            byte b = frame[i];
            out.write(b & 0xFF);
            out.flush();
            System.err.println("[FRAME-OUT] Sent byte " + i + ": 0x" + String.format("%02X", b & 0xFF));
            try { Thread.sleep(4); } catch (InterruptedException ignored) {}
        }

        System.err.println("[FRAME-OUT] ===== END OF FRAME =====\n");

        try { Thread.sleep(60); } catch (InterruptedException ignored) {}
    }
    public static Frame readFrame(InputStream in) throws IOException {
        System.err.println("[FRAME-IN] Waiting for start FLAG...");

        int b;
        int flagWaitTimeout = 0;
        final int MAX_TIMEOUT = 20000;  // 20 секунд макс ожидания

        // Ищем ПЕРВЫЙ FLAG
        while (true) {
            b = in.read();
            if (b < 0) throw new IOException("EOF");

            if ((b & 0xFF) == FLAG) {
                System.err.println("[FRAME-IN] Found start FLAG");
                break;
            }

            flagWaitTimeout++;
            if (flagWaitTimeout > MAX_TIMEOUT) {
                throw new IOException("Start FLAG timeout");
            }
        }

        System.err.println("[FRAME-IN] ===== START OF FRAME =====");

        int dst = readByte(in);
        int src = readByte(in);
        int type = readByte(in);

        System.err.println("[FRAME-IN] Header: dst=" + dst + " src=" + src + " type=" + type);

        if (!hasData(type)) {
            int stop = readByte(in);
            if (stop != FLAG) {
                System.err.println("[FRAME-IN] ERROR: expected stop FLAG 0xFF, got 0x" + String.format("%02X", stop & 0xFF));
                throw new IOException("Bad stop byte");
            }
            System.err.println("[FRAME-IN] ===== END OF FRAME =====\n");
            return new Frame(dst, src, type, new byte[0]);
        }

        int rawLen = readByte(in);
        System.err.println("[FRAME-IN] rawLen=" + rawLen);

        byte[] stuffed = readUntilFlag(in);
        System.err.println("[FRAME-IN] stuffedLen=" + stuffed.length);

        byte[] coded = unstuff(stuffed);
        System.err.println("[FRAME-IN] codedLen=" + coded.length);

        byte[] raw = Code74.decode(coded, rawLen);
        System.err.println("[FRAME-IN] decodedLen=" + (raw == null ? 0 : raw.length));

        if (raw == null || raw.length == 0) {
            System.err.println("[FRAME-IN] ===== END OF FRAME =====\n");
            return null;
        }

        String dataPreview = new String(raw, StandardCharsets.UTF_8)
                .replace('\n', '|')
                .replace((char)0x1F, '§');
        System.err.println("[FRAME-IN] Data: " + dataPreview);
        System.err.println("[FRAME-IN] ===== END OF FRAME =====\n");

        return new Frame(dst, src, type, raw);
    }
    private static boolean hasData(int type) {
        return type == TYPE_I || type == TYPE_LINK;
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("EOF");
        return b & 0xFF;
    }
    private static byte[] readExactlyUntilFlag(InputStream in, int rawLen) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int maxEstimatedSize = (rawLen * 12) / 10 + 20;  // + запас

        System.err.println("[FRAME-IN] readExactlyUntilFlag: expecting ~" + maxEstimatedSize + " bytes before FLAG");

        int byteCount = 0;
        boolean foundFlag = false;

        // Читаем до FLAG или до максимума
        while (!foundFlag && byteCount < 500) {  // макс 500 байт, чтобы не зависнуть
            int b = in.read();
            if (b < 0) throw new IOException("EOF while reading frame data");
            int x = b & 0xFF;

            System.err.println("[FRAME-IN] Read byte " + byteCount + ": 0x" + String.format("%02X", x));

            if (x == FLAG) {
                System.err.println("[FRAME-IN] *** FOUND STOP FLAG at data position " + bos.size() + " ***");
                foundFlag = true;
                break;
            }

            bos.write(x);
            byteCount++;
        }

        if (!foundFlag) {
            System.err.println("[FRAME-IN] WARNING: Did not find FLAG after " + byteCount + " bytes!");
            throw new IOException("STOP FLAG not found after " + byteCount + " bytes");
        }

        byte[] result = bos.toByteArray();
        System.err.print("[FRAME-IN] Received stuffed bytes HEX (" + result.length + " bytes): ");
        for (int i = 0; i < result.length; i++) {
            System.err.print(String.format("%02X ", result[i] & 0xFF));
            if ((i + 1) % 20 == 0) System.err.print("\n                                          ");
        }
        System.err.println();

        return result;
    }
    private static byte[] readUntilFlag(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("EOF while reading frame data");
            int x = b & 0xFF;

            if (x == FLAG) {
                break;
            }

            bos.write(x);

            if (bos.size() > MAX_FRAME_SIZE) {
                throw new IOException("Frame data too large: " + bos.size());
            }
        }

        return bos.toByteArray();
    }
    private static byte[] stuff(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length + 10);
        int flagCount = 0;
        int escCount = 0;

        for (byte bb : data) {
            int x = bb & 0xFF;
            if (x == FLAG) {
                flagCount++;
                bos.write(ESC);
                bos.write(x);
                System.err.println("[STUFF] Escaped FLAG at pos " + (bos.size()-2));
            } else if (x == ESC) {
                escCount++;
                bos.write(ESC);
                bos.write(x);
                System.err.println("[STUFF] Escaped ESC at pos " + (bos.size()-2));
            } else {
                bos.write(x);
            }
        }
        System.err.println("[STUFF] Input: " + data.length + " bytes, Output: " + bos.size() + " bytes (FLAG:" + flagCount + " ESC:" + escCount + ")");
        return bos.toByteArray();
    }

    private static byte[] unstuff(byte[] stuffed) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(stuffed.length);
        int flagCount = 0;
        int escCount = 0;

        for (int i = 0; i < stuffed.length; i++) {
            int x = stuffed[i] & 0xFF;
            if (x == ESC) {
                if (i + 1 >= stuffed.length) {
                    System.err.println("[UNSTUFF] ERROR: ESC at end of data");
                    bos.write(x);
                    break;
                }
                int y = stuffed[++i] & 0xFF;
                bos.write(y);

                if (y == FLAG) {
                    flagCount++;
                    System.err.println("[UNSTUFF] Unescaped FLAG at pos " + (i-1));
                } else if (y == ESC) {
                    escCount++;
                    System.err.println("[UNSTUFF] Unescaped ESC at pos " + (i-1));
                }
            } else {
                if (x == FLAG) {
                    System.err.println("[UNSTUFF] ERROR: Raw FLAG found at pos " + i + " - this should never happen!");
                    throw new IOException("Raw FLAG in data - stuffing failed!");
                }
                bos.write(x);
            }
        }
        System.err.println("[UNSTUFF] Input: " + stuffed.length + " bytes, Output: " + bos.size() + " bytes (FLAG:" + flagCount + " ESC:" + escCount + ")");
        return bos.toByteArray();
    }
}