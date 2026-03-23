package chronos.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Custom dialog with modern toggle switches instead of plain AWT checkboxes.
 * Provides section headers, labeled toggles, text fields, dropdowns, and help text.
 */
public class PipelineDialog {

    private static final Color BG_COLOR = new Color(245, 245, 245);
    private static final Color HEADER_COLOR = new Color(55, 71, 79);
    private static final Color LABEL_COLOR = new Color(33, 33, 33);
    private static final Color HELP_COLOR = new Color(117, 117, 117);

    private final JDialog dialog;
    private final JPanel contentPanel;
    private boolean wasCanceled = true;
    private boolean wasBackPressed = false;
    private boolean backEnabled = false;

    // Ordered lists for retrieval
    private final List<ToggleSwitch> toggles = new ArrayList<ToggleSwitch>();
    private final List<JTextField> textFields = new ArrayList<JTextField>();
    private final List<JComboBox<String>> combos = new ArrayList<JComboBox<String>>();
    private final List<JTextField> numericFields = new ArrayList<JTextField>();

    private final JPanel leftButtonPanel;
    private final JButton backButton;
    private final CountDownLatch latch = new CountDownLatch(1);

    private int toggleIndex = 0;
    private int textFieldIndex = 0;
    private int comboIndex = 0;
    private int numericFieldIndex = 0;

    public PipelineDialog(String title) {
        this(title, true);
    }

    public PipelineDialog(String title, boolean modal) {
        dialog = new JDialog((Frame) null, title, modal);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { latch.countDown(); }
        });

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(new EmptyBorder(15, 20, 10, 20));
        contentPanel.setBackground(BG_COLOR);

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(BG_COLOR);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(scrollPane, BorderLayout.CENTER);

        // Button panel: left-side buttons | right-side (Back / Cancel / OK)
        JPanel buttonBar = new JPanel(new BorderLayout());
        buttonBar.setBackground(BG_COLOR);

        leftButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        leftButtonPanel.setBackground(BG_COLOR);

        JPanel rightButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        rightButtonPanel.setBackground(BG_COLOR);
        backButton = new JButton("Back");
        JButton okBtn = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        backButton.setPreferredSize(new Dimension(80, 28));
        okBtn.setPreferredSize(new Dimension(80, 28));
        cancelBtn.setPreferredSize(new Dimension(80, 28));
        backButton.addActionListener(e -> { wasBackPressed = true; wasCanceled = true; latch.countDown(); dialog.dispose(); });
        okBtn.addActionListener(e -> { wasCanceled = false; latch.countDown(); dialog.dispose(); });
        cancelBtn.addActionListener(e -> { wasCanceled = true; latch.countDown(); dialog.dispose(); });
        backButton.setVisible(false);
        rightButtonPanel.add(backButton);
        rightButtonPanel.add(cancelBtn);
        rightButtonPanel.add(okBtn);

        buttonBar.add(leftButtonPanel, BorderLayout.WEST);
        buttonBar.add(rightButtonPanel, BorderLayout.EAST);
        dialog.getContentPane().add(buttonBar, BorderLayout.SOUTH);
    }

    /** Adds a bold section header. */
    public void addHeader(String text) {
        contentPanel.add(Box.createVerticalStrut(10));
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(HEADER_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(label);
        contentPanel.add(Box.createVerticalStrut(4));

        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(sep);
        contentPanel.add(Box.createVerticalStrut(6));
    }

    /** Adds a labeled toggle switch. Returns the ToggleSwitch for listener attachment. */
    public ToggleSwitch addToggle(String label, boolean defaultValue) {
        ToggleSwitch toggle = new ToggleSwitch(defaultValue);
        toggles.add(toggle);

        JPanel row = createRow();
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        row.add(toggle);

        contentPanel.add(row);
        contentPanel.add(Box.createVerticalStrut(4));
        return toggle;
    }

    /** Adds small explanatory text below the previous element. Returns the JLabel for dynamic updates. */
    public JLabel addHelpText(String text) {
        JLabel help = new JLabel("<html><body style='width:280px;'>" + text + "</body></html>");
        help.setFont(help.getFont().deriveFont(Font.ITALIC, 10f));
        help.setForeground(HELP_COLOR);
        help.setAlignmentX(Component.LEFT_ALIGNMENT);
        help.setBorder(new EmptyBorder(0, 24, 2, 0));
        contentPanel.add(help);
        contentPanel.add(Box.createVerticalStrut(2));
        return help;
    }

    /** Adds a labeled text input field. */
    public JTextField addStringField(String label, String defaultValue, int columns) {
        JPanel row = createRow();
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        JTextField tf = new JTextField(defaultValue, columns);
        tf.setMaximumSize(new Dimension(columns * 12, 24));
        row.add(tf);

        textFields.add(tf);
        contentPanel.add(row);
        contentPanel.add(Box.createVerticalStrut(4));
        return tf;
    }

    /** Adds a labeled dropdown. */
    public JComboBox<String> addChoice(String label, String[] items, String defaultItem) {
        JPanel row = createRow();
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        JComboBox<String> combo = new JComboBox<String>(items);
        if (defaultItem != null) combo.setSelectedItem(defaultItem);
        combo.setMaximumSize(new Dimension(280, 24));
        row.add(combo);

        combos.add(combo);
        contentPanel.add(row);
        contentPanel.add(Box.createVerticalStrut(4));
        return combo;
    }

    /** Adds a plain text message. Returns the JLabel for later updates. */
    public JLabel addMessage(String text) {
        JLabel label = new JLabel("<html><body style='width:280px;'>" + text + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(LABEL_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 4, 2, 0));
        contentPanel.add(label);
        contentPanel.add(Box.createVerticalStrut(4));
        return label;
    }

    /** Adds a button. Returns the JButton for listener attachment. */
    public JButton addButton(String label) {
        JPanel row = createRow();
        JButton btn = new JButton(label);
        btn.setFocusPainted(false);
        row.add(btn);
        contentPanel.add(row);
        contentPanel.add(Box.createVerticalStrut(4));
        return btn;
    }

    /** Adds a labeled numeric input field. */
    public JTextField addNumericField(String label, double defaultValue, int decimals) {
        JPanel row = createRow();
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        String val = decimals == 0 ? String.valueOf((int) defaultValue) : String.valueOf(defaultValue);
        JTextField tf = new JTextField(val, 8);
        tf.setMaximumSize(new Dimension(96, 24));
        row.add(tf);
        numericFields.add(tf);
        contentPanel.add(row);
        contentPanel.add(Box.createVerticalStrut(4));
        return tf;
    }

    /** Adds vertical spacing. */
    public void addSpacer(int height) {
        contentPanel.add(Box.createVerticalStrut(height));
    }

    /** Shows the dialog (blocking). Returns true if OK was pressed. False for Cancel or Back. */
    public boolean showDialog() {
        wasBackPressed = false;
        dialog.pack();
        // Constrain height
        Dimension pref = dialog.getPreferredSize();
        int maxH = (int) (Toolkit.getDefaultToolkit().getScreenSize().height * 0.8);
        if (pref.height > maxH) {
            dialog.setSize(pref.width + 30, maxH); // +30 for scrollbar
        }
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);

        // For non-modal dialogs, setVisible returns immediately — wait on the latch
        if (!dialog.isModal()) {
            try { latch.await(); } catch (InterruptedException ignored) {}
        }

        return !wasCanceled;
    }

    public boolean wasCanceled() {
        return wasCanceled;
    }

    /** Enables the Back button on this dialog. */
    public void enableBackButton() {
        backEnabled = true;
        backButton.setVisible(true);
    }

    /** Adds a button to the bottom-left footer area (same row as OK/Cancel). */
    public JButton addFooterButton(String label) {
        JButton btn = new JButton(label);
        btn.setPreferredSize(new Dimension(90, 28));
        leftButtonPanel.add(btn);
        return btn;
    }

    /** Returns true if the user pressed Back (not Cancel, not OK). */
    public boolean wasBackPressed() {
        return wasBackPressed;
    }

    // Sequential retrieval methods (matching GenericDialog pattern)

    public boolean getNextBoolean() {
        return toggles.get(toggleIndex++).isSelected();
    }

    public String getNextString() {
        return textFields.get(textFieldIndex++).getText();
    }

    public String getNextChoice() {
        return (String) combos.get(comboIndex++).getSelectedItem();
    }

    public double getNextNumber() {
        String text = numericFields.get(numericFieldIndex++).getText().trim();
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private JPanel createRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setBorder(new EmptyBorder(0, 4, 0, 4));
        return row;
    }
}
