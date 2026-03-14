package chronos.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * A modern iOS/Material-style toggle switch component.
 * Renders a colored pill-shaped track with a sliding circular thumb.
 */
public class ToggleSwitch extends JPanel {

    private static final int TRACK_WIDTH = 44;
    private static final int TRACK_HEIGHT = 22;
    private static final int THUMB_DIAMETER = 18;
    private static final int THUMB_MARGIN = 2;

    private static final Color COLOR_ON = new Color(76, 175, 80);       // green
    private static final Color COLOR_OFF = new Color(189, 189, 189);    // gray
    private static final Color COLOR_DISABLED = new Color(220, 220, 220);
    private static final Color COLOR_THUMB = Color.WHITE;
    private static final Color COLOR_THUMB_SHADOW = new Color(0, 0, 0, 40);

    private boolean selected;
    private final List<Runnable> changeListeners = new ArrayList<Runnable>();

    public ToggleSwitch(boolean initialState) {
        this.selected = initialState;
        setOpaque(false);
        setPreferredSize(new Dimension(TRACK_WIDTH, TRACK_HEIGHT));
        setMinimumSize(new Dimension(TRACK_WIDTH, TRACK_HEIGHT));
        setMaximumSize(new Dimension(TRACK_WIDTH, TRACK_HEIGHT));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!isEnabled()) return;
                selected = !selected;
                repaint();
                fireChangeListeners();
            }
        });
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean sel) {
        if (this.selected != sel) {
            this.selected = sel;
            repaint();
            fireChangeListeners();
        }
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void fireChangeListeners() {
        for (Runnable r : changeListeners) {
            r.run();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(enabled
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = TRACK_WIDTH;
        int h = TRACK_HEIGHT;

        // Track
        Color trackColor;
        if (!isEnabled()) {
            trackColor = COLOR_DISABLED;
        } else {
            trackColor = selected ? COLOR_ON : COLOR_OFF;
        }
        g2.setColor(trackColor);
        g2.fill(new RoundRectangle2D.Double(0, 0, w, h, h, h));

        // Thumb position
        int thumbX = selected ? (w - THUMB_DIAMETER - THUMB_MARGIN) : THUMB_MARGIN;
        int thumbY = (h - THUMB_DIAMETER) / 2;

        // Thumb shadow
        g2.setColor(COLOR_THUMB_SHADOW);
        g2.fillOval(thumbX + 1, thumbY + 1, THUMB_DIAMETER, THUMB_DIAMETER);

        // Thumb
        g2.setColor(COLOR_THUMB);
        g2.fillOval(thumbX, thumbY, THUMB_DIAMETER, THUMB_DIAMETER);

        g2.dispose();
    }
}
