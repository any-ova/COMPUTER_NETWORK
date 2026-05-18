import javax.swing.*;
import java.awt.*;

public class LoginDialog {

    private JDialog dialog;
    private JComboBox<String> portCombo;
    private JTextField addrField;
    private JTextField nickField;
    private JTextField baudField;
    private JTextField dataBitsField;
    private JComboBox<String> parityCombo;

    private boolean connected = false;
    private String port;
    private int addr;
    private String nick;
    private int baud;
    private int dataBits;
    private String parity;

    public void show() {
        dialog = new JDialog();
        dialog.setTitle("RESIDENT EVIL :: ТЕРМИНАЛ");
        dialog.setModal(true);
        dialog.setSize(560, 540);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLocationRelativeTo(null);
        dialog.getContentPane().setBackground(ChatTheme.BG_DARK);

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(ChatTheme.BG_DARK);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 12, 8, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JPanel glassPanel = ChatTheme.createGlassPanel();
        glassPanel.setLayout(new GridBagLayout());
        glassPanel.setPreferredSize(new Dimension(420, 420));

        // ЗАГОЛОВОК (крупный винтажный)
        JLabel titleLabel = new JLabel("UMBRELLA TERMINAL");
        titleLabel.setFont(ChatTheme.retroFontLarge);
        titleLabel.setForeground(ChatTheme.ACCENT_RED);
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.gridwidth = 2;
        glassPanel.add(titleLabel, gbc);

        gbc.gridwidth = 1;
        int row = 1;

        // COM порт
        gbc.gridx = 0; gbc.gridy = row;
        JLabel portLabel = new JLabel("> COM PORT:");
        portLabel.setFont(ChatTheme.retroFont);
        portLabel.setForeground(ChatTheme.TEXT_GREEN);
        glassPanel.add(portLabel, gbc);

        portCombo = new JComboBox<>();
        portCombo.setFont(ChatTheme.retroFont);
        portCombo.setBackground(ChatTheme.INPUT_BG);
        portCombo.setForeground(ChatTheme.TEXT_LIGHT);
        portCombo.setBorder(BorderFactory.createLineBorder(ChatTheme.BORDER_COLOR, 1));
        refreshPorts();

        JButton refreshBtn = ChatTheme.createStyledButton("REFRESH");
        refreshBtn.setFont(ChatTheme.retroFontSmall);
        refreshBtn.addActionListener(e -> refreshPorts());

        JPanel portPanel = new JPanel(new BorderLayout(5, 0));
        portPanel.setOpaque(false);
        portPanel.add(portCombo, BorderLayout.CENTER);
        portPanel.add(refreshBtn, BorderLayout.EAST);
        gbc.gridx = 1; gbc.gridy = row; glassPanel.add(portPanel, gbc);
        row++;

        // Адрес
        gbc.gridx = 0; gbc.gridy = row;
        JLabel addrLabel = new JLabel("> ADDRESS (1-126):");
        addrLabel.setFont(ChatTheme.retroFont);
        addrLabel.setForeground(ChatTheme.TEXT_GREEN);
        glassPanel.add(addrLabel, gbc);

        addrField = ChatTheme.createStyledTextField();
        addrField.setFont(ChatTheme.retroFont);
        gbc.gridx = 1; glassPanel.add(addrField, gbc);
        row++;

        // Никнейм
        gbc.gridx = 0; gbc.gridy = row;
        JLabel nickLabel = new JLabel("> NICKNAME:");
        nickLabel.setFont(ChatTheme.retroFont);
        nickLabel.setForeground(ChatTheme.TEXT_GREEN);
        glassPanel.add(nickLabel, gbc);

        nickField = ChatTheme.createStyledTextField();
        nickField.setFont(ChatTheme.retroFont);
        gbc.gridx = 1; glassPanel.add(nickField, gbc);
        row++;

        // Скорость
        gbc.gridx = 0; gbc.gridy = row;
        JLabel baudLabel = new JLabel("> BAUD RATE:");
        baudLabel.setFont(ChatTheme.retroFont);
        baudLabel.setForeground(ChatTheme.TEXT_GREEN);
        glassPanel.add(baudLabel, gbc);

        baudField = ChatTheme.createStyledTextField();
        baudField.setText("9600");
        baudField.setFont(ChatTheme.retroFont);
        gbc.gridx = 1; glassPanel.add(baudField, gbc);
        row++;

        // Биты данных
        gbc.gridx = 0; gbc.gridy = row;
        JLabel dataLabel = new JLabel("> DATA BITS:");
        dataLabel.setFont(ChatTheme.retroFont);
        dataLabel.setForeground(ChatTheme.TEXT_GREEN);
        glassPanel.add(dataLabel, gbc);

        dataBitsField = ChatTheme.createStyledTextField();
        dataBitsField.setText("8");
        dataBitsField.setFont(ChatTheme.retroFont);
        gbc.gridx = 1; glassPanel.add(dataBitsField, gbc);
        row++;

        // Чётность
        gbc.gridx = 0; gbc.gridy = row;
        JLabel parityLabel = new JLabel("> PARITY:");
        parityLabel.setFont(ChatTheme.retroFont);
        parityLabel.setForeground(ChatTheme.TEXT_GREEN);
        glassPanel.add(parityLabel, gbc);

        parityCombo = ChatTheme.createStyledComboBox();
        parityCombo.setFont(ChatTheme.retroFont);
        parityCombo.addItem("NONE");
        parityCombo.addItem("EVEN");
        parityCombo.addItem("ODD");
        gbc.gridx = 1; glassPanel.add(parityCombo, gbc);
        row++;

        // Кнопка подключения
        JButton connectBtn = ChatTheme.createStyledButton("CONNECT TO NETWORK");
        connectBtn.setFont(ChatTheme.retroFont);
        connectBtn.addActionListener(e -> tryConnect());
        gbc.gridx = 0; gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(20, 12, 12, 12);
        glassPanel.add(connectBtn, gbc);

        mainPanel.add(glassPanel);
        dialog.setContentPane(mainPanel);
        dialog.setVisible(true);
    }

    private void refreshPorts() {
        portCombo.removeAllItems();
        String[] ports = SerialPhysicalLayer.listPorts();
        for (String p : ports) portCombo.addItem(p);
        if (ports.length == 0) portCombo.addItem("NO PORTS");
    }

    private void tryConnect() {
        try {
            port = (String) portCombo.getSelectedItem();
            if (port == null || port.isEmpty() || port.equals("NO PORTS")) {
                JOptionPane.showMessageDialog(dialog, "NO COM PORT SELECTED");
                return;
            }
            addr = Integer.parseInt(addrField.getText().trim());
            if (addr < 1 || addr > 126) throw new NumberFormatException();
            nick = nickField.getText().trim();
            if (nick.isEmpty()) throw new IllegalArgumentException();
            baud = Integer.parseInt(baudField.getText().trim());
            dataBits = Integer.parseInt(dataBitsField.getText().trim());
            parity = (String) parityCombo.getSelectedItem();

            connected = true;
            dialog.dispose();

            SwingUtilities.invokeLater(() -> {
                ChatGUI chatGUI = new ChatGUI();
                chatGUI.startWithParams(port, addr, nick, baud, dataBits, parity);
            });

        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(dialog, "INVALID ADDRESS (1-126)");
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(dialog, "NICKNAME REQUIRED");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(dialog, "ERROR: " + ex.getMessage());
        }
    }
}