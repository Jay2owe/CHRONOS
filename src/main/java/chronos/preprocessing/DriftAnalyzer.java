package chronos.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ZProjector;
import ij.process.FHT;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fast automatic drift characterization for time-lapse stacks.
 * Downsamples 4x, phase-correlates each frame against a median reference,
 * classifies the drift pattern, and recommends a registration method.
 */
public class DriftAnalyzer {

    private static final int DOWNSAMPLE_FACTOR = 4;
    private static final double JUMP_THRESHOLD_PX = 2.0;  // pixels at full resolution

    /**
     * Analyze drift in a time-lapse stack.
     *
     * @param imp              input stack
     * @param frameIntervalMin frame interval in minutes (for logging)
     * @return DriftAnalysisResult with pattern classification and recommendation
     */
    public static DriftAnalysisResult analyze(ImagePlus imp, double frameIntervalMin) {
        int nSlices = imp.getStackSize();
        if (nSlices <= 1) {
            return new DriftAnalysisResult("sparse",
                    new double[]{0}, new double[]{0}, new double[]{1.0},
                    new int[0], false, "Phase Correlation",
                    "Single frame — no correction needed");
        }

        IJ.log("  Drift analysis: scanning " + nSlices + " frames...");
        long startTime = System.currentTimeMillis();

        int width = imp.getWidth();
        int height = imp.getHeight();
        ImageStack inputStack = imp.getStack();

        // Step 1: Downsample each frame 4x
        int dsW = width / DOWNSAMPLE_FACTOR;
        int dsH = height / DOWNSAMPLE_FACTOR;
        ImageStack dsStack = new ImageStack(dsW, dsH);
        for (int i = 1; i <= nSlices; i++) {
            ImageProcessor ip = inputStack.getProcessor(i);
            ImageProcessor ds = ip.resize(dsW, dsH);
            dsStack.addSlice(ds);
        }
        ImagePlus dsImp = new ImagePlus("downsampled", dsStack);

        // Step 2: Compute median projection as reference
        ZProjector projector = new ZProjector(dsImp);
        projector.setMethod(ZProjector.MEDIAN_METHOD);
        projector.doProjection();
        FloatProcessor refFp = projector.getProjection().getProcessor().convertToFloatProcessor();

        // Step 3: Phase correlate each downsampled frame vs reference
        int fhtSize = MotionCorrector.nextPowerOf2(Math.max(dsW, dsH));
        FHT refFHT = MotionCorrector.computeFHT(refFp, fhtSize);

        double[] dx = new double[nSlices];
        double[] dy = new double[nSlices];
        double[] correlationQuality = new double[nSlices];

        for (int i = 1; i <= nSlices; i++) {
            FloatProcessor frameFp = dsStack.getProcessor(i).convertToFloatProcessor();
            FHT frameFHT = MotionCorrector.computeFHT(frameFp, fhtSize);

            FHT correlation = frameFHT.conjugateMultiply(refFHT);
            correlation.inverseTransform();

            // Phase normalization
            float[] px = (float[]) correlation.getPixels();
            for (int j = 0; j < px.length; j++) {
                float mag = Math.abs(px[j]);
                if (mag > 1e-10f) px[j] /= mag;
            }

            correlation.swapQuadrants();

            // Find peak
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

            // Scale back to full resolution
            dx[i - 1] = (subX - w / 2.0) * DOWNSAMPLE_FACTOR;
            dy[i - 1] = (subY - h / 2.0) * DOWNSAMPLE_FACTOR;

            // Quality: peak height as correlation quality
            double sum = 0;
            for (float p : pixels) sum += p;
            double mean = sum / pixels.length;
            correlationQuality[i - 1] = mean > 0 ? Math.min(1.0, maxVal / (mean * 10.0)) : 0;
        }

        dsImp.close();

        // Step 5: Classify drift pattern from frame-to-frame deltas
        double[] deltaDx = new double[nSlices - 1];
        double[] deltaDy = new double[nSlices - 1];
        double[] deltaMag = new double[nSlices - 1];
        for (int i = 0; i < nSlices - 1; i++) {
            deltaDx[i] = dx[i + 1] - dx[i];
            deltaDy[i] = dy[i + 1] - dy[i];
            deltaMag[i] = Math.sqrt(deltaDx[i] * deltaDx[i] + deltaDy[i] * deltaDy[i]);
        }

        // Count jump frames where delta > threshold
        int jumpCount = 0;
        for (double d : deltaMag) {
            if (d > JUMP_THRESHOLD_PX) jumpCount++;
        }

        // Compute cumulative drift
        double totalDriftX = 0, totalDriftY = 0;
        for (int i = 0; i < nSlices; i++) {
            totalDriftX = Math.max(totalDriftX, Math.abs(dx[i]));
            totalDriftY = Math.max(totalDriftY, Math.abs(dy[i]));
        }
        double maxDrift = Math.sqrt(totalDriftX * totalDriftX + totalDriftY * totalDriftY);

        // Frame-to-frame variance
        double meanDelta = 0;
        for (double d : deltaMag) meanDelta += d;
        meanDelta /= deltaMag.length;
        double varDelta = 0;
        for (double d : deltaMag) varDelta += (d - meanDelta) * (d - meanDelta);
        varDelta /= deltaMag.length;

        // Classify
        String driftPattern;
        if (jumpCount <= 5 && varDelta < 4.0) {
            driftPattern = "sparse";
        } else if (varDelta < 4.0 && maxDrift > 5.0) {
            driftPattern = "continuous";
        } else {
            driftPattern = "chaotic";
        }

        // Step 6: Detect transition frames via MAD-based outlier detection
        int[] transitionFrames;
        if ("sparse".equals(driftPattern) && deltaMag.length > 0) {
            transitionFrames = detectTransitions(deltaMag);
        } else {
            transitionFrames = new int[0];
        }

        // Step 7: Rotation check — correlate left and right halves independently
        boolean rotationLikely = checkRotation(dsStack, refFp, fhtSize, dsW, dsH);

        // Step 8: Generate recommendation
        String recommendedMethod;
        String reason;

        if (maxDrift < 1.0) {
            recommendedMethod = "Phase Correlation";
            reason = "Negligible drift (<1px) — lightweight phase correlation sufficient";
        } else if (rotationLikely) {
            recommendedMethod = "SIFT";
            reason = "Rotation detected — need feature-based alignment";
        } else if ("sparse".equals(driftPattern) && transitionFrames.length > 0) {
            recommendedMethod = "Phase Correlation + Epoch Detection";
            reason = "Sparse drift with " + transitionFrames.length + " transition(s) — epoch model is ideal";
        } else if ("continuous".equals(driftPattern)) {
            recommendedMethod = "Phase Correlation";
            reason = "Continuous drift — per-frame phase correlation";
        } else if ("chaotic".equals(driftPattern)) {
            recommendedMethod = "SIFT";
            reason = "Chaotic drift pattern — robust feature matching needed";
        } else {
            recommendedMethod = "Phase Correlation";
            reason = "Default — phase correlation is fast and sufficient";
        }

        long elapsed = System.currentTimeMillis() - startTime;
        IJ.log("  Drift analysis complete in " + elapsed + "ms");
        IJ.log("    Pattern: " + driftPattern + ", max drift: " + IJ.d2s(maxDrift, 1) + "px"
                + ", jumps: " + jumpCount + ", transitions: " + transitionFrames.length);
        IJ.log("    Rotation likely: " + rotationLikely);
        IJ.log("    Recommendation: " + recommendedMethod + " (" + reason + ")");

        return new DriftAnalysisResult(driftPattern, dx, dy, correlationQuality,
                transitionFrames, rotationLikely, recommendedMethod, reason);
    }

    /**
     * Detect transition frames using MAD-based outlier detection on frame-to-frame deltas.
     */
    private static int[] detectTransitions(double[] deltaMag) {
        // Compute median of deltas
        double[] sorted = deltaMag.clone();
        Arrays.sort(sorted);
        double medianDelta = sorted[sorted.length / 2];

        // Compute MAD (Median Absolute Deviation)
        double[] absDevs = new double[deltaMag.length];
        for (int i = 0; i < deltaMag.length; i++) {
            absDevs[i] = Math.abs(deltaMag[i] - medianDelta);
        }
        Arrays.sort(absDevs);
        double mad = absDevs[absDevs.length / 2];
        if (mad < 0.01) mad = 0.01; // avoid zero MAD

        // Threshold: 3.5 * MAD above median (robust Z-score)
        double threshold = medianDelta + 3.5 * 1.4826 * mad;
        threshold = Math.max(threshold, JUMP_THRESHOLD_PX);

        List<Integer> transitions = new ArrayList<Integer>();
        for (int i = 0; i < deltaMag.length; i++) {
            if (deltaMag[i] > threshold) {
                transitions.add(i + 1); // transition is at the destination frame
            }
        }

        int[] result = new int[transitions.size()];
        for (int i = 0; i < transitions.size(); i++) {
            result[i] = transitions.get(i);
        }
        return result;
    }

    /**
     * Check for rotation by correlating left and right halves independently.
     * If their shifts differ significantly, rotation is likely.
     */
    private static boolean checkRotation(ImageStack dsStack, FloatProcessor refFp,
                                         int fhtSize, int dsW, int dsH) {
        int halfW = dsW / 2;
        if (halfW < 16 || dsH < 16) return false;

        // Sample a few frames (every 10th or so) to keep this fast
        int nSlices = dsStack.getSize();
        int step = Math.max(1, nSlices / 10);

        int fhtSizeHalf = MotionCorrector.nextPowerOf2(Math.max(halfW, dsH));

        // Left and right halves of reference
        FloatProcessor refLeft = cropHalf(refFp, 0, 0, halfW, dsH);
        FloatProcessor refRight = cropHalf(refFp, halfW, 0, halfW, dsH);
        FHT refLeftFHT = MotionCorrector.computeFHT(refLeft, fhtSizeHalf);
        FHT refRightFHT = MotionCorrector.computeFHT(refRight, fhtSizeHalf);

        double diffSum = 0;
        int count = 0;

        for (int i = 1; i <= nSlices; i += step) {
            FloatProcessor frameFp = dsStack.getProcessor(i).convertToFloatProcessor();
            FloatProcessor frameLeft = cropHalf(frameFp, 0, 0, halfW, dsH);
            FloatProcessor frameRight = cropHalf(frameFp, halfW, 0, halfW, dsH);

            double[] shiftLeft = correlateHalf(frameLeft, refLeftFHT, fhtSizeHalf);
            double[] shiftRight = correlateHalf(frameRight, refRightFHT, fhtSizeHalf);

            double diffX = Math.abs(shiftLeft[0] - shiftRight[0]);
            double diffY = Math.abs(shiftLeft[1] - shiftRight[1]);
            diffSum += Math.sqrt(diffX * diffX + diffY * diffY);
            count++;
        }

        double meanDiff = count > 0 ? diffSum / count : 0;
        // If left and right halves differ by more than 1px on average, rotation is likely
        return meanDiff > 1.0;
    }

    /**
     * Crop a region from a FloatProcessor.
     */
    private static FloatProcessor cropHalf(FloatProcessor fp, int x, int y, int w, int h) {
        fp.setRoi(x, y, w, h);
        return (FloatProcessor) fp.crop().convertToFloatProcessor();
    }

    /**
     * Phase correlate a half-frame against a half-reference.
     */
    private static double[] correlateHalf(FloatProcessor frameFp, FHT refFHT, int fhtSize) {
        FHT frameFHT = MotionCorrector.computeFHT(frameFp, fhtSize);
        FHT correlation = frameFHT.conjugateMultiply(refFHT);
        correlation.inverseTransform();

        float[] px = (float[]) correlation.getPixels();
        for (int j = 0; j < px.length; j++) {
            float mag = Math.abs(px[j]);
            if (mag > 1e-10f) px[j] /= mag;
        }
        correlation.swapQuadrants();

        float[] pixels = (float[]) correlation.getPixels();
        int w = correlation.getWidth();

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
        return new double[]{peakX - w / 2.0, peakY - w / 2.0};
    }
}
