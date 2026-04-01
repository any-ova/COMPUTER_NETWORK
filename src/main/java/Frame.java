public class Frame {
    public static final int FLAG = 0xFF;
    public static final int BROADCAST = 0x7F;

    public final int dst;
    public final int src;
    public final int type;
    public final byte[] data; // raw (уже декодированные)

    public Frame(int dst, int src, int type, byte[] data) {
        this.dst = dst & 0xFF;
        this.src = src & 0xFF;
        this.type = type & 0xFF;
        this.data = (data == null) ? new byte[0] : data;
    }
}