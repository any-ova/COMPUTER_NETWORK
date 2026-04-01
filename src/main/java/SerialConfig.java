public class SerialConfig {
    public final String portName;     // "COM3"
    public final int baudRate;        // 9600
    public final int dataBits;        // 8
    public final int stopBits;        // 1
    public final int parity;          // SerialPhysicalLayer.PARITY_*
    public final int flowControl;     // SerialPhysicalLayer.FLOW_*

    public final boolean dtr;
    public final boolean rts;

    public SerialConfig(String portName,
                        int baudRate,
                        int dataBits,
                        int stopBits,
                        int parity,
                        int flowControl,
                        boolean dtr,
                        boolean rts) {
        this.portName = portName;
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        this.flowControl = flowControl;
        this.dtr = dtr;
        this.rts = rts;
    }

    public static SerialConfig defaults(String portName) {
        return new SerialConfig(
                portName,
                9600,                              // по умолчанию
                8,
                1,
                SerialPhysicalLayer.PARITY_NONE,
                SerialPhysicalLayer.FLOW_NONE,
                true,
                true
        );
    }
}