import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class FrameIO {
    // Флаг кадра 0xFF (по заданию)
    public static final int FLAG = 0xFF;
    public static final int BROADCAST = 0x7F;
    public static final int ESC = 0x7D;

    public static final int TYPE_I      = 0x01;
    public static final int TYPE_LINK   = 0x02;
    public static final int TYPE_UPLINK = 0x03;
    public static final int TYPE_ACK    = 0x04;
    public static final int TYPE_RET    = 0x05;

    public static void writeFrame(OutputStream out, int dst, int src, int type, byte[] rawData) throws IOException {
        out.write(FLAG);
        out.write(dst & 0xFF);
        out.write(src & 0xFF);
        out.write(type & 0xFF);

        if (hasData(type)) {
            if (rawData == null) rawData = new byte[0];
            if (rawData.length > 255) throw new IOException("Data too long: " + rawData.length);

            byte[] coded = Code74.encode(rawData);
            byte[] stuffed = stuff(coded);
            out.write(rawData.length & 0xFF);
            out.write(stuffed);
        }

        out.write(FLAG);
        out.flush();
    }

    public static Frame readFrame(InputStream in) throws IOException {
        int b;
        do {
            b = in.read();
            if (b < 0) throw new IOException("EOF");
        } while ((b & 0xFF) != FLAG);

        int dst = readByte(in);
        int src = readByte(in);
        int type = readByte(in);

        if (!hasData(type)) {
            int stop = readByte(in);
            if (stop != FLAG) throw new IOException("Bad stop byte");
            return new Frame(dst, src, type, new byte[0]);
        }

        int rawLen = readByte(in);
        byte[] stuffed = readUntilFlag(in);
        byte[] coded = unstuff(stuffed);

        // Защита от ошибок декодирования
        byte[] raw;
        try {
            raw = Code74.decode(coded, rawLen);
        } catch (IOException e) {
            System.err.println("[FrameIO] Decode error: " + e.getMessage());
            raw = Code74.decodeSafe(coded, rawLen);
        }

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

    private static byte[] readUntilFlag(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("EOF");
            int x = b & 0xFF;
            if (x == FLAG) break;
            bos.write(x);
        }
        return bos.toByteArray();
    }

    private static byte[] stuff(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 2);
        for (byte bb : data) {
            int x = bb & 0xFF;
            if (x == FLAG || x == ESC) {
                bos.write(ESC);
                bos.write(x ^ 0x20);
            } else {
                bos.write(x);
            }
        }
        return bos.toByteArray();
    }

    private static byte[] unstuff(byte[] stuffed) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(stuffed.length);
        for (int i = 0; i < stuffed.length; i++) {
            int x = stuffed[i] & 0xFF;
            if (x == ESC) {
                if (i + 1 >= stuffed.length) throw new IOException("Bad ESC at end");
                int y = stuffed[++i] & 0xFF;
                bos.write(y ^ 0x20);
            } else {
                bos.write(x);
            }
        }
        return bos.toByteArray();
    }
}