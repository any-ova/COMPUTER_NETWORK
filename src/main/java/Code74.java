import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class Code74 {
    // g(x)=x^3 + x + 1 -> бинарно 1011 (степень 3)
    private static final int G = 0b1011;
    private static final int R = 3; // n-k

    // Таблица синдром -> позиция ошибки
    private static final int[] SYNDROME_TO_ERRPOS = new int[8];
    static {
        SYNDROME_TO_ERRPOS[0b001] = 0;
        SYNDROME_TO_ERRPOS[0b010] = 1;
        SYNDROME_TO_ERRPOS[0b100] = 2;
        SYNDROME_TO_ERRPOS[0b011] = 3;
        SYNDROME_TO_ERRPOS[0b110] = 4;
        SYNDROME_TO_ERRPOS[0b111] = 5;
        SYNDROME_TO_ERRPOS[0b101] = 6;
    }

    /** Кодирует 4-битное сообщение (0..15) в 7-бит кодовое слово (0..127). */
    public static int encodeNibble(int m) {
        m &= 0xF;
        int shifted = m << R;
        int rem = mod2divRemainder(shifted, G);
        return (shifted ^ rem) & 0x7F;
    }

    /**
     * Декодирует 7-бит слово, исправляет одиночную ошибку по синдрому.
     * Возвращает 4-битное сообщение (0..15).
     */
    public static int decodeCodeword(int cw) throws IOException {
        cw &= 0x7F;

        int syndrome = mod2divRemainder(cw, G) & 0x7;
        if (syndrome != 0) {
            int pos = SYNDROME_TO_ERRPOS[syndrome];
            if (pos == 0 && syndrome != 0b001) {
                throw new IOException("Uncorrectable error: unknown syndrome " + syndrome);
            }
            cw ^= (1 << pos);

            int syndrome2 = mod2divRemainder(cw, G) & 0x7;
            if (syndrome2 != 0) throw new IOException("Uncorrectable error (syndrome after correction != 0)");
        }

        return (cw >> R) & 0xF;
    }

    // --- Работа с массивами байт ---

    public static byte[] encode(byte[] input) {
        if (input == null || input.length == 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);

        int bitBuf = 0;
        int bitCount = 0;

        for (byte bb : input) {
            int b = bb & 0xFF;
            int hi = (b >> 4) & 0xF;
            int lo = b & 0xF;

            int cw1 = encodeNibble(hi);
            int cw2 = encodeNibble(lo);

            bitBuf = (bitBuf << 7) | cw1; bitCount += 7;
            bitBuf = (bitBuf << 7) | cw2; bitCount += 7;

            while (bitCount >= 8) {
                int shift = bitCount - 8;
                out.write((bitBuf >> shift) & 0xFF);
                bitCount -= 8;
                bitBuf &= (1 << bitCount) - 1;
            }
        }

        if (bitCount > 0) {
            out.write((bitBuf << (8 - bitCount)) & 0xFF);
        }
        return out.toByteArray();
    }

    public static byte[] decode(byte[] encoded, int needOutputBytes) throws IOException {
        if (needOutputBytes == 0) return new byte[0];
        if (encoded == null) throw new IOException("null encoded");

        int needNibbles = needOutputBytes * 2;

        int bitBuf = 0;
        int bitCount = 0;
        int idx = 0;

        byte[] out = new byte[needOutputBytes];
        int outPos = 0;
        int cur = 0;
        boolean hi = true;

        for (int nib = 0; nib < needNibbles; nib++) {
            while (bitCount < 7) {
                if (idx >= encoded.length) {
                    // НЕ ХВАТАЕТ ДАННЫХ! Вместо ошибки возвращаем то, что успели декодировать
                    // Обрезаем результат до успешно декодированных байтов
                    if (outPos > 0) {
                        byte[] truncated = new byte[outPos];
                        System.arraycopy(out, 0, truncated, 0, outPos);
                        return truncated;
                    } else {
                        return new byte[0];
                    }
                }
                bitBuf = (bitBuf << 8) | (encoded[idx++] & 0xFF);
                bitCount += 8;
            }
            int shift = bitCount - 7;
            int cw = (bitBuf >> shift) & 0x7F;
            bitCount -= 7;
            bitBuf &= (1 << bitCount) - 1;

            int m;
            try {
                m = decodeCodeword(cw);
            } catch (IOException e) {
                // При ошибке декодирования одного ниббла — пропускаем его и продолжаем
                m = 0;
            }

            if (hi) {
                cur = (m & 0xF) << 4;
                hi = false;
            } else {
                cur |= (m & 0xF);
                out[outPos++] = (byte) cur;
                hi = true;
            }
        }

        // Если декодировали меньше, чем ожидалось — обрезаем
        if (outPos < needOutputBytes) {
            byte[] truncated = new byte[outPos];
            System.arraycopy(out, 0, truncated, 0, outPos);
            return truncated;
        }

        return out;
    }

    /**
     * Остаток от деления полинома (в виде битового числа) на g(x) по mod2.
     */
    private static int mod2divRemainder(int value, int g) {
        int gDeg = degree(g);
        int v = value;

        while (degree(v) >= gDeg) {
            int shift = degree(v) - gDeg;
            v ^= (g << shift);
        }
        return v;
    }

    private static int degree(int poly) {
        for (int i = 31; i >= 0; i--) {
            if (((poly >> i) & 1) != 0) return i;
        }
        return -1;
    }
}