package chronos.visualization;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.LUT;

/**
 * Creates space-time kymograph images.
 * X = time, Y = ROI index (spatial position), pixel = intensity.
 * Applies Fire LUT.
 */
public class KymographGenerator {

    private KymographGenerator() { }

    /**
     * Generates a kymograph from a corrected stack and ROI set.
     *
     * @param correctedStack  the corrected image stack
     * @param rois            ROIs ordered spatially (e.g., dorsal to ventral)
     * @param frameIntervalMin frame interval in minutes
     * @return kymograph ImagePlus with Fire LUT
     */
    public static ImagePlus generate(ImagePlus correctedStack, Roi[] rois,
                                      double frameIntervalMin) {
        if (correctedStack == null || rois == null || rois.length == 0) {
            return null;
        }

        int nRois = rois.length;
        int nFrames = correctedStack.getStackSize();

        // Extract mean intensity per ROI per frame
        FloatProcessor fp = new FloatProcessor(nFrames, nRois);

        for (int t = 0; t < nFrames; t++) {
            ImageProcessor ip = correctedStack.getStack().getProcessor(t + 1);
            for (int r = 0; r < nRois; r++) {
                ip.setRoi(rois[r]);
                ImageStatistics stats = ImageStatistics.getStatistics(ip,
                        ImageStatistics.MEAN, null);
                fp.setf(t, r, (float) stats.mean);
            }
        }

        ImagePlus kymo = new ImagePlus("Kymograph", fp);

        // Apply Fire LUT
        kymo.setLut(createFireLUT());

        // Set calibration
        Calibration cal = kymo.getCalibration();
        cal.pixelWidth = frameIntervalMin / 60.0; // hours per pixel
        cal.pixelHeight = 1.0; // ROI index
        cal.setXUnit("hours");
        cal.setYUnit("ROI");
        kymo.setCalibration(cal);

        // Auto-adjust contrast
        fp.resetMinAndMax();
        kymo.resetDisplayRange();

        return kymo;
    }

    /**
     * Generates a kymograph from pre-extracted trace data.
     *
     * @param traces          dF/F traces [nRois][nFrames]
     * @param roiNames        ROI names
     * @param frameIntervalMin frame interval in minutes
     * @return kymograph ImagePlus with Fire LUT
     */
    public static ImagePlus generateFromTraces(double[][] traces, String[] roiNames,
                                                double frameIntervalMin) {
        if (traces == null || traces.length == 0) return null;

        int nRois = traces.length;
        int nFrames = traces[0].length;

        FloatProcessor fp = new FloatProcessor(nFrames, nRois);
        for (int r = 0; r < nRois; r++) {
            for (int t = 0; t < nFrames; t++) {
                fp.setf(t, r, (float) traces[r][t]);
            }
        }

        ImagePlus kymo = new ImagePlus("Kymograph", fp);
        kymo.setLut(createFireLUT());

        Calibration cal = kymo.getCalibration();
        cal.pixelWidth = frameIntervalMin / 60.0;
        cal.pixelHeight = 1.0;
        cal.setXUnit("hours");
        cal.setYUnit("ROI");
        kymo.setCalibration(cal);

        fp.resetMinAndMax();
        kymo.resetDisplayRange();

        return kymo;
    }

    /**
     * Creates the Fire LUT (red-yellow-white gradient).
     */
    static LUT createFireLUT() {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];

        // Fire LUT: black -> blue -> red -> yellow -> white
        for (int i = 0; i < 256; i++) {
            if (i < 64) {
                r[i] = 0;
                g[i] = 0;
                b[i] = (byte) (i * 4);
            } else if (i < 128) {
                r[i] = (byte) ((i - 64) * 4);
                g[i] = 0;
                b[i] = (byte) (255 - (i - 64) * 4);
            } else if (i < 192) {
                r[i] = (byte) 255;
                g[i] = (byte) ((i - 128) * 4);
                b[i] = 0;
            } else {
                r[i] = (byte) 255;
                g[i] = (byte) 255;
                b[i] = (byte) ((i - 192) * 4);
            }
        }

        return new LUT(r, g, b);
    }
}
