import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class SerialPhysicalLayer {

    public static final int PARITY_NONE  = 0;
    public static final int PARITY_EVEN  = 1;
    public static final int PARITY_ODD   = 2;

    public static final int FLOW_NONE    = 0;
    public static final int FLOW_RTSCTS  = 1;
    public static final int FLOW_XONXOFF = 2;

    private final SerialConfig cfg;

    private SerialPort port;
    private InputStream in;
    private OutputStream out;

    public SerialPhysicalLayer(SerialConfig cfg) {
        this.cfg = cfg;
    }

    public static String[] listPorts() {
        return Arrays.stream(SerialPort.getCommPorts())
                .map(SerialPort::getSystemPortName)
                .toArray(String[]::new);
    }

    public synchronized void open() throws IOException {
        port = SerialPort.getCommPort(cfg.portName);
        if (port == null) throw new IOException("Port not found: " + cfg.portName);
        port.setComPortParameters(cfg.baudRate, cfg.dataBits, mapStopBits(cfg.stopBits), mapParity(cfg.parity));
        port.setFlowControl(mapFlow(cfg.flowControl));
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);

        if (!port.openPort()) {
            throw new IOException("Cannot open port: " + cfg.portName);
        }

        // === ОЧИЩАЕМ БУФЕРЫ ===
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        port.getInputStream().skip(port.getInputStream().available());

        System.err.println("[PHY] Serial port buffers cleared");

        System.err.printf(
                "[PHY] Opened %s: baud=%d dataBits=%d stopBits=%d parity=%s flow=%s%n",
                cfg.portName,
                cfg.baudRate,
                cfg.dataBits,
                cfg.stopBits,
                parityToString(cfg.parity),
                flowToString(cfg.flowControl)
        );
        if (cfg.dtr) port.setDTR(); else port.clearDTR();
        if (cfg.rts) port.setRTS(); else port.clearRTS();

        in = port.getInputStream();
        out = port.getOutputStream();
    }

    public synchronized void close() {
        if (in != null) try { in.close(); } catch (Exception ignored) {}
        if (out != null) try { out.close(); } catch (Exception ignored) {}
        in = null;
        out = null;

        if (port != null) {
            try { port.closePort(); } catch (Exception ignored) {}
            port = null;
        }
    }

    public InputStream getInputStream() { return in; }
    public OutputStream getOutputStream() { return out; }

    public void writeBytes(byte[] data) throws IOException {
        if (out == null) throw new IOException("Port not open");
        out.write(data);
        out.flush();
    }

    private static int mapParity(int p) {
        return switch (p) {
            case PARITY_EVEN -> SerialPort.EVEN_PARITY;
            case PARITY_ODD  -> SerialPort.ODD_PARITY;
            default -> SerialPort.NO_PARITY;
        };
    }

    private static int mapStopBits(int sb) {
        return (sb == 2) ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT;
    }

    private static int mapFlow(int fc) {
        return switch (fc) {
            case FLOW_RTSCTS ->
                    SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
            case FLOW_XONXOFF ->
                    SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
            default ->
                    SerialPort.FLOW_CONTROL_DISABLED;
        };
    }

    private static String parityToString(int p) {
        return switch (p) {
            case PARITY_EVEN -> "EVEN";
            case PARITY_ODD  -> "ODD";
            default -> "NONE";
        };
    }

    private static String flowToString(int fc) {
        return switch (fc) {
            case FLOW_RTSCTS -> "RTS/CTS";
            case FLOW_XONXOFF -> "XON/XOFF";
            default -> "NONE";
        };
    }
}