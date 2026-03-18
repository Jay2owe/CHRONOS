package chronos.visualization;

import chronos.rhythm.CosinorResult;

import ij.ImagePlus;
import ij.gui.Plot;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Creates ImageJ Plot windows showing dF/F traces with cosinor fit overlay.
 * Annotates with period, amplitude, phase, and R-squared.
 * For many ROIs, produces a tiled multi-panel image.
 */
public class TimeSeriesPlotter {

    /** Scale factor for high-DPI output (3x = ~300 DPI at typical print size). */
    private static final int DPI_SCALE = 3;

    private TimeSeriesPlotter() { }

    /**
     * Creates a single trace plot with cosinor fit overlay.
     *
     * @param time      time values in hours
     * @param trace     dF/F trace values
     * @param fit       cosinor fitted values (may be null)
     * @param roiName   name of the ROI
     * @param result    cosinor result for annotation (may be null)
     * @return ImagePlus of the rendered plot
     */
    public static ImagePlus plot(double[] time, double[] trace, double[] fit,
                                  String roiName, CosinorResult result) {
        int baseW = 600;
        int baseH = 300;
        int w = baseW * DPI_SCALE;
        int h = baseH * DPI_SCALE;

        Plot p = new Plot(roiName, "Time (hours)", "dF/F");
        p.setSize(w, h);
        p.setFont(new Font("SansSerif", Font.PLAIN, 12 * DPI_SCALE));
        p.setColor(Color.BLUE);
        p.setLineWidth(1 * DPI_SCALE);
        p.addPoints(time, trace, Plot.LINE);

        if (fit != null) {
            p.setColor(Color.RED);
            p.setLineWidth(2 * DPI_SCALE);
            p.addPoints(time, fit, Plot.LINE);
        }

        // Set x limits to snap to nearest 24h multiple
        double maxTime = time[time.length - 1];
        double xMax = Math.ceil(maxTime / 24.0) * 24.0;
        if (xMax < 24) xMax = 24;

        // Compute y range
        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (double v : trace) {
            if (v < yMin) yMin = v;
            if (v > yMax) yMax = v;
        }
        double yPad = (yMax - yMin) * 0.1;
        if (yPad == 0) yPad = 0.001;
        yMin -= yPad;
        yMax += yPad;

        p.setLimits(0, xMax, yMin, yMax);

        // Draw 24h grid lines
        p.setColor(new Color(200, 200, 200));
        p.setLineWidth(1 * DPI_SCALE);
        for (double x24 = 24; x24 < xMax; x24 += 24) {
            p.drawLine(x24, yMin, x24, yMax);
        }

        // Draw 24h tick labels explicitly
        p.setColor(Color.DARK_GRAY);
        p.setFont(new Font("SansSerif", Font.PLAIN, 11 * DPI_SCALE));
        for (double x24 = 0; x24 <= xMax; x24 += 24) {
            p.addLabel(x24 / xMax * 0.85 + 0.08, 0.98,
                    String.valueOf((int) x24));
        }

        // Annotation
        if (result != null) {
            String annotation = String.format(
                    "T=%.2fh  A=%.4f  Phase=%.1fh  R\u00B2=%.3f",
                    result.period, result.amplitude,
                    result.acrophaseHours, result.rSquared);
            p.setColor(Color.DARK_GRAY);
            p.setFont(new Font("SansSerif", Font.PLAIN, 11 * DPI_SCALE));
            p.addLabel(0.02, 0.06, annotation);
        }

        // Legend
        p.setColor(Color.BLUE);
        p.setFont(new Font("SansSerif", Font.PLAIN, 11 * DPI_SCALE));
        p.addLegend("dF/F trace\nCosinor fit");

        return p.getImagePlus();
    }

    /**
     * Creates a tiled multi-panel image of all ROI traces.
     *
     * @param time      time values in hours
     * @param traces    dF/F traces [nRois][nFrames]
     * @param fits      cosinor fits [nRois][nFrames] (may be null)
     * @param roiNames  ROI names
     * @param results   cosinor results per ROI (array entries may be null)
     * @return ImagePlus with tiled subplots
     */
    public static ImagePlus plotAll(double[] time, double[][] traces, double[][] fits,
                                     String[] roiNames, CosinorResult[] results) {
        int nRois = traces.length;
        if (nRois == 0) return null;

        // Each panel rendered at high DPI via plot()
        int cols = Math.min(nRois, 3);
        int rows = (nRois + cols - 1) / cols;

        // Get actual rendered size from first plot
        ImagePlus firstPlot = plot(time, traces[0],
                (fits != null && fits.length > 0) ? fits[0] : null,
                roiNames[0],
                (results != null && results.length > 0) ? results[0] : null);

        int plotW = firstPlot.getWidth();
        int plotH = firstPlot.getHeight();
        int totalW = cols * plotW;
        int totalH = rows * plotH;

        BufferedImage canvas = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = canvas.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, totalW, totalH);

        // Draw first plot (already rendered)
        g2.drawImage(firstPlot.getImage(), 0, 0, plotW, plotH, null);

        for (int r = 1; r < nRois; r++) {
            double[] fit = (fits != null && r < fits.length) ? fits[r] : null;
            CosinorResult res = (results != null && r < results.length) ? results[r] : null;
            ImagePlus singlePlot = plot(time, traces[r], fit, roiNames[r], res);

            if (singlePlot != null) {
                int col = r % cols;
                int row = r / cols;
                g2.drawImage(singlePlot.getImage(), col * plotW, row * plotH,
                        plotW, plotH, null);
            }
        }

        g2.dispose();
        return new ImagePlus("Time Series - All ROIs", canvas);
    }
}
