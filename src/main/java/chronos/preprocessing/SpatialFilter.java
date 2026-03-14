package chronos.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.RankFilters;
import ij.process.ImageProcessor;

/**
 * Spatial filtering wrappers for time-lapse stacks.
 * Applies Gaussian blur or median filter to each frame independently.
 */
public class SpatialFilter {

    /**
     * Apply Gaussian blur to each frame of a stack.
     *
     * @param imp   input stack
     * @param sigma Gaussian sigma in pixels
     * @return filtered stack (new ImagePlus)
     */
    public static ImagePlus gaussian(ImagePlus imp, double sigma) {
        if (sigma <= 0) {
            return imp;
        }

        ImagePlus result = imp.duplicate();
        ImageStack stack = result.getStack();
        int nSlices = stack.getSize();

        GaussianBlur gb = new GaussianBlur();

        for (int i = 1; i <= nSlices; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            gb.blurGaussian(ip, sigma, sigma, 0.01);
            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        result.setTitle(imp.getTitle() + "_gaussian");
        return result;
    }

    /**
     * Apply median filter to each frame of a stack.
     *
     * @param imp    input stack
     * @param radius median filter radius in pixels
     * @return filtered stack (new ImagePlus)
     */
    public static ImagePlus median(ImagePlus imp, double radius) {
        if (radius <= 0) {
            return imp;
        }

        ImagePlus result = imp.duplicate();
        ImageStack stack = result.getStack();
        int nSlices = stack.getSize();

        RankFilters rf = new RankFilters();

        for (int i = 1; i <= nSlices; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            rf.rank(ip, radius, RankFilters.MEDIAN);
            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        result.setTitle(imp.getTitle() + "_median");
        return result;
    }
}
