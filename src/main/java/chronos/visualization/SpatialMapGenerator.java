package chronos.visualization;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Creates spatial maps (phase, period, amplitude) overlaid on mean projection.
 * Phase map uses a circular HSV colormap; period and amplitude use sequential colormaps.
 * Color bar legends are rendered with Java2D.
 */
public class SpatialMapGenerator {

    private static final int COLOR_BAR_WIDTH = 30;
    private static final int COLOR_BAR_MARGIN = 10;
    private static final int LABEL_WIDTH = 60;

    private SpatialMapGenerator() { }

    /**
     * Creates a phase map with circular HSV colormap.
     *
     * @param meanProjection  mean projection image
     * @param rois            ROIs to fill
     * @param phasesHours     phase values in hours for each ROI
     * @param maxPhase        maximum phase value (typically 24)
     * @return ImagePlus with phase map overlay and color bar
     */
    public static ImagePlus phaseMap(ImagePlus meanProjection, Roi[] rois,
                                      double[] phasesHours, double maxPhase) {
        int w = meanProjection.getWidth();
        int h = meanProjection.getHeight();

        BufferedImage canvas = createBaseImage(meanProjection, w, h);
        Graphics2D g2 = canvas.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw ROIs with phase-mapped colors
        for (int i = 0; i < rois.length; i++) {
            if (Double.isNaN(phasesHours[i])) continue;
            float hue = (float) (phasesHours[i] / maxPhase);
            if (hue < 0) hue = 0;
            if (hue > 1) hue = 1;
            Color c = Color.getHSBColor(hue, 1.0f, 1.0f);
            Color semi = new Color(c.getRed(), c.getGreen(), c.getBlue(), 180);
            fillRoi(g2, rois[i], semi);
        }

        g2.dispose();

        // Add color bar
        BufferedImage withBar = addCircularColorBar(canvas, "Phase (h)", 0, maxPhase, true);
        return new ImagePlus("Phase Map", withBar);
    }

    /**
     * Creates a period map with sequential colormap.
     *
     * @param meanProjection  mean projection image
     * @param rois            ROIs to fill
     * @param periods         period values for each ROI
     * @param minP            minimum period for colormap
     * @param maxP            maximum period for colormap
     * @return ImagePlus with period map overlay and color bar
     */
    public static ImagePlus periodMap(ImagePlus meanProjection, Roi[] rois,
                                       double[] periods, double minP, double maxP) {
        int w = meanProjection.getWidth();
        int h = meanProjection.getHeight();

        BufferedImage canvas = createBaseImage(meanProjection, w, h);
        Graphics2D g2 = canvas.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double range = maxP - minP;
        for (int i = 0; i < rois.length; i++) {
            if (Double.isNaN(periods[i])) continue;
            double norm = (range > 0) ? (periods[i] - minP) / range : 0.5;
            norm = Math.max(0, Math.min(1, norm));
            Color c = viridisColor(norm);
            Color semi = new Color(c.getRed(), c.getGreen(), c.getBlue(), 180);
            fillRoi(g2, rois[i], semi);
        }

        g2.dispose();
        BufferedImage withBar = addSequentialColorBar(canvas, "Period (h)", minP, maxP);
        return new ImagePlus("Period Map", withBar);
    }

    /**
     * Creates an amplitude map with sequential colormap.
     *
     * @param meanProjection  mean projection image
     * @param rois            ROIs to fill
     * @param amplitudes      amplitude values for each ROI
     * @return ImagePlus with amplitude map overlay and color bar
     */
    public static ImagePlus amplitudeMap(ImagePlus meanProjection, Roi[] rois,
                                          double[] amplitudes) {
        // Find min/max amplitude
        double minA = Double.MAX_VALUE;
        double maxA = -Double.MAX_VALUE;
        for (int i = 0; i < amplitudes.length; i++) {
            if (!Double.isNaN(amplitudes[i])) {
                if (amplitudes[i] < minA) minA = amplitudes[i];
                if (amplitudes[i] > maxA) maxA = amplitudes[i];
            }
        }
        if (minA > maxA) { minA = 0; maxA = 1; }

        int w = meanProjection.getWidth();
        int h = meanProjection.getHeight();

        BufferedImage canvas = createBaseImage(meanProjection, w, h);
        Graphics2D g2 = canvas.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double range = maxA - minA;
        for (int i = 0; i < rois.length; i++) {
            if (Double.isNaN(amplitudes[i])) continue;
            double norm = (range > 0) ? (amplitudes[i] - minA) / range : 0.5;
            norm = Math.max(0, Math.min(1, norm));
            Color c = viridisColor(norm);
            Color semi = new Color(c.getRed(), c.getGreen(), c.getBlue(), 180);
            fillRoi(g2, rois[i], semi);
        }

        g2.dispose();
        BufferedImage withBar = addSequentialColorBar(canvas, "Amplitude", minA, maxA);
        return new ImagePlus("Amplitude Map", withBar);
    }

    // ---- Helper methods ----

    /**
     * Creates a grayscale base image from a mean projection.
     */
    private static BufferedImage createBaseImage(ImagePlus meanProjection, int w, int h) {
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = canvas.createGraphics();

        // Draw grayscale mean projection as background
        ImageProcessor ip = meanProjection.getProcessor().convertToByte(true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int val = ip.get(x, y) & 0xFF;
                int gray = (val << 16) | (val << 8) | val;
                canvas.setRGB(x, y, gray);
            }
        }
        g2.dispose();
        return canvas;
    }

    /**
     * Fills an ROI polygon with a semi-transparent color.
     */
    private static void fillRoi(Graphics2D g2, Roi roi, Color color) {
        g2.setColor(color);
        // Get the polygon shape from the ROI
        Shape shape = roiToShape(roi);
        if (shape != null) {
            g2.fill(shape);
        } else {
            // Fallback: fill bounding rectangle
            Rectangle bounds = roi.getBounds();
            g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    /**
     * Converts an ImageJ ROI to a Java2D Shape.
     */
    private static Shape roiToShape(Roi roi) {
        if (roi == null) return null;
        java.awt.Polygon poly = roi.getPolygon();
        if (poly != null && poly.npoints > 0) {
            return poly;
        }
        // Fallback to bounds rectangle
        Rectangle b = roi.getBounds();
        return new Rectangle(b.x, b.y, b.width, b.height);
    }

    /**
     * Adds a circular (HSV) color bar to the right of the image.
     */
    private static BufferedImage addCircularColorBar(BufferedImage src, String label,
                                                      double minVal, double maxVal,
                                                      boolean circular) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int barH = srcH - 40;
        int totalW = srcW + COLOR_BAR_MARGIN + COLOR_BAR_WIDTH + LABEL_WIDTH;

        BufferedImage result = new BufferedImage(totalW, srcH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = result.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, totalW, srcH);
        g2.drawImage(src, 0, 0, null);

        int barX = srcW + COLOR_BAR_MARGIN;
        int barY = 20;

        // Draw color bar
        for (int y = 0; y < barH; y++) {
            float frac = 1.0f - (float) y / barH;
            Color c;
            if (circular) {
                c = Color.getHSBColor(frac, 1.0f, 1.0f);
            } else {
                c = viridisColor(frac);
            }
            g2.setColor(c);
            g2.fillRect(barX, barY + y, COLOR_BAR_WIDTH, 1);
        }

        // Border
        g2.setColor(Color.BLACK);
        g2.drawRect(barX, barY, COLOR_BAR_WIDTH, barH);

        // Labels
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
        FontMetrics fm = g2.getFontMetrics();
        int labelX = barX + COLOR_BAR_WIDTH + 4;

        String topLabel = String.format("%.0f", maxVal);
        String midLabel = String.format("%.0f", (minVal + maxVal) / 2);
        String botLabel = String.format("%.0f", minVal);

        g2.drawString(topLabel, labelX, barY + fm.getAscent());
        g2.drawString(midLabel, labelX, barY + barH / 2 + fm.getAscent() / 2);
        g2.drawString(botLabel, labelX, barY + barH + fm.getAscent());

        // Title
        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        g2.drawString(label, barX, barY - 5);

        g2.dispose();
        return result;
    }

    /**
     * Adds a sequential color bar (viridis) to the right of the image.
     */
    private static BufferedImage addSequentialColorBar(BufferedImage src, String label,
                                                       double minVal, double maxVal) {
        return addCircularColorBar(src, label, minVal, maxVal, false);
    }

    /**
     * Approximation of the Viridis colormap.
     * Maps 0.0 (dark purple) to 1.0 (yellow).
     */
    static Color viridisColor(double t) {
        t = Math.max(0, Math.min(1, t));

        // Simplified viridis: dark purple -> blue -> teal -> green -> yellow
        double r, g, b;
        if (t < 0.25) {
            double s = t / 0.25;
            r = 0.267 + s * (0.229 - 0.267);
            g = 0.004 + s * (0.322 - 0.004);
            b = 0.329 + s * (0.546 - 0.329);
        } else if (t < 0.5) {
            double s = (t - 0.25) / 0.25;
            r = 0.229 + s * (0.127 - 0.229);
            g = 0.322 + s * (0.566 - 0.322);
            b = 0.546 + s * (0.551 - 0.546);
        } else if (t < 0.75) {
            double s = (t - 0.5) / 0.25;
            r = 0.127 + s * (0.531 - 0.127);
            g = 0.566 + s * (0.725 - 0.566);
            b = 0.551 + s * (0.338 - 0.551);
        } else {
            double s = (t - 0.75) / 0.25;
            r = 0.531 + s * (0.993 - 0.531);
            g = 0.725 + s * (0.906 - 0.725);
            b = 0.338 + s * (0.144 - 0.338);
        }

        return new Color(
                (int) Math.round(r * 255),
                (int) Math.round(g * 255),
                (int) Math.round(b * 255)
        );
    }
}
