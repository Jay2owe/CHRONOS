package chronos.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.ZProjector;
import ij.plugin.filter.BackgroundSubtracter;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/**
 * Background correction methods for time-lapse stacks.
 * Provides rolling ball subtraction, fixed ROI subtraction,
 * and minimum projection subtraction.
 */
public class BackgroundCorrector {

    /**
     * Apply rolling ball background subtraction to each frame.
     *
     * @param imp    input stack
     * @param radius rolling ball radius in pixels
     * @return corrected stack (new ImagePlus)
     */
    public static ImagePlus rollingBall(ImagePlus imp, double radius) {
        ImagePlus result = imp.duplicate();
        ImageStack stack = result.getStack();
        int nSlices = stack.getSize();

        BackgroundSubtracter bs = new BackgroundSubtracter();

        for (int i = 1; i <= nSlices; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            // rollingBallBackground(ip, radius, createBackground, lightBackground,
            //   useParaboloid, doPreSmooth, correctCorners)
            bs.rollingBallBackground(ip, radius, false, false, false, true, true);
            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        result.setTitle(imp.getTitle() + "_bgCorr");
        return result;
    }

    /**
     * Subtract the mean intensity within a fixed background ROI from each frame.
     *
     * @param imp   input stack
     * @param bgRoi ROI defining the background region
     * @return corrected stack (new ImagePlus)
     */
    public static ImagePlus fixedRoi(ImagePlus imp, Roi bgRoi) {
        ImagePlus result = imp.duplicate();
        ImageStack stack = result.getStack();
        int nSlices = stack.getSize();

        for (int i = 1; i <= nSlices; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            ip.setRoi(bgRoi);
            ImageStatistics stats = ImageStatistics.getStatistics(ip, ImageStatistics.MEAN, null);
            double bgMean = stats.mean;
            ip.resetRoi();

            // Subtract background mean from every pixel
            float[] pixels;
            if (ip instanceof ij.process.FloatProcessor) {
                pixels = (float[]) ip.getPixels();
                for (int j = 0; j < pixels.length; j++) {
                    pixels[j] = (float) (pixels[j] - bgMean);
                    if (pixels[j] < 0) pixels[j] = 0;
                }
            } else {
                // For 16-bit or 8-bit, subtract and clamp
                int pixelCount = ip.getWidth() * ip.getHeight();
                for (int j = 0; j < pixelCount; j++) {
                    float val = ip.getf(j) - (float) bgMean;
                    ip.setf(j, Math.max(0, val));
                }
            }

            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        result.setTitle(imp.getTitle() + "_bgCorr");
        return result;
    }

    /**
     * Subtract the minimum intensity projection from each frame.
     * This removes stable background features present in all frames.
     *
     * @param imp input stack
     * @return corrected stack (new ImagePlus)
     */
    public static ImagePlus minimumProjection(ImagePlus imp) {
        // Compute minimum projection
        ZProjector projector = new ZProjector(imp);
        projector.setMethod(ZProjector.MIN_METHOD);
        projector.doProjection();
        ImageProcessor minProj = projector.getProjection().getProcessor();

        ImagePlus result = imp.duplicate();
        ImageStack stack = result.getStack();
        int nSlices = stack.getSize();
        int pixelCount = imp.getWidth() * imp.getHeight();

        for (int i = 1; i <= nSlices; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            for (int j = 0; j < pixelCount; j++) {
                float val = ip.getf(j) - minProj.getf(j);
                ip.setf(j, Math.max(0, val));
            }
            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        result.setTitle(imp.getTitle() + "_bgCorr");
        return result;
    }
}
