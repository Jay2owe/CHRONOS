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
        Plot p = new Plot(roiName, "Time (hours)", "dF/F");
        p.setSize(600, 300);
        p.setColor(Color.BLUE);
        p.setLineWidth(1);
        p.addPoints(time, trace, Plot.LINE);

        if (fit != null) {
            p.setColor(Color.RED);
            p.setLineWidth(2);
            p.addPoints(time, fit, Plot.LINE);
        }

        // Annotation
        if (result != null) {
            String annotation = String.format(
                    "T=%.2fh  A=%.4f  Phase=%.1fh  R2=%.3f",
                    result.period, result.amplitude,
                    result.acrophaseHours, result.rSquared);
            p.setColor(Color.DARK_GRAY);
            p.setFont(new Font("SansSerif", Font.PLAIN, 11));
            p.addLabel(0.02, 0.06, annotation);
        }

        // Legend
        p.setColor(Color.BLUE);
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

        int plotW = 500;
        int plotH = 200;
        int cols = Math.min(nRois, 3);
        int rows = (nRois + cols - 1) / cols;

        int totalW = cols * plotW;
        int totalH = rows * plotH;

        BufferedImage canvas = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = canvas.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, totalW, totalH);

        for (int r = 0; r < nRois; r++) {
            double[] fit = (fits != null && r < fits.length) ? fits[r] : null;
            CosinorResult res = (results != null && r < results.length) ? results[r] : null;
            ImagePlus singlePlot = plot(time, traces[r], fit, roiNames[r], res);

            if (singlePlot != null) {
                Image img = singlePlot.getImage();
                int col = r % cols;
                int row = r / cols;
                g2.drawImage(img, col * plotW, row * plotH, plotW, plotH, null);
            }
        }

        g2.dispose();
        return new ImagePlus("Time Series - All ROIs", canvas);
    }
}
