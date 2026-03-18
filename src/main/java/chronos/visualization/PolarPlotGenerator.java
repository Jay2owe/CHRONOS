package chronos.visualization;

import chronos.rhythm.RayleighResult;

import ij.ImagePlus;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;

/**
 * Creates circular/polar plots of phase distribution using Java2D.
 * Plots phase dots on the unit circle, Rayleigh mean vector arrow,
 * and annotates with R, p-value, mean direction, and hour labels.
 */
public class PolarPlotGenerator {

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final int S = 3; // DPI scale factor

    private PolarPlotGenerator() { }

    /**
     * Generates a polar phase plot.
     *
     * @param phasesRad  phase values in radians for each ROI
     * @param rayleigh   Rayleigh test result (may be null)
     * @param imageSize  base size of the output image in pixels (square), scaled by 3x
     * @return ImagePlus with rendered polar plot
     */
    public static ImagePlus generate(double[] phasesRad, RayleighResult rayleigh,
                                      int imageSize) {
        if (imageSize < 200) imageSize = 400;
        imageSize *= S;

        BufferedImage img = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, imageSize, imageSize);

        int cx = imageSize / 2;
        int cy = imageSize / 2;
        int radius = (int) (imageSize * 0.35);

        // Draw concentric circles (0.25, 0.5, 0.75, 1.0)
        g2.setColor(new Color(220, 220, 220));
        g2.setStroke(new BasicStroke(0.5f * S));
        for (double frac = 0.25; frac <= 1.0; frac += 0.25) {
            int r = (int) (radius * frac);
            g2.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        }

        // Draw unit circle (outer)
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1.5f * S));
        g2.draw(new Ellipse2D.Double(cx - radius, cy - radius, 2 * radius, 2 * radius));

        // Draw axes
        g2.setColor(new Color(180, 180, 180));
        g2.setStroke(new BasicStroke(0.5f * S));
        g2.draw(new Line2D.Double(cx - radius, cy, cx + radius, cy));
        g2.draw(new Line2D.Double(cx, cy - radius, cx, cy + radius));

        // Hour labels around circle (0, 6, 12, 18)
        // Convention: 0h at top, clockwise
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SansSerif", Font.BOLD, 14 * S));
        FontMetrics fm = g2.getFontMetrics();

        String[] hourLabels = {"0", "6", "12", "18"};
        double[] hourAngles = {-Math.PI / 2, 0, Math.PI / 2, Math.PI};  // mathematical angles

        for (int i = 0; i < 4; i++) {
            double angle = hourAngles[i];
            int lx = cx + (int) ((radius + 18 * S) * Math.cos(angle));
            int ly = cy + (int) ((radius + 18 * S) * Math.sin(angle));
            String lbl = hourLabels[i] + "h";
            int strW = fm.stringWidth(lbl);
            g2.drawString(lbl, lx - strW / 2, ly + fm.getAscent() / 2);
        }

        // Plot phase dots
        int dotRadius = 5 * S;
        g2.setColor(new Color(30, 100, 200, 200));
        for (int i = 0; i < phasesRad.length; i++) {
            if (Double.isNaN(phasesRad[i])) continue;
            double screenAngle = phasesRad[i] - Math.PI / 2;

            int dx = cx + (int) (radius * Math.cos(screenAngle));
            int dy = cy + (int) (radius * Math.sin(screenAngle));
            g2.fill(new Ellipse2D.Double(dx - dotRadius, dy - dotRadius,
                    2 * dotRadius, 2 * dotRadius));
        }

        // Draw Rayleigh mean vector
        if (rayleigh != null && !Double.isNaN(rayleigh.meanDirectionRad)) {
            double R = rayleigh.vectorLength;
            double meanAngle = rayleigh.meanDirectionRad - Math.PI / 2;

            int arrowLen = (int) (radius * R);
            int ax = cx + (int) (arrowLen * Math.cos(meanAngle));
            int ay = cy + (int) (arrowLen * Math.sin(meanAngle));

            // Arrow shaft
            g2.setColor(Color.RED);
            g2.setStroke(new BasicStroke(2.5f * S));
            g2.draw(new Line2D.Double(cx, cy, ax, ay));

            // Arrowhead
            double arrowHeadSize = 10 * S;
            double angle1 = meanAngle + Math.PI + Math.PI / 6;
            double angle2 = meanAngle + Math.PI - Math.PI / 6;
            int[] xPts = {
                    ax,
                    ax + (int) (arrowHeadSize * Math.cos(angle1)),
                    ax + (int) (arrowHeadSize * Math.cos(angle2))
            };
            int[] yPts = {
                    ay,
                    ay + (int) (arrowHeadSize * Math.sin(angle1)),
                    ay + (int) (arrowHeadSize * Math.sin(angle2))
            };
            g2.fillPolygon(xPts, yPts, 3);

            // Annotations
            g2.setColor(Color.BLACK);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 12 * S));
            int textY = imageSize - 55 * S;
            g2.drawString(String.format("R = %.3f", rayleigh.vectorLength), 10 * S, textY);
            g2.drawString(String.format("p = %.4f", rayleigh.pValue), 10 * S, textY + 16 * S);
            g2.drawString(String.format("Mean = %.1fh", rayleigh.meanDirectionHours), 10 * S, textY + 32 * S);
        }

        // Title
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SansSerif", Font.BOLD, 14 * S));
        String title = "Phase Distribution (n=" + countValid(phasesRad) + ")";
        int titleW = g2.getFontMetrics().stringWidth(title);
        g2.drawString(title, (imageSize - titleW) / 2, 18 * S);

        g2.dispose();
        return new ImagePlus("Polar Phase Plot", img);
    }

    private static int countValid(double[] arr) {
        int n = 0;
        for (int i = 0; i < arr.length; i++) {
            if (!Double.isNaN(arr[i])) n++;
        }
        return n;
    }
}
