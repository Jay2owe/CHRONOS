package chronos.visualization;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.LUT;
import ij.measure.Calibration;

/**
 * Creates raster plot heatmaps: rows = ROIs sorted by phase, columns = time.
 * Each row is normalized to [0, 1] range. Fire LUT applied.
 */
public class RasterPlotGenerator {

    private RasterPlotGenerator() { }

    /**
     * Generates a raster plot heatmap.
     *
     * @param traces          dF/F traces [nRois][nFrames]
     * @param phases          phase values in hours for sorting (length = nRois)
     * @param roiNames        ROI names (length = nRois)
     * @param frameIntervalMin frame interval in minutes
     * @return ImagePlus with raster plot
     */
    public static ImagePlus generate(double[][] traces, double[] phases,
                                      String[] roiNames, double frameIntervalMin) {
        if (traces == null || traces.length == 0) return null;

        int nRois = traces.length;
        int nFrames = traces[0].length;

        // Create sort indices by phase (ascending)
        int[] sortIdx = sortIndicesByPhase(phases, nRois);

        // Build normalized image
        FloatProcessor fp = new FloatProcessor(nFrames, nRois);

        for (int row = 0; row < nRois; row++) {
            int roiIdx = sortIdx[row];
            double[] trace = traces[roiIdx];

            // Find min and max for normalization
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (int t = 0; t < nFrames; t++) {
                double v = trace[t];
                if (!Double.isNaN(v)) {
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            double range = max - min;
            if (range <= 0) range = 1.0;

            // Set normalized values
            for (int t = 0; t < nFrames; t++) {
                double v = trace[t];
                if (Double.isNaN(v)) {
                    fp.setf(t, row, 0);
                } else {
                    fp.setf(t, row, (float) ((v - min) / range));
                }
            }
        }

        ImagePlus raster = new ImagePlus("Raster Plot", fp);
        raster.setLut(KymographGenerator.createFireLUT());

        // Set calibration
        Calibration cal = raster.getCalibration();
        cal.pixelWidth = frameIntervalMin / 60.0;
        cal.pixelHeight = 1.0;
        cal.setXUnit("hours");
        cal.setYUnit("ROI (sorted by phase)");
        raster.setCalibration(cal);

        fp.setMinAndMax(0, 1);
        raster.updateAndDraw();

        return raster;
    }

    /**
     * Returns indices sorted by phase value (ascending, early phase at top).
     * NaN phases are placed at the end.
     */
    private static int[] sortIndicesByPhase(double[] phases, int n) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;

        // Simple insertion sort (small n expected)
        for (int i = 1; i < n; i++) {
            int key = idx[i];
            double keyPhase = phases[key];
            int j = i - 1;
            while (j >= 0 && comparePhase(phases[idx[j]], keyPhase) > 0) {
                idx[j + 1] = idx[j];
                j--;
            }
            idx[j + 1] = key;
        }
        return idx;
    }

    /**
     * Compares two phase values. NaN is treated as larger than any value.
     */
    private static int comparePhase(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) return 0;
        if (Double.isNaN(a)) return 1;
        if (Double.isNaN(b)) return -1;
        return Double.compare(a, b);
    }
}
