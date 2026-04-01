public class SystemPacket {
    public final int eventId;
    public final String info; // доп.текст для удобства логов (не обязателен методичкой)

    public SystemPacket(int eventId, String info) {
        this.eventId = eventId;
        this.info = info;
    }

    @Override
    public String toString() {
        return "SystemPacket{eventId=" + eventId + ", info='" + info + "'}";
    }
}