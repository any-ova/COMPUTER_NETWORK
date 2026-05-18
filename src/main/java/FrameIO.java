import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class FrameIO {
    // ИСПРАВЛЕНО: правильные значения по стандарту HDLC
    public static final int FLAG = 0x7E;        // 126 - start/stop флаг
    public static final int BROADCAST = 0x7F;   // 127 - широковещательный адрес
    public static final int ESC = 0x7D;         // 125 - escape символ

    // Типы кадров (как в методичке)
    public static final int TYPE_I      = 0x01;
    public static final int TYPE_LINK   = 0x02;
    public static final int TYPE_UPLINK = 0x03;
    public static final int TYPE_ACK    = 0x04;
    public static final int TYPE_RET    = 0x05;

    /** Записать кадр. rawData используется только для I и LINK. */
    public static void writeFrame(OutputStream out, int dst, int src, int type, byte[] rawData) throws IOException {
        out.write(FLAG);
        out.write(dst & 0xFF);
        out.write(src & 0xFF);
        out.write(type & 0xFF);

        if (hasData(type)) {
            if (rawData == null) rawData = new byte[0];
            if (rawData.length > 255) throw new IOException("Data too long: " + rawData.length);

            // 1) [7,4] кодирование полезных данных
            byte[] coded = Code74.encode(rawData);

            // 2) stuffing coded-данных, чтобы FLAG не встретился в середине
            byte[] stuffed = stuff(coded);

            // len в методичке = длина ПОЛЯ ДАННЫХ (оригинальная, до кодирования)
            out.write(rawData.length & 0xFF);

            // поле "Данные" (в методичке) мы передаём как stuffed(coded(rawData))
            out.write(stuffed);
        }

        out.write(FLAG);
        out.flush();
    }

    /** Прочитать кадр. Возвращаем rawData уже ДЕКОДИРОВАННЫЕ (т.е. исходные данные). */
    public static Frame readFrame(InputStream in) throws IOException {
        // 1) дождаться стартового FLAG
        int b;
        do {
            b = in.read();
            if (b < 0) throw new IOException("EOF");
        } while ((b & 0xFF) != FLAG);

        // 2) header
        int dst = readByte(in);
        int src = readByte(in);
        int type = readByte(in);

        if (!hasData(type)) {
            // для ACK/RET/UPLINK поля len и data отсутствуют — сразу ждём stop FLAG
            int stop = readByte(in);
            if (stop != FLAG) throw new IOException("Bad stop byte: expected " + FLAG + ", got " + stop);
            return new Frame(dst, src, type, new byte[0]);
        }

        // 3) len (raw length)
        int rawLen = readByte(in);

        // 4) читаем stuffed(coded(data)) до стопового FLAG
        byte[] stuffed = readUntilFlag(in);

        // 5) unstuff -> coded
        byte[] coded = unstuff(stuffed);

        // 6) decode [7,4] обратно в raw bytes (длина известна = rawLen)
        byte[] raw = Code74.decode(coded, rawLen);

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

    /** Читает байты до следующего FLAG (FLAG не включается). */
    private static byte[] readUntilFlag(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("EOF");
            int x = b & 0xFF;
            if (x == FLAG) break;      // stop
            bos.write(x);
        }
        return bos.toByteArray();
    }

    // --- stuffing только внутри data ---
    // Правило: после ESC идёт оригинальный байт XOR 0x20 (как в HDLC)

    private static byte[] stuff(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 2);
        for (byte bb : data) {
            int x = bb & 0xFF;
            if (x == FLAG || x == ESC) {
                bos.write(ESC);
                bos.write(x ^ 0x20);  // XOR 0x20 как в стандарте HDLC
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
                // Восстанавливаем оригинальный байт: XOR 0x20 обратно
                bos.write(y ^ 0x20);
            } else {
                bos.write(x);
            }
        }
        return bos.toByteArray();
    }
}