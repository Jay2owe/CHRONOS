package chronos.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.plugin.ZProjector;
import ij.process.FHT;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * Motion correction for time-lapse stacks.
 * Supports SIFT-based registration (Fiji built-in, robust to intensity changes)
 * and FFT cross-correlation (simpler, translation-only).
 */
public class MotionCorrector {

    /**
     * Correct drift using SIFT feature-based alignment.
     * Uses Fiji's built-in "Linear Stack Alignment with SIFT" plugin.
     * Handles rotation and is robust to intensity changes from circadian rhythms.
     *
     * @param imp input stack
     * @return motion-corrected stack (new ImagePlus, or same if SIFT unavailable)
     */
    public static ImagePlus correctWithSIFT(ImagePlus imp) {
        int nSlices = imp.getStackSize();
        if (nSlices <= 1) {
            return imp;
        }

        IJ.log("  Running SIFT alignment (" + nSlices + " frames)...");

        // SIFT requires the image to be visible
        boolean wasVisible = imp.getWindow() != null;
        if (!wasVisible) {
            imp.show();
        }

        try {
            // Run Fiji's Linear Stack Alignment with SIFT
            // Rigid transformation = translation + rotation (best for organotypic slices)
            IJ.run(imp, "Linear Stack Alignment with SIFT",
                    "initial_gaussian_blur=1.60 "
                    + "steps_per_scale_octave=3 "
                    + "minimum_image_size=64 "
                    + "maximum_image_size=1024 "
                    + "feature_descriptor_size=4 "
                    + "feature_descriptor_orientation_bins=8 "
                    + "closest/next_closest_ratio=0.92 "
                    + "maximal_alignment_error=25 "
                    + "inlier_ratio=0.05 "
                    + "expected_transformation=Rigid "
                    + "interpolate");

            // SIFT creates a new window with the aligned result
            ImagePlus aligned = ij.WindowManager.getCurrentImage();
            if (aligned != null && aligned != imp) {
                aligned.setTitle(imp.getTitle() + "_registered");
                aligned.setCalibration(imp.getCalibration().copy());

                // Hide the aligned image if we're running headless-style
                if (!wasVisible) {
                    imp.hide();
                    aligned.hide();
                }

                IJ.log("  SIFT alignment complete.");
                return aligned;
            } else {
                IJ.log("  WARNING: SIFT alignment did not produce output. Using original.");
                if (!wasVisible) {
                    imp.hide();
                }
                return imp;
            }
        } catch (Exception e) {
            IJ.log("  WARNING: SIFT alignment failed (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + "). Falling back to cross-correlation.");
            if (!wasVisible) {
                imp.hide();
            }
            return correct(imp, "mean");
        }
    }

    /**
     * Correct translational drift using FFT cross-correlation.
     *
     * @param imp       input stack
     * @param refMethod "first", "mean", or "median" — how to compute reference frame
     * @return motion-corrected stack (new ImagePlus)
     */
    public static ImagePlus correct(ImagePlus imp, String refMethod) {
        int nSlices = imp.getStackSize();
        if (nSlices <= 1) {
            return imp;
        }

        int width = imp.getWidth();
        int height = imp.getHeight();
        ImageStack inputStack = imp.getStack();

        // Compute reference frame
        FloatProcessor refFp = computeReference(imp, refMethod);

        // Pad to power-of-2 square for FHT
        int fhtSize = nextPowerOf2(Math.max(width, height));

        // Compute FHT of reference (padded)
        FHT refFHT = computeFHT(refFp, fhtSize);

        // Correct each frame
        ImageStack outputStack = new ImageStack(width, height);
        double[] shiftX = new double[nSlices];
        double[] shiftY = new double[nSlices];

        for (int i = 1; i <= nSlices; i++) {
            FloatProcessor frameFp = inputStack.getProcessor(i).convertToFloatProcessor();

            // Compute cross-correlation via FFT
            double[] shift = computeShift(frameFp, refFHT, fhtSize);
            shiftX[i - 1] = shift[0];
            shiftY[i - 1] = shift[1];

            // Apply translation with bilinear interpolation
            ImageProcessor corrected = applyTranslation(inputStack.getProcessor(i), shift[0], shift[1]);
            outputStack.addSlice(inputStack.getSliceLabel(i), corrected);

            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        // Log summary of shifts
        double maxShift = 0;
        for (int i = 0; i < nSlices; i++) {
            double mag = Math.sqrt(shiftX[i] * shiftX[i] + shiftY[i] * shiftY[i]);
            if (mag > maxShift) maxShift = mag;
        }
        IJ.log("  Motion correction: max shift = " + IJ.d2s(maxShift, 2) + " px");

        ImagePlus result = new ImagePlus(imp.getTitle() + "_registered", outputStack);
        result.setCalibration(imp.getCalibration().copy());
        return result;
    }

    /**
     * Compute the reference frame based on the selected method.
     */
    private static FloatProcessor computeReference(ImagePlus imp, String refMethod) {
        if ("first".equalsIgnoreCase(refMethod)) {
            return imp.getStack().getProcessor(1).convertToFloatProcessor();
        }

        int projMethod;
        if ("median".equalsIgnoreCase(refMethod)) {
            projMethod = ZProjector.MEDIAN_METHOD;
        } else {
            projMethod = ZProjector.AVG_METHOD;
        }

        ZProjector projector = new ZProjector(imp);
        projector.setMethod(projMethod);
        projector.doProjection();
        return projector.getProjection().getProcessor().convertToFloatProcessor();
    }

    /**
     * Compute the translational shift between a frame and the reference
     * using FFT-based cross-correlation.
     *
     * @return [dx, dy] shift to apply to align the frame to the reference
     */
    private static double[] computeShift(FloatProcessor frameFp, FHT refFHT, int fhtSize) {
        // Compute FHT of frame
        FHT frameFHT = computeFHT(frameFp, fhtSize);

        // Cross-correlation = IFFT( FFT(frame) * conj(FFT(ref)) )
        // FHT.conjugateMultiply computes this in Hartley space
        FHT correlation = frameFHT.conjugateMultiply(refFHT);
        correlation.inverseTransform();
        correlation.swapQuadrants();

        // Find peak in correlation image
        float[] pixels = (float[]) correlation.getPixels();
        int w = correlation.getWidth();
        int h = correlation.getHeight();

        int maxIdx = 0;
        float maxVal = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] > maxVal) {
                maxVal = pixels[i];
                maxIdx = i;
            }
        }

        int peakX = maxIdx % w;
        int peakY = maxIdx / w;

        // Subpixel refinement using parabolic interpolation
        double subX = peakX;
        double subY = peakY;

        if (peakX > 0 && peakX < w - 1) {
            float left = pixels[peakY * w + (peakX - 1)];
            float center = pixels[peakY * w + peakX];
            float right = pixels[peakY * w + (peakX + 1)];
            double denom = 2.0 * (2.0 * center - left - right);
            if (Math.abs(denom) > 1e-10) {
                subX = peakX + (left - right) / denom;
            }
        }

        if (peakY > 0 && peakY < h - 1) {
            float top = pixels[(peakY - 1) * w + peakX];
            float center = pixels[peakY * w + peakX];
            float bottom = pixels[(peakY + 1) * w + peakX];
            double denom = 2.0 * (2.0 * center - top - bottom);
            if (Math.abs(denom) > 1e-10) {
                subY = peakY + (top - bottom) / denom;
            }
        }

        // Convert peak position to shift (relative to center)
        double dx = subX - w / 2.0;
        double dy = subY - h / 2.0;

        return new double[]{dx, dy};
    }

    /**
     * Pad a processor to the given square size and compute its FHT.
     */
    private static FHT computeFHT(FloatProcessor fp, int fhtSize) {
        FloatProcessor padded = new FloatProcessor(fhtSize, fhtSize);
        // Center the image in the padded frame
        int offsetX = (fhtSize - fp.getWidth()) / 2;
        int offsetY = (fhtSize - fp.getHeight()) / 2;
        padded.insert(fp, offsetX, offsetY);

        FHT fht = new FHT(padded);
        fht.transform();
        return fht;
    }

    /**
     * Apply a translational shift to an image processor using bilinear interpolation.
     */
    private static ImageProcessor applyTranslation(ImageProcessor ip, double dx, double dy) {
        ImageProcessor result = ip.duplicate();
        result.setInterpolationMethod(ImageProcessor.BILINEAR);
        result.translate(dx, dy);
        return result;
    }

    /**
     * Returns the smallest power of 2 >= n.
     */
    private static int nextPowerOf2(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }
}
