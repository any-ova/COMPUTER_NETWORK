public class AppMessage {
    public final String toNick;    // получатель
    public final String fromNick;  // отправитель
    public final String text;      // текст

    public AppMessage(String toNick, String fromNick, String text) {
        this.toNick = toNick;
        this.fromNick = fromNick;
        this.text = text;
    }

    @Override
    public String toString() {
        return "AppMessage{to='" + toNick + "', from='" + fromNick + "', text='" + text + "'}";
    }
}