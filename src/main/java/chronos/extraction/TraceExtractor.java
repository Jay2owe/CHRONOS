package chronos.extraction;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/**
 * Extracts mean intensity traces from an ImagePlus stack for each ROI.
 */
public class TraceExtractor {

    /**
     * Extracts mean intensity per ROI per frame from a stack.
     *
     * @param imp  the image stack (must have nSlices >= 1)
     * @param rois the ROIs to measure
     * @return double[nRois][nFrames] of mean intensities
     */
    public static double[][] extractTraces(ImagePlus imp, Roi[] rois) {
        int nFrames = imp.getStackSize();
        int nRois = rois.length;
        double[][] traces = new double[nRois][nFrames];

        for (int f = 0; f < nFrames; f++) {
            // Get a copy of the processor for this frame (1-based index)
            ImageProcessor ip = imp.getStack().getProcessor(f + 1).duplicate();
            for (int r = 0; r < nRois; r++) {
                ip.setRoi(rois[r]);
                ImageStatistics stats = ImageStatistics.getStatistics(ip,
                        ImageStatistics.MEAN, imp.getCalibration());
                traces[r][f] = stats.mean;
            }
        }

        return traces;
    }
}
