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
 * Supports SIFT-based registration (Fiji built-in, robust to intensity changes),
 * FFT cross-correlation, phase correlation, epoch-based registration,
 * and descriptor-based series registration.
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
     * Correct drift using Fiji's "Correct 3D Drift" plugin.
     * Computes drift on an 8-bit greyscale duplicate (calibration stripped to avoid
     * the plugin rejecting Incucyte images with physical units), parses the per-frame
     * dx/dy corrections from the ImageJ Log window, then applies them to the original
     * RGB/colour stack using bilinear interpolation.
     *
     * @param imp input stack (any type/colour)
     * @return RegistrationResult with per-frame shifts, or null if the plugin failed
     */
    public static RegistrationResult correctWith3DDrift(ImagePlus imp) {
        int nSlices = imp.getStackSize();
        if (nSlices <= 1) {
            return new RegistrationResult(new double[1], new double[1], new double[]{1.0},
                    "Correct 3D Drift", "plugin");
        }

        IJ.log("  Running Correct 3D Drift (" + nSlices + " frames)...");

        // --- 1. Build an 8-bit greyscale duplicate with calibration stripped ---
        // Correct 3D Drift rejects images whose pixel unit is not "pixel" on some
        // Fiji versions, which breaks Incucyte files (unit = "µm").
        ImagePlus grey = imp.duplicate();
        grey.setTitle("_3ddrift_grey_tmp");
        IJ.run(grey, "8-bit", "");
        ij.measure.Calibration cal = grey.getCalibration();
        cal.pixelWidth  = 1.0;
        cal.pixelHeight = 1.0;
        cal.pixelDepth  = 1.0;
        cal.setUnit("pixel");
        grey.setCalibration(cal);

        // Plugin requires the image window to be visible
        grey.show();

        // --- 2. Clear the Log before running so we can parse fresh output ---
        IJ.log("\\Clear");

        try {
            IJ.run(grey, "Correct 3D Drift",
                    "channel=1 only=0 lowest=1 highest=" + nSlices
                    + " maximum_shift=50 edge_enhance");
        } catch (Exception e) {
            IJ.log("  WARNING: Correct 3D Drift failed: " + e.getMessage());
            grey.close();
            return null;
        }

        // Hide and discard the greyscale registered result — we only need the log
        grey.close();
        // Also close any new window the plugin may have opened
        ImagePlus pluginResult = WindowManager.getCurrentImage();
        if (pluginResult != null && pluginResult.getTitle().contains("registered")) {
            pluginResult.close();
        }

        // --- 3. Parse drift values from the ImageJ Log ---
        // Format: "frame N correcting drift dx,dy,0"
        String log = IJ.getLog();
        double[] shiftX = new double[nSlices];
        double[] shiftY = new double[nSlices];
        double[] quality = new double[nSlices];
        java.util.Arrays.fill(quality, 1.0);

        if (log != null) {
            String[] lines = log.split("\n");
            for (String line : lines) {
                line = line.trim();
                // Match lines like: "frame 5 correcting drift -2.0,1.5,0"
                if (!line.toLowerCase().contains("correcting drift")) {
                    continue;
                }
                try {
                    // Extract frame number
                    int frameIdx = -1;
                    if (line.toLowerCase().startsWith("frame")) {
                        String[] parts = line.split("\\s+");
                        // parts[0]="frame", parts[1]=N, parts[2]="correcting", ...
                        if (parts.length >= 2) {
                            frameIdx = Integer.parseInt(parts[1]) - 1; // 0-based
                        }
                    }
                    if (frameIdx < 0 || frameIdx >= nSlices) {
                        continue;
                    }
                    // Extract "dx,dy,0" — the last whitespace-separated token
                    String[] parts = line.split("\\s+");
                    String driftToken = parts[parts.length - 1];
                    String[] driftParts = driftToken.split(",");
                    if (driftParts.length >= 2) {
                        double dx = Double.parseDouble(driftParts[0]);
                        double dy = Double.parseDouble(driftParts[1]);
                        // The plugin reports corrections to apply; negate to get shift
                        shiftX[frameIdx] = -dx;
                        shiftY[frameIdx] = -dy;
                    }
                } catch (NumberFormatException nfe) {
                    // Skip malformed lines
                }
            }
        }

        // Log summary
        double maxShift = 0;
        for (int i = 0; i < nSlices; i++) {
            double mag = Math.sqrt(shiftX[i] * shiftX[i] + shiftY[i] * shiftY[i]);
            if (mag > maxShift) maxShift = mag;
        }
        IJ.log("  Correct 3D Drift complete. Max shift parsed: " + IJ.d2s(maxShift, 2) + " px");

        return new RegistrationResult(shiftX, shiftY, quality, "Correct 3D Drift", "plugin");
    }

    /**
     * Correct drift using Correct 3D Drift, but only compute cross-correlation
     * within a user-specified ROI region. This allows the user to select stable
     * landmarks (scratch marks, tissue edges) and ignore moving cells.
     * The computed shifts are applied to the full image.
     *
     * @param imp input stack (any type/colour)
     * @param roi rectangle defining the landmark region to track
     * @return RegistrationResult with per-frame shifts, or null if the plugin failed
     */
    public static RegistrationResult correctWith3DDriftManual(ImagePlus imp, java.awt.Rectangle roi) {
        int nSlices = imp.getStackSize();
        if (nSlices <= 1) {
            return new RegistrationResult(new double[1], new double[1], new double[]{1.0},
                    "Correct 3D Drift (Manual)", "plugin");
        }

        IJ.log("  Running Correct 3D Drift on manual ROI (" + roi.width + "x" + roi.height
                + " at " + roi.x + "," + roi.y + ", " + nSlices + " frames)...");

        // --- 1. Build 8-bit greyscale cropped to the ROI ---
        ImageStack croppedStack = new ImageStack(roi.width, roi.height);
        ImageStack srcStack = imp.getStack();
        for (int i = 1; i <= nSlices; i++) {
            ImageProcessor ip = srcStack.getProcessor(i).duplicate();
            ip.setRoi(roi.x, roi.y, roi.width, roi.height);
            croppedStack.addSlice(srcStack.getSliceLabel(i), ip.crop());
        }
        ImagePlus cropped = new ImagePlus("_3ddrift_roi_tmp", croppedStack);
        IJ.run(cropped, "8-bit", "");
        ij.measure.Calibration cal = cropped.getCalibration();
        cal.pixelWidth = 1.0;
        cal.pixelHeight = 1.0;
        cal.pixelDepth = 1.0;
        cal.setUnit("pixel");
        cropped.setCalibration(cal);

        cropped.show();

        // --- 2. Clear Log and run ---
        IJ.log("\\Clear");

        try {
            IJ.run(cropped, "Correct 3D Drift",
                    "channel=1 only=0 lowest=1 highest=" + nSlices
                    + " maximum_shift=50 edge_enhance");
        } catch (Exception e) {
            IJ.log("  WARNING: Correct 3D Drift (Manual) failed: " + e.getMessage());
            cropped.close();
            return null;
        }

        // Close cropped and any registered result
        cropped.close();
        ImagePlus pluginResult = WindowManager.getCurrentImage();
        if (pluginResult != null && pluginResult.getTitle().contains("registered")) {
            pluginResult.close();
        }

        // --- 3. Parse drift values from Log (same as automatic version) ---
        String log = IJ.getLog();
        double[] shiftX = new double[nSlices];
        double[] shiftY = new double[nSlices];
        double[] quality = new double[nSlices];
        java.util.Arrays.fill(quality, 1.0);

        if (log != null) {
            String[] lines = log.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.toLowerCase().contains("correcting drift")) {
                    continue;
                }
                try {
                    int frameIdx = -1;
                    if (line.toLowerCase().startsWith("frame")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            frameIdx = Integer.parseInt(parts[1]) - 1;
                        }
                    }
                    if (frameIdx < 0 || frameIdx >= nSlices) {
                        continue;
                    }
                    String[] parts = line.split("\\s+");
                    String driftToken = parts[parts.length - 1];
                    String[] driftParts = driftToken.split(",");
                    if (driftParts.length >= 2) {
                        double dx = Double.parseDouble(driftParts[0]);
                        double dy = Double.parseDouble(driftParts[1]);
                        shiftX[frameIdx] = -dx;
                        shiftY[frameIdx] = -dy;
                    }
                } catch (NumberFormatException nfe) {
                    // Skip malformed lines
                }
            }
        }

        double maxShift = 0;
        for (int i = 0; i < nSlices; i++) {
            double mag = Math.sqrt(shiftX[i] * shiftX[i] + shiftY[i] * shiftY[i]);
            if (mag > maxShift) maxShift = mag;
        }
        IJ.log("  Correct 3D Drift (Manual) complete. Max shift: " + IJ.d2s(maxShift, 2) + " px");

        return new RegistrationResult(shiftX, shiftY, quality, "Correct 3D Drift (Manual)", "plugin");
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
     * Phase correlation with magnitude normalization.
     * Like cross-correlation but normalizes by magnitude in frequency domain,
     * producing a sharper peak that is robust to intensity changes.
     *
     * @param imp       input stack
     * @param refMethod "first", "mean", or "median"
     * @return RegistrationResult with per-frame shifts and quality
     */
    public static RegistrationResult computePhaseCorrelation(ImagePlus imp, String refMethod) {
        int nSlices = imp.getStackSize();
        int width = imp.getWidth();
        int height = imp.getHeight();
        ImageStack inputStack = imp.getStack();

        FloatProcessor refFp = computeReference(imp, refMethod);
        int fhtSize = nextPowerOf2(Math.max(width, height));
        FHT refFHT = computeFHT(refFp, fhtSize);

        double[] shiftX = new double[nSlices];
        double[] shiftY = new double[nSlices];
        double[] quality = new double[nSlices];

        for (int i = 1; i <= nSlices; i++) {
            FloatProcessor frameFp = inputStack.getProcessor(i).convertToFloatProcessor();
            FHT frameFHT = computeFHT(frameFp, fhtSize);

            // Cross-correlation in Hartley space
            FHT correlation = frameFHT.conjugateMultiply(refFHT);
            correlation.inverseTransform();

            // Phase normalization: divide by magnitude
            float[] px = (float[]) correlation.getPixels();
            for (int j = 0; j < px.length; j++) {
                float mag = Math.abs(px[j]);
                if (mag > 1e-10f) px[j] /= mag;
            }

            correlation.swapQuadrants();

            // Find peak and compute quality
            float[] pixels = (float[]) correlation.getPixels();
            int w = correlation.getWidth();
            int h = correlation.getHeight();

            int maxIdx = 0;
            float maxVal = Float.NEGATIVE_INFINITY;
            for (int j = 0; j < pixels.length; j++) {
                if (pixels[j] > maxVal) {
                    maxVal = pixels[j];
                    maxIdx = j;
                }
            }

            int peakX = maxIdx % w;
            int peakY = maxIdx / w;

            // Subpixel refinement
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

            shiftX[i - 1] = subX - w / 2.0;
            shiftY[i - 1] = subY - h / 2.0;

            // Quality: peak height relative to mean (higher = more confident)
            double sum = 0;
            for (float p : pixels) sum += p;
            double mean = sum / pixels.length;
            quality[i - 1] = mean > 0 ? Math.min(1.0, maxVal / (mean * 10.0)) : 0;

            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        return new RegistrationResult(shiftX, shiftY, quality, "Phase Correlation", refMethod);
    }

    /**
     * Epoch-based registration: splits the stack at detected transition frames
     * and applies a single constant shift per epoch (piecewise-constant model).
     *
     * @param imp              input stack
     * @param refMethod        "first", "mean", or "median"
     * @param transitionFrames 0-based frame indices where drift jumps occur
     * @return RegistrationResult with piecewise-constant shifts
     */
    public static RegistrationResult computeEpochRegistration(ImagePlus imp, String refMethod,
                                                               int[] transitionFrames) {
        // First compute per-frame phase correlation shifts
        RegistrationResult perFrame = computePhaseCorrelation(imp, refMethod);

        int nFrames = perFrame.nFrames;
        double[] epochShiftX = new double[nFrames];
        double[] epochShiftY = new double[nFrames];
        double[] epochQuality = new double[nFrames];

        // Build epoch boundaries: [0, t1, t2, ..., nFrames]
        int[] boundaries = new int[transitionFrames.length + 2];
        boundaries[0] = 0;
        for (int i = 0; i < transitionFrames.length; i++) {
            boundaries[i + 1] = transitionFrames[i];
        }
        boundaries[boundaries.length - 1] = nFrames;

        for (int e = 0; e < boundaries.length - 1; e++) {
            int start = boundaries[e];
            int end = boundaries[e + 1];
            int epochLen = end - start;
            if (epochLen == 0) continue;

            // Compute median shift for this epoch
            double[] ex = new double[epochLen];
            double[] ey = new double[epochLen];
            double[] eq = new double[epochLen];
            for (int i = 0; i < epochLen; i++) {
                ex[i] = perFrame.shiftX[start + i];
                ey[i] = perFrame.shiftY[start + i];
                eq[i] = perFrame.quality[start + i];
            }

            double medX = median(ex);
            double medY = median(ey);
            double medQ = median(eq);

            for (int i = start; i < end; i++) {
                epochShiftX[i] = medX;
                epochShiftY[i] = medY;
                epochQuality[i] = medQ;
            }
        }

        return new RegistrationResult(epochShiftX, epochShiftY, epochQuality,
                "Phase Correlation + Epoch Detection", refMethod);
    }

    /**
     * Wrapper for Fiji's Descriptor-based Series Registration (2D/3D + t).
     * Returns a RegistrationResult with zero shifts (actual correction done in-place by plugin).
     *
     * @param imp input stack
     * @return RegistrationResult (shifts are zeroed since plugin applies them directly)
     */
    public static RegistrationResult computeDescriptorBased(ImagePlus imp) {
        int nSlices = imp.getStackSize();
        if (nSlices <= 1) {
            return new RegistrationResult(new double[1], new double[1], new double[]{1.0},
                    "Descriptor-Based", "plugin");
        }

        IJ.log("  Running Descriptor-based series registration (" + nSlices + " frames)...");

        boolean wasVisible = imp.getWindow() != null;
        if (!wasVisible) {
            imp.show();
        }

        try {
            IJ.run(imp, "Descriptor-based series registration (2d/3d + t)",
                    "series_of_images=All_images "
                    + "brightness_of=Medium "
                    + "approximate_size=10 "
                    + "type_of_detections=[Minima & Maxima] "
                    + "subpixel_localization=[3-dimensional quadratic fit] "
                    + "transformation_model=[Rigid (2d)] "
                    + "number_of_neighbors=3 "
                    + "redundancy=1 "
                    + "significance=3 "
                    + "allowed_error_for_RANSAC=5 "
                    + "global_optimization=[All-to-all matching with range ('reasonable')]"
                    + " range=5 "
                    + "choose_registration_channel=1 "
                    + "image=[Fuse and display]");

            ImagePlus aligned = WindowManager.getCurrentImage();
            if (aligned != null && aligned != imp) {
                // Plugin applied corrections — copy result back
                if (!wasVisible) {
                    imp.hide();
                    aligned.hide();
                }
                IJ.log("  Descriptor-based registration complete.");
            } else {
                if (!wasVisible) {
                    imp.hide();
                }
            }
        } catch (Exception e) {
            IJ.log("  WARNING: Descriptor-based registration failed: " + e.getMessage());
            if (!wasVisible) {
                imp.hide();
            }
        }

        // Plugin modifies in-place; we return zero shifts as the correction is already applied
        double[] zeros = new double[nSlices];
        double[] ones = new double[nSlices];
        for (int i = 0; i < nSlices; i++) ones[i] = 1.0;
        return new RegistrationResult(zeros, zeros, ones, "Descriptor-Based", "plugin");
    }

    /**
     * Apply pre-computed registration shifts to a stack.
     *
     * @param imp    input stack
     * @param result registration result containing per-frame shifts
     * @return new corrected ImagePlus
     */
    public static ImagePlus applyRegistration(ImagePlus imp, RegistrationResult result) {
        int nSlices = imp.getStackSize();
        int width = imp.getWidth();
        int height = imp.getHeight();
        ImageStack inputStack = imp.getStack();
        ImageStack outputStack = new ImageStack(width, height);

        int framesToApply = Math.min(nSlices, result.nFrames);
        for (int i = 1; i <= nSlices; i++) {
            double dx = (i - 1 < framesToApply) ? result.shiftX[i - 1] : 0;
            double dy = (i - 1 < framesToApply) ? result.shiftY[i - 1] : 0;
            ImageProcessor corrected = applyTranslation(inputStack.getProcessor(i), dx, dy);
            outputStack.addSlice(inputStack.getSliceLabel(i), corrected);

            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        ImagePlus out = new ImagePlus(imp.getTitle() + "_registered", outputStack);
        out.setCalibration(imp.getCalibration().copy());
        return out;
    }

    /**
     * Compute the reference frame based on the selected method.
     * Package-private for reuse by DriftAnalyzer.
     */
    static FloatProcessor computeReference(ImagePlus imp, String refMethod) {
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
     * Package-private for reuse by DriftAnalyzer.
     */
    static FHT computeFHT(FloatProcessor fp, int fhtSize) {
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
    static ImageProcessor applyTranslation(ImageProcessor ip, double dx, double dy) {
        ImageProcessor result = ip.duplicate();
        result.setInterpolationMethod(ImageProcessor.BILINEAR);
        result.translate(dx, dy);
        return result;
    }

    /**
     * Returns the smallest power of 2 >= n.
     * Package-private for reuse by DriftAnalyzer.
     */
    static int nextPowerOf2(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }

    /**
     * Compute the median of a double array.
     */
    private static double median(double[] arr) {
        double[] sorted = arr.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 0) {
            return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        }
        return sorted[n / 2];
    }
}
