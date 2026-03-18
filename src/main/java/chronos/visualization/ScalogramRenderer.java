package chronos.visualization;

import chronos.rhythm.RidgeResult;
import chronos.rhythm.WaveletResult;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.LUT;
import ij.measure.Calibration;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Renders wavelet scalogram as an ImageJ image.
 * Applies log10 power transform, Fire LUT, overlays COI boundary,
 * ridge line, and significance contours. Adds color bar.
 */
public class ScalogramRenderer {

    private static final int S = 3; // DPI scale factor

    private ScalogramRenderer() { }

    /**
     * Renders a wavelet scalogram.
     *
     * @param wavelet  wavelet analysis result
     * @param roiName  ROI name for the title
     * @return ImagePlus with rendered scalogram
     */
    public static ImagePlus render(WaveletResult wavelet, String roiName) {
        if (wavelet == null || wavelet.power == null) return null;

        int nScales = wavelet.power.length;
        int nTime = wavelet.power[0].length;

        // Create log10 power image
        FloatProcessor fp = new FloatProcessor(nTime, nScales);
        float minPower = Float.MAX_VALUE;
        float maxPower = -Float.MAX_VALUE;

        for (int j = 0; j < nScales; j++) {
            for (int t = 0; t < nTime; t++) {
                double power = wavelet.power[j][t];
                float logPow = (power > 0) ? (float) Math.log10(power) : -10f;
                // Row 0 = longest period (top), last row = shortest period (bottom)
                fp.setf(t, nScales - 1 - j, logPow);
                if (logPow < minPower) minPower = logPow;
                if (logPow > maxPower) maxPower = logPow;
            }
        }

        fp.setMinAndMax(minPower, maxPower);

        // Scale up the source image for higher DPI
        int scaledW = nTime * S;
        int scaledH = nScales * S;

        // Convert to RGB for overlay drawing
        LUT lut = KymographGenerator.createFireLUT();
        byte[] rLut = new byte[256];
        byte[] gLut = new byte[256];
        byte[] bLut = new byte[256];
        lut.getReds(rLut);
        lut.getGreens(gLut);
        lut.getBlues(bLut);

        float range = maxPower - minPower;
        if (range <= 0) range = 1f;

        BufferedImage colorImg = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < nScales; y++) {
            for (int x = 0; x < nTime; x++) {
                float val = fp.getf(x, y);
                int idx = (int) ((val - minPower) / range * 255);
                if (idx < 0) idx = 0;
                if (idx > 255) idx = 255;
                int rgb = ((rLut[idx] & 0xFF) << 16) | ((gLut[idx] & 0xFF) << 8) | (bLut[idx] & 0xFF);
                // Fill S×S block
                for (int dy = 0; dy < S; dy++) {
                    for (int dx = 0; dx < S; dx++) {
                        colorImg.setRGB(x * S + dx, y * S + dy, rgb);
                    }
                }
            }
        }

        // Draw overlays
        Graphics2D g2 = colorImg.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // COI boundary (white dashed line)
        if (wavelet.coneOfInfluence != null) {
            g2.setColor(Color.WHITE);
            float[] dash = {4f * S, 4f * S};
            g2.setStroke(new BasicStroke(1.5f * S, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, dash, 0f));

            int prevY = -1;
            for (int t = 0; t < nTime; t++) {
                double coiPeriod = wavelet.coneOfInfluence[t];
                int scaleIdx = findNearestScaleIndex(wavelet.periods, coiPeriod);
                int yPos = (nScales - 1 - scaleIdx) * S + S / 2;
                int xPos = t * S + S / 2;

                if (prevY >= 0 && t > 0) {
                    g2.drawLine((t - 1) * S + S / 2, prevY, xPos, yPos);
                }
                prevY = yPos;
            }
        }

        // Ridge line (white solid line)
        RidgeResult ridge = wavelet.ridge;
        if (ridge != null && ridge.ridgeScaleIndices != null) {
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2.0f * S));

            for (int t = 1; t < nTime && t < ridge.ridgeScaleIndices.length; t++) {
                int y1 = (nScales - 1 - ridge.ridgeScaleIndices[t - 1]) * S + S / 2;
                int y2 = (nScales - 1 - ridge.ridgeScaleIndices[t]) * S + S / 2;
                g2.drawLine((t - 1) * S + S / 2, y1, t * S + S / 2, y2);
            }
        }

        // Significance contours
        if (wavelet.significance != null) {
            g2.setColor(new Color(255, 255, 255, 128));
            g2.setStroke(new BasicStroke(0.5f * S));

            for (int j = 0; j < nScales; j++) {
                for (int t = 1; t < nTime; t++) {
                    boolean thisSig = wavelet.significance[j][t] > 1.0;
                    boolean prevSig = wavelet.significance[j][t - 1] > 1.0;
                    if (thisSig != prevSig) {
                        int yPos = (nScales - 1 - j) * S + S / 2;
                        g2.drawLine(t * S, yPos - S, t * S, yPos + S);
                    }
                }
            }
        }

        g2.dispose();

        // Add color bar
        BufferedImage withBar = addScalogramColorBar(colorImg, roiName, minPower, maxPower,
                wavelet.periods, nScales, wavelet.times);

        return new ImagePlus("Scalogram - " + roiName, withBar);
    }

    /**
     * Finds the nearest scale index for a given period value.
     */
    private static int findNearestScaleIndex(double[] periods, double targetPeriod) {
        int best = 0;
        double bestDist = Math.abs(periods[0] - targetPeriod);
        for (int i = 1; i < periods.length; i++) {
            double dist = Math.abs(periods[i] - targetPeriod);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /**
     * Adds axis labels and color bar to the scalogram.
     */
    private static BufferedImage addScalogramColorBar(BufferedImage src, String roiName,
                                                       float minPow, float maxPow,
                                                       double[] periods, int nScales,
                                                       double[] times) {
        int leftMargin = 60 * S;
        int bottomMargin = 40 * S;
        int topMargin = 30 * S;
        int rightMargin = 80 * S;
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int totalW = leftMargin + srcW + rightMargin;
        int totalH = topMargin + srcH + bottomMargin;

        BufferedImage result = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = result.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, totalW, totalH);

        // Draw scalogram image
        g2.drawImage(src, leftMargin, topMargin, null);

        // Title
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SansSerif", Font.BOLD, 13 * S));
        String title = "Scalogram - " + roiName;
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(title, leftMargin + (srcW - fm.stringWidth(title)) / 2, topMargin - 8 * S);

        // Y-axis labels (periods in hours)
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10 * S));
        fm = g2.getFontMetrics();
        if (periods != null && periods.length > 0) {
            double[] labelPeriods = {18, 20, 22, 24, 26, 28, 30};
            for (int i = 0; i < labelPeriods.length; i++) {
                double p = labelPeriods[i];
                if (p < periods[0] || p > periods[periods.length - 1]) continue;
                int scaleIdx = findNearestScaleIndex(periods, p);
                int yPos = topMargin + (nScales - 1 - scaleIdx) * S + S / 2;
                String label = String.format("%.0fh", p);
                g2.drawString(label, leftMargin - fm.stringWidth(label) - 4 * S,
                        yPos + fm.getAscent() / 2);
                g2.drawLine(leftMargin - 3 * S, yPos, leftMargin, yPos);
            }
        }

        // Y-axis title
        g2.setFont(new Font("SansSerif", Font.BOLD, 11 * S));
        Graphics2D g2r = (Graphics2D) g2.create();
        g2r.rotate(-Math.PI / 2);
        g2r.drawString("Period", -(topMargin + srcH / 2 + 15 * S), 14 * S);
        g2r.dispose();

        // X-axis labels at 24h intervals
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10 * S));
        fm = g2.getFontMetrics();
        if (times != null && times.length > 0) {
            double maxTime = times[times.length - 1];
            // Always label at 24h intervals
            for (double t = 0; t <= maxTime; t += 24) {
                double frac = t / maxTime;
                int xPos = leftMargin + (int) (frac * (srcW - 1));
                String label = String.format("%.0f", t);
                g2.drawString(label, xPos - fm.stringWidth(label) / 2,
                        topMargin + srcH + 14 * S);
                g2.drawLine(xPos, topMargin + srcH, xPos, topMargin + srcH + 3 * S);
            }
        }

        // X-axis title
        g2.setFont(new Font("SansSerif", Font.BOLD, 11 * S));
        fm = g2.getFontMetrics();
        String xTitle = "Time (hours)";
        g2.drawString(xTitle, leftMargin + (srcW - fm.stringWidth(xTitle)) / 2,
                topMargin + srcH + 32 * S);

        // Color bar on the right
        int barX = leftMargin + srcW + 10 * S;
        int barW = 15 * S;
        int barH = srcH;
        int barY = topMargin;

        LUT lut = KymographGenerator.createFireLUT();
        byte[] rLut = new byte[256];
        byte[] gLut = new byte[256];
        byte[] bLut = new byte[256];
        lut.getReds(rLut);
        lut.getGreens(gLut);
        lut.getBlues(bLut);

        for (int y = 0; y < barH; y++) {
            int idx = 255 - (y * 255 / barH);
            Color c = new Color(rLut[idx] & 0xFF, gLut[idx] & 0xFF, bLut[idx] & 0xFF);
            g2.setColor(c);
            g2.fillRect(barX, barY + y, barW, 1);
        }
        g2.setColor(Color.BLACK);
        g2.drawRect(barX, barY, barW, barH);

        // Color bar labels
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9 * S));
        fm = g2.getFontMetrics();
        g2.drawString(String.format("%.1f", maxPow), barX + barW + 3 * S, barY + fm.getAscent());
        g2.drawString(String.format("%.1f", minPow), barX + barW + 3 * S, barY + barH);
        g2.drawString("log10(P)", barX, barY - 4 * S);

        g2.dispose();
        return result;
    }
}
