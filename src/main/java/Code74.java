import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class Code74 {
    // g(x)=x^3 + x + 1 -> бинарно 1011 (степень 3)
    private static final int G = 0b1011;
    private static final int R = 3; // n-k

    // Таблица синдром -> позиция ошибки (по твоей таблице)
    // синдром как 3-бит: (s2 s1 s0) соответствует (s3 s2 s1) в методичке,
    // где s3 — коэффициент при x^2, s2 — при x^1, s1 — при x^0.
    // Из таблицы:
    // x0 -> 001
    // x1 -> 010
    // x2 -> 100
    // x3 -> 011
    // x4 -> 110
    // x5 -> 111
    // x6 -> 101
    private static final int[] SYNDROME_TO_ERRPOS = new int[8];
    static {
        // 0 => no error
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
        // 1) x^(n-k) * m(x) => сдвиг на 3: m << 3
        int shifted = m << R; // 7 бит максимум

        // 2) остаток от деления shifted на g(x)
        int rem = mod2divRemainder(shifted, G); // степень <=2 (3 бита)

        // 3) конкатенация: v = shifted XOR rem (так как rem занимает младшие 3 бита)
        return (shifted ^ rem) & 0x7F;
    }

    /**
     * Декодирует 7-бит слово, исправляет одиночную ошибку по синдрому.
     * Возвращает 4-битное сообщение (0..15).
     */
    public static int decodeCodeword(int cw) throws IOException {
        cw &= 0x7F;

        int syndrome = mod2divRemainder(cw, G) & 0x7; // 3 бита
        if (syndrome != 0) {
            // одиночная ошибка -> по таблице находим позицию и исправляем
            int pos = SYNDROME_TO_ERRPOS[syndrome];
            if (pos == 0 && syndrome != 0b001) {
                // на всякий: если синдром неизвестен (не бывает для одиночной ошибки)
                // можно считать "неисправимо"
                // но для (7,4) и одиночной ошибки синдром всегда из таблицы
            }
            // pos = степень x^pos, значит это бит b_pos (младший = pos=0)
            cw ^= (1 << pos);

            // проверим, что стало нормально
            int syndrome2 = mod2divRemainder(cw, G) & 0x7;
            if (syndrome2 != 0) throw new IOException("Uncorrectable error (syndrome after correction != 0)");
        }

        // информационные биты — это старшие 4 бита: cw[6..3]
        return (cw >> R) & 0xF;
    }

    // --- Работа с массивами байт: кодируем каждый НИББЛ (полубайт) -> 7 бит, пакуем в поток бит ---

    public static byte[] encode(byte[] input) {
        if (input == null || input.length == 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);

        int bitBuf = 0;
        int bitCount = 0;

        for (byte bb : input) {
            int b = bb & 0xFF;
            int hi = (b >> 4) & 0xF;
            int lo = b & 0xF;

            int cw1 = encodeNibble(hi); // 7 bits
            int cw2 = encodeNibble(lo); // 7 bits

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
                if (idx >= encoded.length) throw new IOException("Not enough encoded data");
                bitBuf = (bitBuf << 8) | (encoded[idx++] & 0xFF);
                bitCount += 8;
            }
            int shift = bitCount - 7;
            int cw = (bitBuf >> shift) & 0x7F;
            bitCount -= 7;
            bitBuf &= (1 << bitCount) - 1;

            int m = decodeCodeword(cw); // 4 bits
            if (hi) {
                cur = (m & 0xF) << 4;
                hi = false;
            } else {
                cur |= (m & 0xF);
                out[outPos++] = (byte) cur;
                hi = true;
            }
        }
        return out;
    }

    /**
     * Остаток от деления полинома (в виде битового числа) на g(x) по mod2.
     * Возвращает remainder < 2^deg(g) (т.е. 3 бита для g степени 3).
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