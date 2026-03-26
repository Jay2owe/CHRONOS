package chronos.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Menus;
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
                    + ": " + e.getMessage() + "). Returning input unchanged.");
            if (!wasVisible) {
                imp.hide();
            }
            return imp;
        }
    }

    /**
     * Correct drift using Fiji's "Correct 3D Drift" plugin.
     * Extracts the blue channel (stable tissue texture, no fluorescence) for
     * registration, parses per-frame dx/dy corrections from the ImageJ Log,
     * then the caller applies them to the original RGB/colour stack.
     *
     * The blue channel is used because moving fluorescent cells (e.g. GFP microglia)
     * in the green channel would confuse cross-correlation-based registration.
     * Blue typically contains only background tissue texture in GFP experiments.
     *
     * @param imp input stack (any type/colour)
     * @return RegistrationResult with per-frame shifts, or null if the plugin failed
     */
    public static RegistrationResult correctWith3DDrift(ImagePlus imp) {
        return correctWith3DDrift(imp, 1);
    }

    /**
     * Correct drift using Fiji's "Correct 3D Drift" plugin.
     * Extracts the blue channel (stable tissue texture, no fluorescence) for
     * registration, parses per-frame dx/dy corrections from the ImageJ Log,
     * then the caller applies them to the original RGB/colour stack.
     *
     * Always uses multi_time_scale, sub_pixel, and edge_enhance for best results.
     *
     * @param imp input stack (any type/colour)
     * @param downsampleFactor spatial downsample factor (1 = no downsampling)
     * @return RegistrationResult with per-frame shifts (in full-resolution pixels), or null if failed
     */
    public static RegistrationResult correctWith3DDrift(ImagePlus imp, int downsampleFactor) {
        // Use getNFrames for hyperstacks, fall back to getStackSize for plain stacks
        int nFrames = imp.getNFrames() > 1 ? imp.getNFrames() : imp.getStackSize();
        if (nFrames <= 1) {
            return new RegistrationResult(new double[1], new double[1], new double[]{1.0},
                    "Correct 3D Drift", "plugin");
        }

        if (downsampleFactor < 1) downsampleFactor = 1;
        boolean doDownsample = downsampleFactor > 1;

        IJ.log("  Running Correct 3D Drift (" + nFrames + " frames, blue channel registration"
                + (doDownsample ? ", " + downsampleFactor + "x downsample" : "") + ")...");

        // --- 1. Extract the blue channel for registration ---
        // extractBlueChannel returns a single-channel stack with nFrames slices
        ImagePlus regChannel = extractBlueChannel(imp);
        regChannel.setTitle("_3ddrift_blue_tmp");

        // --- 1b. Downsample if requested ---
        if (doDownsample) {
            int dsW = regChannel.getWidth() / downsampleFactor;
            int dsH = regChannel.getHeight() / downsampleFactor;
            IJ.log("    Downsampling from " + regChannel.getWidth() + "x" + regChannel.getHeight()
                    + " to " + dsW + "x" + dsH + " (" + downsampleFactor + "x)");
            ImageStack dsStack = new ImageStack(dsW, dsH);
            ImageStack srcStack = regChannel.getStack();
            for (int i = 1; i <= srcStack.getSize(); i++) {
                ImageProcessor ds = srcStack.getProcessor(i).resize(dsW, dsH);
                dsStack.addSlice(srcStack.getSliceLabel(i), ds);
            }
            regChannel.close();
            regChannel = new ImagePlus("_3ddrift_blue_ds_tmp", dsStack);
        }

        // Strip calibration — Correct 3D Drift rejects images with physical units
        ij.measure.Calibration cal = regChannel.getCalibration();
        cal.pixelWidth  = 1.0;
        cal.pixelHeight = 1.0;
        cal.pixelDepth  = 1.0;
        cal.setUnit("pixel");
        regChannel.setCalibration(cal);

        // Set as hyperstack: C=1, Z=1, T=nFrames
        // Plugin uses getNSlices() for Z-range validation and getNFrames() for time
        int regFrames = regChannel.getStackSize();
        regChannel.setDimensions(1, 1, regFrames);
        regChannel.setOpenAsHyperStack(true);

        // Plugin requires the image window to be visible
        regChannel.show();

        // --- 2. Clear the Log before running so we can parse fresh output ---
        IJ.log("\\Clear");

        try {
            String cmd = findCorrect3DDriftCommand();
            IJ.log("  Using command: '" + cmd + "'");
            // highest=1 because Z=1 (2D time-lapse); the plugin iterates over T automatically
            IJ.run(regChannel, cmd,
                    "channel=1 multi_time_scale sub_pixel edge_enhance"
                    + " only_consider=0"
                    + " lowest=1 highest=1"
                    + " max_shift_x=" + (doDownsample ? 50 / downsampleFactor + 10 : 50)
                    + " max_shift_y=" + (doDownsample ? 50 / downsampleFactor + 10 : 50)
                    + " max_shift_z=10");
        } catch (Exception e) {
            IJ.log("  WARNING: Correct 3D Drift failed: " + e.getMessage());
            regChannel.close();
            return null;
        }

        // Hide and discard the blue channel registered result — we only need the log
        regChannel.close();
        ImagePlus pluginResult = WindowManager.getCurrentImage();
        if (pluginResult != null && pluginResult.getTitle().contains("registered")) {
            pluginResult.close();
        }

        // --- 3. Parse drift values from the ImageJ Log ---
        RegistrationResult result = parseDriftLog(nFrames, downsampleFactor);
        return result;
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
        return correctWith3DDriftManual(imp, roi, 1);
    }

    /**
     * Correct drift using Correct 3D Drift, but only compute cross-correlation
     * within a user-specified ROI region. This allows the user to select stable
     * landmarks (scratch marks, tissue edges) and ignore moving cells.
     *
     * Always uses multi_time_scale, sub_pixel, and edge_enhance for best results.
     *
     * @param imp input stack (any type/colour)
     * @param roi rectangle defining the landmark region to track
     * @param downsampleFactor spatial downsample factor (1 = no downsampling)
     * @return RegistrationResult with per-frame shifts (in full-resolution pixels), or null if failed
     */
    public static RegistrationResult correctWith3DDriftManual(ImagePlus imp, java.awt.Rectangle roi,
                                                               int downsampleFactor) {
        int nFrames = imp.getNFrames() > 1 ? imp.getNFrames() : imp.getStackSize();
        if (nFrames <= 1) {
            return new RegistrationResult(new double[1], new double[1], new double[]{1.0},
                    "Correct 3D Drift (Manual)", "plugin");
        }

        if (downsampleFactor < 1) downsampleFactor = 1;
        boolean doDownsample = downsampleFactor > 1;

        IJ.log("  Running Correct 3D Drift on manual ROI (" + roi.width + "x" + roi.height
                + " at " + roi.x + "," + roi.y + ", " + nFrames + " frames"
                + (doDownsample ? ", " + downsampleFactor + "x downsample" : "") + ")...");

        // --- 1. Extract blue channel cropped to the ROI ---
        // extractBlueChannel returns a single-channel stack with nFrames slices
        ImagePlus blueStack = extractBlueChannel(imp);
        ImageStack croppedStack = new ImageStack(roi.width, roi.height);
        ImageStack srcBlue = blueStack.getStack();
        int blueSlices = srcBlue.getSize();
        for (int i = 1; i <= blueSlices; i++) {
            ImageProcessor ip = srcBlue.getProcessor(i).duplicate();
            ip.setRoi(roi.x, roi.y, roi.width, roi.height);
            croppedStack.addSlice(srcBlue.getSliceLabel(i), ip.crop());
        }
        blueStack.close();

        // --- 1b. Downsample if requested ---
        ImageStack finalStack;
        if (doDownsample) {
            int dsW = roi.width / downsampleFactor;
            int dsH = roi.height / downsampleFactor;
            IJ.log("    Downsampling ROI from " + roi.width + "x" + roi.height
                    + " to " + dsW + "x" + dsH + " (" + downsampleFactor + "x)");
            finalStack = new ImageStack(dsW, dsH);
            for (int i = 1; i <= croppedStack.getSize(); i++) {
                ImageProcessor ds = croppedStack.getProcessor(i).resize(dsW, dsH);
                finalStack.addSlice(croppedStack.getSliceLabel(i), ds);
            }
        } else {
            finalStack = croppedStack;
        }

        // Set as hyperstack: C=1, Z=1, T=nFrames
        int regFrames = finalStack.getSize();
        ImagePlus cropped = new ImagePlus("_3ddrift_roi_tmp", finalStack);
        cropped.setDimensions(1, 1, regFrames);
        cropped.setOpenAsHyperStack(true);
        ij.measure.Calibration cal = cropped.getCalibration();
        cal.pixelWidth  = 1.0;
        cal.pixelHeight = 1.0;
        cal.pixelDepth  = 1.0;
        cal.setUnit("pixel");
        cropped.setCalibration(cal);

        cropped.show();

        // --- 2. Clear Log and run ---
        IJ.log("\\Clear");

        try {
            String cmd = findCorrect3DDriftCommand();
            IJ.log("  Using command: '" + cmd + "'");
            // highest=1 because Z=1 (2D time-lapse)
            IJ.run(cropped, cmd,
                    "channel=1 multi_time_scale sub_pixel edge_enhance"
                    + " only_consider=0"
                    + " lowest=1 highest=1"
                    + " max_shift_x=" + (doDownsample ? 50 / downsampleFactor + 10 : 50)
                    + " max_shift_y=" + (doDownsample ? 50 / downsampleFactor + 10 : 50)
                    + " max_shift_z=10");
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

        // --- 3. Parse drift values from Log, scaling up if downsampled ---
        RegistrationResult result = parseDriftLog(nFrames, downsampleFactor);
        if (result != null) {
            return new RegistrationResult(result.shiftX, result.shiftY, result.quality,
                    "Correct 3D Drift (Manual)", "plugin");
        }
        return null;
    }

    /**
     * Parse drift correction values from the ImageJ Log after running Correct 3D Drift.
     * The plugin logs lines like: "frame N correcting drift dx,dy,dz"
     *
     * @param nSlices          number of frames in the original (full-res) stack
     * @param downsampleFactor factor to scale shifts back to full resolution (1 = no scaling)
     * @return RegistrationResult with per-frame shifts in full-resolution pixels
     */
    private static RegistrationResult parseDriftLog(int nSlices, int downsampleFactor) {
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
                            frameIdx = Integer.parseInt(parts[1]) - 1; // 0-based
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
                        // The plugin reports corrections to apply; negate to get shift
                        // Scale up by downsample factor to get full-resolution shifts
                        shiftX[frameIdx] = -dx * downsampleFactor;
                        shiftY[frameIdx] = -dy * downsampleFactor;
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
        IJ.log("  Correct 3D Drift complete. Max shift: " + IJ.d2s(maxShift, 2) + " px"
                + (downsampleFactor > 1 ? " (scaled " + downsampleFactor + "x from downsampled)" : ""));

        return new RegistrationResult(shiftX, shiftY, quality, "Correct 3D Drift", "plugin");
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

    /**
     * Extract the blue channel from an RGB stack as an 8-bit greyscale stack.
     * For non-RGB images (already greyscale or composite), returns an 8-bit duplicate.
     *
     * @param imp input stack (RGB or other)
     * @return 8-bit stack of the blue channel (or greyscale duplicate for non-RGB)
     */
    private static ImagePlus extractBlueChannel(ImagePlus imp) {
        ImageStack src = imp.getStack();
        int w = imp.getWidth();
        int h = imp.getHeight();

        if (imp.getType() == ImagePlus.COLOR_RGB) {
            // Extract blue channel (index 2) from each RGB slice
            int nSlices = src.getSize();
            ImageStack blueStack = new ImageStack(w, h);
            for (int i = 1; i <= nSlices; i++) {
                int[] pixels = (int[]) src.getProcessor(i).getPixels();
                byte[] blue = new byte[pixels.length];
                for (int j = 0; j < pixels.length; j++) {
                    blue[j] = (byte) (pixels[j] & 0xFF); // blue = lowest 8 bits
                }
                blueStack.addSlice(src.getSliceLabel(i),
                        new ij.process.ByteProcessor(w, h, blue, null));
            }
            return new ImagePlus("blue_channel", blueStack);
        } else if (imp.getNChannels() > 1) {
            // Composite hyperstack: extract the last channel (typically blue/stable texture)
            // For 3-channel images: ch3 is blue. For 2-channel: ch2.
            int blueChannel = imp.getNChannels(); // last channel = blue
            int nFrames = imp.getNFrames();
            int nZ = imp.getNSlices(); // actual Z slices
            ImageStack blueStack = new ImageStack(w, h);
            for (int t = 1; t <= nFrames; t++) {
                for (int z = 1; z <= nZ; z++) {
                    int idx = imp.getStackIndex(blueChannel, z, t);
                    ImageProcessor ip = src.getProcessor(idx).duplicate();
                    blueStack.addSlice(src.getSliceLabel(idx), ip);
                }
            }
            ImagePlus result = new ImagePlus("blue_channel", blueStack);
            IJ.run(result, "8-bit", "");
            return result;
        } else {
            // Single-channel greyscale: duplicate and convert to 8-bit
            ImagePlus dup = imp.duplicate();
            IJ.run(dup, "8-bit", "");
            return dup;
        }
    }

    /**
     * Find the exact registered command name for "Correct 3D Drift" by
     * case-insensitive search through Fiji's command table. Different Fiji
     * versions register it as "Correct 3D Drift" or "Correct 3D drift".
     *
     * @return the exact command string, or "Correct 3D drift" as fallback
     */
    static String findCorrect3DDriftCommand() {
        java.util.Hashtable commands = Menus.getCommands();
        if (commands != null) {
            for (Object key : commands.keySet()) {
                String name = key.toString();
                if (name.equalsIgnoreCase("Correct 3D Drift")
                        || name.equalsIgnoreCase("Correct 3D drift")) {
                    IJ.log("  Found Correct 3D Drift command: '" + name + "'");
                    return name;
                }
            }
            // Broader search — match anything containing "correct" and "drift"
            for (Object key : commands.keySet()) {
                String lower = key.toString().toLowerCase();
                if (lower.contains("correct") && lower.contains("drift") && lower.contains("3d")) {
                    IJ.log("  Found Correct 3D Drift command (broad match): '" + key + "'");
                    return key.toString();
                }
            }
            IJ.log("  WARNING: Correct 3D Drift not found in " + commands.size() + " commands. Using fallback name.");
        } else {
            IJ.log("  WARNING: Menus.getCommands() returned null");
        }
        return "Correct 3D drift"; // most common registration
    }
}
