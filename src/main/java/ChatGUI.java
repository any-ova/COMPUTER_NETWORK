import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

public class ChatGUI {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private JFrame frame;
    private JTextArea chatArea;
    private JList<String> userList;
    private DefaultListModel<String> userListModel;
    private JTextField inputField;
    private JTextField downloadDirField;
    private JComboBox<String> recipientCombo;

    private SerialPhysicalLayer phy;
    private DataLinkLayer dll;
    private ChatApplicationLayer appLayer;
    private String myNick;
    private int myAddr;
    private boolean connected = false;
    private List<String> history = Collections.synchronizedList(new ArrayList<>());
    private boolean isClosing = false;

    private String portName;
    private int baudRate;
    private int dataBits;
    private String parity;

    public void startWithParams(String portName, int addr, String nick, int baud, int dataBits, String parity) {
        this.myNick = nick;
        this.myAddr = addr;
        this.portName = portName;
        this.baudRate = baud;
        this.dataBits = dataBits;
        this.parity = parity;

        try {
            SerialConfig serialConfig = new SerialConfig(
                    portName, baud, dataBits, 1,
                    parity.equals("EVEN") ? SerialPhysicalLayer.PARITY_EVEN :
                            (parity.equals("ODD") ? SerialPhysicalLayer.PARITY_ODD : SerialPhysicalLayer.PARITY_NONE),
                    SerialPhysicalLayer.FLOW_NONE, true, true
            );

            phy = new SerialPhysicalLayer(serialConfig);
            phy.open();

            final Map<Integer, String>[] usersRef = new Map[]{new HashMap<>()};
            final ChatApplicationLayer[] appLayerRef = new ChatApplicationLayer[1];

            dll = new DataLinkLayer(
                    phy.getInputStream(), phy.getOutputStream(), addr, nick,
                    new DataLinkLayer.Callbacks() {
                        @Override public void onChat(String fromName, int fromAddr, String text) {
                            String cleanText = text.replace('\u001F', ' ');
                            String line = now() + " " + fromName + " (" + fromAddr + ")> " + cleanText;
                            history.add(line);
                            SwingUtilities.invokeLater(() -> {
                                if (chatArea != null) {
                                    chatArea.append(line + "\n");
                                    chatArea.setCaretPosition(chatArea.getDocument().getLength());
                                }
                            });
                        }
                        @Override public void onSystem(String text) {
                            SwingUtilities.invokeLater(() -> {
                                if (chatArea != null) chatArea.append(now() + " [SYS] " + text + "\n");
                            });
                        }
                        @Override public void onUsers(Map<Integer, String> users) {
                            usersRef[0] = new HashMap<>(users);
                            if (appLayerRef[0] != null) appLayerRef[0].updateUsers(usersRef[0]);
                            SwingUtilities.invokeLater(() -> updateUserList(usersRef[0]));
                        }
                        @Override public void onDisconnected() {
                            SwingUtilities.invokeLater(() -> {
                                if (chatArea != null) chatArea.append(now() + " [SYS] ОТКЛЮЧЕНИЕ\n");
                            });
                        }
                    }
            );

            dll.start();
            appLayer = new ChatApplicationLayer(dll, nick);
            appLayerRef[0] = appLayer;
            appLayer.start();
            connected = true;
            createAndShowGUI();

            new Thread(() -> {
                while (connected) {
                    try {
                        AppMessage msg = appLayer.incomingQueue.take();
                        String line = now() + " " + msg.fromNick + "> " + msg.text;
                        history.add(line);
                        SwingUtilities.invokeLater(() -> {
                            if (chatArea != null) {
                                chatArea.append(line + "\n");
                                chatArea.setCaretPosition(chatArea.getDocument().getLength());
                            }
                        });
                    } catch (InterruptedException e) { break; }
                }
            }).start();

            new Thread(() -> {
                while (connected) {
                    try {
                        SystemPacket pkt = appLayer.systemQueue.take();
                        String line = now() + " [SYS] " + pkt.info;
                        history.add(line);
                        SwingUtilities.invokeLater(() -> {
                            if (chatArea != null) {
                                chatArea.append(line + "\n");
                                chatArea.setCaretPosition(chatArea.getDocument().getLength());
                            }
                        });
                    } catch (InterruptedException e) { break; }
                }
            }).start();

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "ОШИБКА: " + e.getMessage());
        }
    }

    private void updateUserList(Map<Integer, String> users) {
        if (userListModel != null) userListModel.clear();
        if (recipientCombo != null) {
            recipientCombo.removeAllItems();
            recipientCombo.addItem("ВСЕМ (broadcast)");
        }
        for (Map.Entry<Integer, String> entry : users.entrySet()) {
            if (entry.getKey() != myAddr) {
                if (userListModel != null) userListModel.addElement(entry.getValue() + " (" + entry.getKey() + ")");
                if (recipientCombo != null) recipientCombo.addItem(entry.getValue());
            }
        }
    }

    private void createAndShowGUI() {
        frame = new JFrame("RESIDENT EVIL :: ТЕРМИНАЛ — " + myNick + " [АДР:" + myAddr + "]");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { disconnect(); }
        });
        frame.setSize(1200, 800);
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(ChatTheme.BG_DARK);

        JPanel mainPanel = new JPanel(new BorderLayout(15, 15));
        mainPanel.setBackground(ChatTheme.BG_DARK);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // ===== ЛЕВАЯ ПАНЕЛЬ =====
        JPanel leftPanel = ChatTheme.createGlassPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setPreferredSize(new Dimension(300, 0));

        JLabel titleLabel = new JLabel("UMBRELLA");
        titleLabel.setFont(ChatTheme.retroFontLarge);
        titleLabel.setForeground(ChatTheme.ACCENT_RED);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        leftPanel.add(titleLabel);
        leftPanel.add(Box.createVerticalStrut(12));

        JLabel nameLabel = new JLabel("ПОЛЬЗОВАТЕЛЬ: " + myNick);
        nameLabel.setFont(ChatTheme.retroFont);
        nameLabel.setForeground(ChatTheme.TEXT_LIGHT);
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        leftPanel.add(nameLabel);
        leftPanel.add(Box.createVerticalStrut(4));

        JLabel addrLabel = new JLabel("АДРЕС: " + myAddr);
        addrLabel.setFont(ChatTheme.retroFontSmall);
        addrLabel.setForeground(ChatTheme.TEXT_GREEN);
        addrLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        leftPanel.add(addrLabel);
        leftPanel.add(Box.createVerticalStrut(18));

        leftPanel.add(ChatTheme.createSectionTitle("ПАРАМЕТРЫ ПОРТА"));
        leftPanel.add(Box.createVerticalStrut(6));

        JPanel portPanel = new JPanel();
        portPanel.setLayout(new BoxLayout(portPanel, BoxLayout.Y_AXIS));
        portPanel.setOpaque(false);
        portPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel portLabel = new JLabel("ПОРТ: " + portName);
        portLabel.setFont(ChatTheme.retroFont);
        portLabel.setForeground(ChatTheme.TEXT_LIGHT);
        portPanel.add(portLabel);

        JLabel baudLabel = new JLabel("СКОРОСТЬ: " + baudRate + " бод");
        baudLabel.setFont(ChatTheme.retroFont);
        baudLabel.setForeground(ChatTheme.TEXT_LIGHT);
        portPanel.add(baudLabel);

        JLabel dataLabel = new JLabel("БИТЫ ДАННЫХ: " + dataBits);
        dataLabel.setFont(ChatTheme.retroFont);
        dataLabel.setForeground(ChatTheme.TEXT_LIGHT);
        portPanel.add(dataLabel);

        JLabel parityLabel = new JLabel("ЧЁТНОСТЬ: " + parity);
        parityLabel.setFont(ChatTheme.retroFont);
        parityLabel.setForeground(ChatTheme.TEXT_LIGHT);
        portPanel.add(parityLabel);

        JLabel stopLabel = new JLabel("СТОП-БИТЫ: 1");
        stopLabel.setFont(ChatTheme.retroFont);
        stopLabel.setForeground(ChatTheme.TEXT_LIGHT);
        portPanel.add(stopLabel);

        leftPanel.add(portPanel);
        leftPanel.add(Box.createVerticalStrut(18));

        leftPanel.add(ChatTheme.createSectionTitle("АКТИВНЫЕ УЗЛЫ"));
        leftPanel.add(Box.createVerticalStrut(6));

        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setBackground(ChatTheme.INPUT_BG);
        userList.setForeground(ChatTheme.TEXT_GREEN);
        userList.setFont(ChatTheme.retroFont);
        userList.setBorder(BorderFactory.createLineBorder(ChatTheme.BORDER_COLOR, 1));
        userList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selected = userList.getSelectedValue();
                    if (selected != null) {
                        String nick = selected.split(" ")[0];
                        inputField.setText("/to " + nick + " ");
                        inputField.requestFocus();
                    }
                }
            }
        });
        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(BorderFactory.createEmptyBorder());
        userScroll.setPreferredSize(new Dimension(280, 140));
        leftPanel.add(userScroll);
        leftPanel.add(Box.createVerticalStrut(10));

        JButton linkBtn = ChatTheme.createStyledButton("ОТПРАВИТЬ LINK");
        linkBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        linkBtn.addActionListener(e -> sendLink());
        leftPanel.add(linkBtn);
        leftPanel.add(Box.createVerticalStrut(18));

        leftPanel.add(ChatTheme.createSectionTitle("ПАПКА ЗАГРУЗОК"));
        leftPanel.add(Box.createVerticalStrut(6));

        downloadDirField = ChatTheme.createStyledTextField();
        downloadDirField.setText("./downloads/");
        downloadDirField.setAlignmentX(Component.CENTER_ALIGNMENT);
        leftPanel.add(downloadDirField);
        leftPanel.add(Box.createVerticalStrut(6));

        JButton changeDirBtn = ChatTheme.createStyledButton("ВЫБРАТЬ ПАПКУ");
        changeDirBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        changeDirBtn.addActionListener(e -> changeDownloadDir());
        leftPanel.add(changeDirBtn);
        leftPanel.add(Box.createVerticalStrut(12));

        JButton fileBtn = ChatTheme.createStyledButton("ОТПРАВИТЬ ФАЙЛ");
        fileBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        fileBtn.addActionListener(e -> sendFile());
        leftPanel.add(fileBtn);
        leftPanel.add(Box.createVerticalGlue());

        // ===== ЦЕНТРАЛЬНАЯ ПАНЕЛЬ (УЖЕ) =====
        JPanel centerPanel = ChatTheme.createGlassPanel();
        centerPanel.setLayout(new BorderLayout(8, 8));

        JLabel chatTitle = ChatTheme.createSectionTitle("ЖУРНАЛ СООБЩЕНИЙ");
        chatTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        centerPanel.add(chatTitle, BorderLayout.NORTH);

        chatArea = ChatTheme.createStyledTextArea();
        chatArea.setEditable(false);
        chatArea.setRows(20);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(BorderFactory.createEmptyBorder());
        centerPanel.add(chatScroll, BorderLayout.CENTER);

        // ===== НИЖНЯЯ ПАНЕЛЬ =====
        JPanel bottomPanel = ChatTheme.createGlassPanel();
        bottomPanel.setLayout(new BorderLayout(8, 8));
        bottomPanel.setPreferredSize(new Dimension(0, 80));

        JPanel inputSubPanel = new JPanel(new BorderLayout(8, 0));
        inputSubPanel.setOpaque(false);

        recipientCombo = ChatTheme.createStyledComboBox();
        recipientCombo.addItem("ВСЕМ (broadcast)");
        recipientCombo.setPreferredSize(new Dimension(140, 35));
        inputSubPanel.add(recipientCombo, BorderLayout.WEST);

        inputField = ChatTheme.createStyledTextField();
        inputField.addActionListener(e -> sendMessage());
        inputSubPanel.add(inputField, BorderLayout.CENTER);

        JButton sendBtn = ChatTheme.createStyledButton("ОТПРАВИТЬ");
        sendBtn.addActionListener(e -> sendMessage());
        inputSubPanel.add(sendBtn, BorderLayout.EAST);

        bottomPanel.add(inputSubPanel, BorderLayout.CENTER);
        centerPanel.add(bottomPanel, BorderLayout.SOUTH);

        mainPanel.add(leftPanel, BorderLayout.WEST);
        mainPanel.add(centerPanel, BorderLayout.CENTER);

        frame.setContentPane(mainPanel);
        frame.setVisible(true);

        chatArea.append("════════════════════════════════════════════════════\n");
        chatArea.append("UMBRELLA CORPORATION ТЕРМИНАЛ v.2.0\n");
        chatArea.append("ПОЛЬЗОВАТЕЛЬ: " + myNick + " [АДР:" + myAddr + "]\n");
        chatArea.append("ПОРТ: " + portName + " | " + baudRate + " бод | " + dataBits + " бит | " + parity + " | СТОП:1\n");
        chatArea.append("ПАПКА ЗАГРУЗОК: " + downloadDirField.getText() + "\n");
        chatArea.append("════════════════════════════════════════════════════\n\n");
    }

    private void sendMessage() {
        if (!connected || inputField == null) return;
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        inputField.setText("");

        String recipient = recipientCombo != null ? (String) recipientCombo.getSelectedItem() : null;
        if (recipient != null && !recipient.equals("ВСЕМ (broadcast)") && !recipient.equals("ВСЕМ (broadcast)")) {
            sendToNick(recipient, text);
        } else {
            sendBroadcast(text);
        }
        if (chatArea != null) chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void sendBroadcast(String text) {
        String self = now() + " " + myNick + " [" + myAddr + "] > " + text;
        history.add(self);
        if (chatArea != null) chatArea.append(self + "\n");
        if (appLayer != null) appLayer.sendBroadcast(text);
    }

    private void sendToNick(String toNick, String text) {
        String self = now() + " " + myNick + " [" + myAddr + "] -> " + toNick + " > " + text;
        history.add(self);
        if (chatArea != null) chatArea.append(self + "\n");
        if (appLayer != null) appLayer.sendToNick(toNick, text);
    }

    private void sendLink() {
        if (!connected || dll == null) return;
        try {
            dll.sendLink();
            if (chatArea != null) chatArea.append(now() + " [SYS] LINK ОТПРАВЛЕН\n");
        } catch (IOException e) {
            if (chatArea != null) chatArea.append(now() + " [SYS] ОШИБКА LINK\n");
        }
    }

    private void sendFile() {
        if (!connected || appLayer == null) return;
        String toNick = JOptionPane.showInputDialog(frame, "КОМУ ОТПРАВИТЬ ФАЙЛ:");
        if (toNick == null || toNick.trim().isEmpty()) return;

        JFileChooser fileChooser = new JFileChooser(".");
        fileChooser.setDialogTitle("ВЫБЕРИТЕ ФАЙЛ");
        if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try {
                appLayer.sendFile(toNick, file.getAbsolutePath());
                if (chatArea != null) chatArea.append(now() + " [SYS] ОТПРАВКА ФАЙЛА: " + file.getName() + " -> " + toNick + "\n");
            } catch (Exception e) {
                if (chatArea != null) chatArea.append(now() + " [SYS] ОШИБКА ОТПРАВКИ ФАЙЛА\n");
            }
        }
    }

    private void changeDownloadDir() {
        if (!connected || appLayer == null) return;
        JFileChooser dirChooser = new JFileChooser(".");
        dirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        dirChooser.setDialogTitle("ВЫБЕРИТЕ ПАПКУ ДЛЯ ЗАГРУЗОК");
        if (dirChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            String dir = dirChooser.getSelectedFile().getAbsolutePath();
            downloadDirField.setText(dir);
            appLayer.setDownloadDirectory(dir);
            if (chatArea != null) chatArea.append(now() + " [SYS] ПАПКА ЗАГРУЗОК: " + dir + "\n");
        }
    }

    private void disconnect() {
        if (isClosing) return;
        isClosing = true;
        if (connected) {
            connected = false;
            if (appLayer != null) { try { appLayer.stop(); } catch (Exception ignored) {} }
            if (dll != null) { try { dll.stop(); } catch (Exception ignored) {} }
            if (phy != null) { try { phy.close(); } catch (Exception ignored) {} }
        }
        if (frame != null) frame.dispose();
        System.exit(0);
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}