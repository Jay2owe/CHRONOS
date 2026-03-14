package chronos.preprocessing;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.plugin.GroupedZProjector;
import ij.plugin.ZProjector;

/**
 * Temporal frame binning using GroupedZProjector.
 * Reduces the number of frames by averaging or summing groups of consecutive frames.
 */
public class FrameBinner {

    /**
     * Bins frames of a stack by the given factor.
     *
     * @param imp       input stack
     * @param factor    number of frames per bin (1 = no-op)
     * @param useMean   true for mean projection, false for sum projection
     * @return binned stack (new ImagePlus), or the original if factor <= 1
     */
    public static ImagePlus bin(ImagePlus imp, int factor, boolean useMean) {
        if (factor <= 1) {
            return imp;
        }

        int nSlices = imp.getStackSize();
        if (nSlices < factor) {
            return imp;
        }

        int method = useMean ? ZProjector.AVG_METHOD : ZProjector.SUM_METHOD;

        GroupedZProjector gzp = new GroupedZProjector();
        // GroupedZProjector works on the current image — we set it and run
        // Use the static approach: project groups manually
        ImageStack inputStack = imp.getStack();
        int nBins = nSlices / factor;
        ImageStack outputStack = new ImageStack(imp.getWidth(), imp.getHeight());

        for (int bin = 0; bin < nBins; bin++) {
            int startSlice = bin * factor + 1; // 1-based
            int endSlice = startSlice + factor - 1;

            // Create a sub-stack for this bin
            ImagePlus subImp = new ImagePlus("bin_" + bin, createSubStack(inputStack, startSlice, endSlice));
            ZProjector projector = new ZProjector(subImp);
            projector.setMethod(method);
            projector.doProjection();
            ImagePlus projected = projector.getProjection();
            outputStack.addSlice("Frame " + (bin + 1), projected.getProcessor());
        }

        ImagePlus result = new ImagePlus(imp.getTitle() + "_binned", outputStack);
        result.setCalibration(imp.getCalibration().copy());
        return result;
    }

    private static ImageStack createSubStack(ImageStack source, int startSlice, int endSlice) {
        ImageStack sub = new ImageStack(source.getWidth(), source.getHeight());
        for (int i = startSlice; i <= endSlice; i++) {
            sub.addSlice(source.getSliceLabel(i), source.getProcessor(i).duplicate());
        }
        return sub;
    }
}
