package chronos.visualization;

import chronos.rhythm.CosinorResult;
import chronos.rhythm.RayleighResult;
import chronos.rhythm.RhythmResult;

import ij.ImagePlus;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates a 2x2 multi-panel summary figure.
 * <p>
 * Top-left:     Mean dF/F trace + cosinor fit
 * Top-right:    Polar phase plot
 * Bottom-left:  Period histogram
 * Bottom-right: Summary text panel
 */
public class SummaryDashboard {

    private static final int S = 3; // DPI scale factor
    private static final int PANEL_W = 400 * S;
    private static final int PANEL_H = 350 * S;
    private static final int TOTAL_W = PANEL_W * 2;
    private static final int TOTAL_H = PANEL_H * 2;
    private static final Color BG_COLOR = Color.WHITE;

    private SummaryDashboard() { }

    /**
     * Generates the summary dashboard.
     *
     * @param time      time values in hours
     * @param traces    dF/F traces [nRois][nFrames]
     * @param results   rhythm results per ROI
     * @param rayleigh  Rayleigh test result (may be null)
     * @return ImagePlus with the 2x2 dashboard
     */
    public static ImagePlus generate(double[] time, double[][] traces,
                                      List<RhythmResult> results,
                                      RayleighResult rayleigh) {
        if (results == null || results.isEmpty()) return null;

        BufferedImage canvas = new BufferedImage(TOTAL_W, TOTAL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = canvas.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(BG_COLOR);
        g2.fillRect(0, 0, TOTAL_W, TOTAL_H);

        // Separate rhythmic ROIs
        List<RhythmResult> rhythmic = new ArrayList<RhythmResult>();
        for (RhythmResult rr : results) {
            if (rr.isRhythmic) rhythmic.add(rr);
        }

        // Top-left: Mean trace + fit
        drawMeanTrace(g2, 0, 0, time, traces, results);

        // Top-right: Polar plot
        drawPolarPlot(g2, PANEL_W, 0, results, rayleigh);

        // Bottom-left: Period histogram
        drawPeriodHistogram(g2, 0, PANEL_H, rhythmic);

        // Bottom-right: Summary text
        drawSummaryText(g2, PANEL_W, PANEL_H, results, rhythmic, rayleigh);

        // Panel borders
        g2.setColor(Color.LIGHT_GRAY);
        g2.setStroke(new BasicStroke(1f * S));
        g2.drawLine(PANEL_W, 0, PANEL_W, TOTAL_H);
        g2.drawLine(0, PANEL_H, TOTAL_W, PANEL_H);

        g2.dispose();
        return new ImagePlus("Summary Dashboard", canvas);
    }

    /**
     * Draws the mean dF/F trace with cosinor fit overlay.
     */
    private static void drawMeanTrace(Graphics2D g2, int ox, int oy,
                                       double[] time, double[][] traces,
                                       List<RhythmResult> results) {
        int plotL = ox + 55 * S;
        int plotT = oy + 35 * S;
        int plotW = PANEL_W - 75 * S;
        int plotH = PANEL_H - 60 * S;

        // Compute mean trace across rhythmic ROIs
        int nFrames = (time != null) ? time.length : 0;
        if (nFrames == 0 || traces == null) return;

        double[] meanTrace = new double[nFrames];
        double[] meanFit = new double[nFrames];
        int countTrace = 0;
        int countFit = 0;

        for (int r = 0; r < results.size(); r++) {
            RhythmResult rr = results.get(r);
            if (!rr.isRhythmic) continue;
            if (r < traces.length) {
                for (int t = 0; t < nFrames && t < traces[r].length; t++) {
                    if (!Double.isNaN(traces[r][t])) {
                        meanTrace[t] += traces[r][t];
                    }
                }
                countTrace++;
            }
            if (rr.cosinorResult != null && rr.cosinorResult.fittedValues != null) {
                double[] fit = rr.cosinorResult.fittedValues;
                for (int t = 0; t < nFrames && t < fit.length; t++) {
                    meanFit[t] += fit[t];
                }
                countFit++;
            }
        }

        // If no rhythmic, use all
        if (countTrace == 0) {
            for (int r = 0; r < traces.length; r++) {
                for (int t = 0; t < nFrames && t < traces[r].length; t++) {
                    if (!Double.isNaN(traces[r][t])) {
                        meanTrace[t] += traces[r][t];
                    }
                }
                countTrace++;
            }
        }

        if (countTrace > 0) {
            for (int t = 0; t < nFrames; t++) meanTrace[t] /= countTrace;
        }
        if (countFit > 0) {
            for (int t = 0; t < nFrames; t++) meanFit[t] /= countFit;
        }

        // Find data range
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int t = 0; t < nFrames; t++) {
            if (meanTrace[t] < minY) minY = meanTrace[t];
            if (meanTrace[t] > maxY) maxY = meanTrace[t];
        }
        double rangeY = maxY - minY;
        if (rangeY <= 0) rangeY = 1;
        double maxTime = time[nFrames - 1];
        double xMax = Math.ceil(maxTime / 24.0) * 24.0;
        if (xMax < 24) xMax = 24;

        // Title
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SansSerif", Font.BOLD, 12 * S));
        g2.drawString("Mean dF/F Trace + Cosinor Fit", plotL, oy + 20 * S);

        // Axes
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1f * S));
        g2.drawLine(plotL, plotT, plotL, plotT + plotH);
        g2.drawLine(plotL, plotT + plotH, plotL + plotW, plotT + plotH);

        // 24h grid lines
        g2.setColor(new Color(220, 220, 220));
        g2.setStroke(new BasicStroke(0.5f * S));
        for (double x24 = 24; x24 < xMax; x24 += 24) {
            int xPos = plotL + (int) (x24 / xMax * plotW);
            g2.drawLine(xPos, plotT, xPos, plotT + plotH);
        }

        // Axis labels
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SansSerif", Font.BOLD, 10 * S));
        FontMetrics fmBold = g2.getFontMetrics();
        g2.drawString("Time (h)", plotL + plotW / 2 - fmBold.stringWidth("Time (h)") / 2,
                plotT + plotH + 22 * S);
        Graphics2D g2r = (Graphics2D) g2.create();
        g2r.rotate(-Math.PI / 2);
        g2r.drawString("dF/F", -(plotT + plotH / 2 + fmBold.stringWidth("dF/F") / 2), ox + 14 * S);
        g2r.dispose();

        // X tick labels at 24h intervals
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10 * S));
        FontMetrics fm = g2.getFontMetrics();
        for (double t24 = 0; t24 <= xMax; t24 += 24) {
            int x = plotL + (int) (t24 / xMax * plotW);
            g2.drawLine(x, plotT + plotH, x, plotT + plotH + 3 * S);
            String lbl = String.format("%.0f", t24);
            g2.drawString(lbl, x - fm.stringWidth(lbl) / 2, plotT + plotH + 14 * S);
        }

        // Draw mean trace
        g2.setColor(Color.BLUE);
        g2.setStroke(new BasicStroke(1.5f * S));
        int[] xPts = new int[nFrames];
        int[] yPts = new int[nFrames];
        for (int t = 0; t < nFrames; t++) {
            xPts[t] = plotL + (int) (time[t] / xMax * plotW);
            yPts[t] = plotT + plotH - (int) ((meanTrace[t] - minY) / rangeY * plotH);
        }
        g2.drawPolyline(xPts, yPts, nFrames);

        // Draw mean fit
        if (countFit > 0) {
            g2.setColor(Color.RED);
            g2.setStroke(new BasicStroke(2f * S));
            for (int t = 0; t < nFrames; t++) {
                yPts[t] = plotT + plotH - (int) ((meanFit[t] - minY) / rangeY * plotH);
            }
            g2.drawPolyline(xPts, yPts, nFrames);
        }

        // Legend
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10 * S));
        int legLineLen = 20 * S;
        g2.setStroke(new BasicStroke(1.5f * S));
        g2.setColor(Color.BLUE);
        g2.drawLine(plotL + plotW - 80 * S, plotT + 10 * S,
                plotL + plotW - 80 * S + legLineLen, plotT + 10 * S);
        g2.drawString("Mean dF/F", plotL + plotW - 56 * S, plotT + 14 * S);
        g2.setColor(Color.RED);
        g2.drawLine(plotL + plotW - 80 * S, plotT + 24 * S,
                plotL + plotW - 80 * S + legLineLen, plotT + 24 * S);
        g2.drawString("Cosinor", plotL + plotW - 56 * S, plotT + 28 * S);
    }

    /**
     * Draws a polar phase plot in the specified panel.
     */
    private static void drawPolarPlot(Graphics2D g2, int ox, int oy,
                                       List<RhythmResult> results,
                                       RayleighResult rayleigh) {
        // Collect phases
        List<Double> phases = new ArrayList<Double>();
        for (RhythmResult rr : results) {
            if (rr.isRhythmic && !Double.isNaN(rr.phaseRad)) {
                phases.add(rr.phaseRad);
            }
        }
        double[] phasesArr = new double[phases.size()];
        for (int i = 0; i < phases.size(); i++) phasesArr[i] = phases.get(i);

        // Generate polar plot at panel size (PolarPlotGenerator applies its own 3x)
        // Use base size that will fill the panel after scaling
        int baseSize = PANEL_W / S;
        ImagePlus polarImg = PolarPlotGenerator.generate(phasesArr, rayleigh, baseSize);
        if (polarImg != null) {
            Image img = polarImg.getImage();
            g2.drawImage(img, ox, oy, PANEL_W, PANEL_H, null);
        }
    }

    /**
     * Draws a period histogram bar chart.
     */
    private static void drawPeriodHistogram(Graphics2D g2, int ox, int oy,
                                             List<RhythmResult> rhythmic) {
        int plotL = ox + 55 * S;
        int plotT = oy + 35 * S;
        int plotW = PANEL_W - 75 * S;
        int plotH = PANEL_H - 65 * S;

        // Title
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SansSerif", Font.BOLD, 12 * S));
        g2.drawString("Period Distribution", plotL, oy + 20 * S);

        if (rhythmic.isEmpty()) {
            g2.setFont(new Font("SansSerif", Font.ITALIC, 11 * S));
            g2.drawString("No rhythmic ROIs", plotL + 40 * S, plotT + plotH / 2);
            return;
        }

        // Find period range
        double minP = Double.MAX_VALUE;
        double maxP = -Double.MAX_VALUE;
        for (RhythmResult rr : rhythmic) {
            if (rr.period < minP) minP = rr.period;
            if (rr.period > maxP) maxP = rr.period;
        }

        // Create histogram bins
        int nBins = Math.min(15, Math.max(5, rhythmic.size() / 2));
        double binWidth = (maxP - minP) / nBins;
        if (binWidth <= 0) { binWidth = 1; nBins = 1; }
        int[] counts = new int[nBins];

        for (RhythmResult rr : rhythmic) {
            int bin = (int) ((rr.period - minP) / binWidth);
            if (bin >= nBins) bin = nBins - 1;
            if (bin < 0) bin = 0;
            counts[bin]++;
        }

        int maxCount = 0;
        for (int c : counts) {
            if (c > maxCount) maxCount = c;
        }
        if (maxCount == 0) maxCount = 1;

        // Axes
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1f * S));
        g2.drawLine(plotL, plotT, plotL, plotT + plotH);
        g2.drawLine(plotL, plotT + plotH, plotL + plotW, plotT + plotH);

        // Draw bars
        int barW = plotW / nBins;
        g2.setColor(new Color(70, 130, 180));
        for (int i = 0; i < nBins; i++) {
            int barH = (int) ((double) counts[i] / maxCount * plotH);
            int bx = plotL + i * barW;
            int by = plotT + plotH - barH;
            g2.fillRect(bx + S, by, barW - 2 * S, barH);
            g2.setColor(new Color(50, 100, 150));
            g2.drawRect(bx + S, by, barW - 2 * S, barH);
            g2.setColor(new Color(70, 130, 180));
        }

        // X-axis labels
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9 * S));
        FontMetrics fm = g2.getFontMetrics();
        for (int i = 0; i <= nBins; i += Math.max(1, nBins / 5)) {
            double val = minP + i * binWidth;
            String lbl = String.format("%.1f", val);
            int x = plotL + i * barW;
            g2.drawString(lbl, x - fm.stringWidth(lbl) / 2, plotT + plotH + 14 * S);
        }

        g2.setFont(new Font("SansSerif", Font.BOLD, 10 * S));
        FontMetrics fmBold = g2.getFontMetrics();
        String xLabel = "Period (h)";
        g2.drawString(xLabel, plotL + plotW / 2 - fmBold.stringWidth(xLabel) / 2,
                plotT + plotH + 28 * S);

        // Y-axis label
        Graphics2D g2r = (Graphics2D) g2.create();
        g2r.rotate(-Math.PI / 2);
        g2r.drawString("Count", -(plotT + plotH / 2 + fmBold.stringWidth("Count") / 2), ox + 14 * S);
        g2r.dispose();
    }

    /**
     * Draws the summary text panel.
     */
    private static void drawSummaryText(Graphics2D g2, int ox, int oy,
                                         List<RhythmResult> allResults,
                                         List<RhythmResult> rhythmic,
                                         RayleighResult rayleigh) {
        int tx = ox + 30 * S;
        int ty = oy + 40 * S;
        int lineH = 22 * S;

        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SansSerif", Font.BOLD, 13 * S));
        g2.drawString("Summary Statistics", tx, ty);
        ty += lineH + 5 * S;

        g2.setFont(new Font("SansSerif", Font.PLAIN, 12 * S));

        int nTotal = allResults.size();
        int nRhythmic = rhythmic.size();
        double pctRhythmic = (nTotal > 0) ? 100.0 * nRhythmic / nTotal : 0;

        g2.drawString(String.format("ROIs analyzed: %d", nTotal), tx, ty); ty += lineH;
        g2.drawString(String.format("Rhythmic: %d / %d (%.1f%%)", nRhythmic, nTotal, pctRhythmic),
                tx, ty); ty += lineH;

        if (!rhythmic.isEmpty()) {
            // Period stats
            double sumP = 0, sumP2 = 0;
            for (RhythmResult rr : rhythmic) {
                sumP += rr.period;
                sumP2 += rr.period * rr.period;
            }
            double meanP = sumP / nRhythmic;
            double semP = Math.sqrt((sumP2 / nRhythmic - meanP * meanP) / nRhythmic);
            g2.drawString(String.format("Mean period: %.2f \u00B1 %.2f h", meanP, semP), tx, ty);
            ty += lineH;

            // Amplitude stats
            double sumA = 0, sumA2 = 0;
            for (RhythmResult rr : rhythmic) {
                sumA += rr.amplitude;
                sumA2 += rr.amplitude * rr.amplitude;
            }
            double meanA = sumA / nRhythmic;
            double semA = Math.sqrt((sumA2 / nRhythmic - meanA * meanA) / nRhythmic);
            g2.drawString(String.format("Mean amplitude: %.4f \u00B1 %.4f", meanA, semA), tx, ty);
            ty += lineH;

            // R-squared stats
            double sumR2 = 0;
            for (RhythmResult rr : rhythmic) {
                sumR2 += rr.rSquared;
            }
            double meanR2 = sumR2 / nRhythmic;
            g2.drawString(String.format("Mean R\u00B2: %.3f", meanR2), tx, ty);
            ty += lineH;
        }

        ty += 5 * S;

        if (rayleigh != null) {
            g2.setFont(new Font("SansSerif", Font.BOLD, 12 * S));
            g2.drawString("Rayleigh Test", tx, ty); ty += lineH;
            g2.setFont(new Font("SansSerif", Font.PLAIN, 12 * S));
            g2.drawString(String.format("R = %.3f", rayleigh.vectorLength), tx, ty); ty += lineH;
            g2.drawString(String.format("p = %.4f", rayleigh.pValue), tx, ty); ty += lineH;
            g2.drawString(String.format("Mean direction = %.1fh", rayleigh.meanDirectionHours),
                    tx, ty); ty += lineH;

            // Significance
            g2.setFont(new Font("SansSerif", Font.BOLD, 12 * S));
            if (rayleigh.pValue < 0.001) {
                g2.setColor(new Color(0, 128, 0));
                g2.drawString("Highly significant (p < 0.001)", tx, ty);
            } else if (rayleigh.pValue < 0.05) {
                g2.setColor(new Color(0, 128, 0));
                g2.drawString("Significant (p < 0.05)", tx, ty);
            } else {
                g2.setColor(new Color(180, 0, 0));
                g2.drawString("Not significant (p \u2265 0.05)", tx, ty);
            }
        }
    }
}
