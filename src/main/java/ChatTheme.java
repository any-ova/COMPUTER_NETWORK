import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class ChatTheme {

    // Цветовая палитра
    public static final Color BG_DARK = new Color(12, 12, 20);
    public static final Color PANEL_BG = new Color(20, 20, 35);
    public static final Color ACCENT_RED = new Color(180, 55, 45);
    public static final Color TEXT_LIGHT = new Color(210, 210, 220);
    public static final Color TEXT_GREEN = new Color(140, 210, 140);
    public static final Color BORDER_COLOR = new Color(80, 70, 60);
    public static final Color BUTTON_BG = new Color(45, 40, 55);
    public static final Color BUTTON_HOVER = new Color(65, 55, 70);
    public static final Color INPUT_BG = new Color(18, 18, 30);

    // Винтажные шрифты
    public static Font retroFont;
    public static Font retroFontSmall;
    public static Font retroFontLarge;
    public static Font retroFontTitle;

    static {
        retroFont = new Font("Courier New", Font.PLAIN, 14);
        retroFontSmall = new Font("Courier New", Font.PLAIN, 11);
        retroFontLarge = new Font("Courier New", Font.BOLD, 20);
        retroFontTitle = new Font("Courier New", Font.BOLD, 15);
    }

    public static JPanel createGlassPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(PANEL_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                new EmptyBorder(10, 10, 10, 10)
        ));
        return panel;
    }

    public static JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(BUTTON_BG);
        button.setForeground(TEXT_LIGHT);
        button.setFont(retroFont);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setBackground(BUTTON_HOVER);
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setBackground(BUTTON_BG);
            }
        });
        return button;
    }

    public static JTextField createStyledTextField() {
        JTextField field = new JTextField();
        field.setBackground(INPUT_BG);
        field.setForeground(TEXT_LIGHT);
        field.setCaretColor(ACCENT_RED);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
        field.setFont(retroFont);
        return field;
    }

    public static JTextArea createStyledTextArea() {
        JTextArea area = new JTextArea();
        area.setBackground(BG_DARK);
        area.setForeground(TEXT_GREEN);
        area.setCaretColor(ACCENT_RED);
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        area.setFont(retroFontSmall);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    public static JComboBox<String> createStyledComboBox() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setBackground(INPUT_BG);
        combo.setForeground(TEXT_LIGHT);
        combo.setFont(retroFont);
        combo.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
        return combo;
    }

    public static JLabel createSectionTitle(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(retroFontTitle);
        label.setForeground(ACCENT_RED);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }
}
