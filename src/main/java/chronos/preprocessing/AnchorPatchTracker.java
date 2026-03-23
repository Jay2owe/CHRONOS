package chronos.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.Arrays;

/**
 * Anchor-patch tracking for motion correction.
 * Places multiple small patches at high-gradient regions and tracks them
 * via cross-correlation, producing robust shift estimates via median.
 */
public class AnchorPatchTracker {

    private static final int PATCH_SIZE = 32;
    private static final int SEARCH_WINDOW = 64;
    private static final int NUM_PATCHES = 6;
    private static final int MIN_SPACING = 50;

    /**
     * Track drift using anchor patches at high-gradient regions.
     *
     * @param imp       input stack
     * @param refMethod "first", "mean", or "median"
     * @return RegistrationResult with median-of-patches shifts
     */
    public static RegistrationResult track(ImagePlus imp, String refMethod) {
        int nSlices = imp.getStackSize();
        if (nSlices <= 1) {
            return new RegistrationResult(new double[]{0}, new double[]{0}, new double[]{1.0},
                    "Anchor-Patch Tracking", refMethod);
        }

        IJ.log("  Anchor-patch tracking (" + nSlices + " frames)...");
        long startTime = System.currentTimeMillis();

        int width = imp.getWidth();
        int height = imp.getHeight();
        ImageStack inputStack = imp.getStack();

        // Step 1: Compute reference frame
        FloatProcessor refFp = MotionCorrector.computeReference(imp, refMethod);

        // Step 2: Auto-place patches at high-gradient regions
        int[][] patchPositions = findPatchPositions(refFp, width, height);
        int nPatches = patchPositions.length;

        IJ.log("    Placed " + nPatches + " anchor patches");

        // Step 3: Extract reference patches
        float[][][] refPatches = new float[nPatches][][];
        for (int p = 0; p < nPatches; p++) {
            refPatches[p] = extractPatch(refFp, patchPositions[p][0], patchPositions[p][1]);
        }

        // Step 4: Track each patch across frames
        double[] shiftX = new double[nSlices];
        double[] shiftY = new double[nSlices];
        double[] quality = new double[nSlices];

        for (int i = 1; i <= nSlices; i++) {
            FloatProcessor frameFp = inputStack.getProcessor(i).convertToFloatProcessor();

            double[] patchDx = new double[nPatches];
            double[] patchDy = new double[nPatches];
            double[] patchQ = new double[nPatches];
            int validPatches = 0;

            for (int p = 0; p < nPatches; p++) {
                double[] result = correlateSearchWindow(frameFp, refPatches[p],
                        patchPositions[p][0], patchPositions[p][1], width, height);
                if (result != null) {
                    patchDx[validPatches] = result[0];
                    patchDy[validPatches] = result[1];
                    patchQ[validPatches] = result[2];
                    validPatches++;
                }
            }

            if (validPatches > 0) {
                // Robust shift estimate: median of valid patches
                double[] trimDx = Arrays.copyOf(patchDx, validPatches);
                double[] trimDy = Arrays.copyOf(patchDy, validPatches);
                double[] trimQ = Arrays.copyOf(patchQ, validPatches);

                shiftX[i - 1] = median(trimDx);
                shiftY[i - 1] = median(trimDy);
                quality[i - 1] = median(trimQ);
            }

            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        IJ.log("    Anchor-patch tracking complete in " + elapsed + "ms");

        return new RegistrationResult(shiftX, shiftY, quality,
                "Anchor-Patch Tracking", refMethod);
    }

    /**
     * Find patch positions at high-gradient regions using Sobel magnitude.
     */
    private static int[][] findPatchPositions(FloatProcessor fp, int width, int height) {
        // Compute Sobel gradient magnitude
        FloatProcessor gx = (FloatProcessor) fp.duplicate();
        FloatProcessor gy = (FloatProcessor) fp.duplicate();
        gx.convolve3x3(new int[]{-1, 0, 1, -2, 0, 2, -1, 0, 1});
        gy.convolve3x3(new int[]{-1, -2, -1, 0, 0, 0, 1, 2, 1});

        float[] gxPix = (float[]) gx.getPixels();
        float[] gyPix = (float[]) gy.getPixels();
        float[] magPix = new float[gxPix.length];
        for (int i = 0; i < gxPix.length; i++) {
            magPix[i] = (float) Math.sqrt(gxPix[i] * gxPix[i] + gyPix[i] * gyPix[i]);
        }

        // Margin to avoid edges
        int margin = PATCH_SIZE + SEARCH_WINDOW / 2;

        // Find peaks in gradient magnitude, spaced > MIN_SPACING apart
        // Prefer edges over interior
        int[][] positions = new int[NUM_PATCHES][2];
        int found = 0;

        // Create indexed list of gradient magnitudes (only within margins)
        int count = 0;
        for (int y = margin; y < height - margin; y++) {
            for (int x = margin; x < width - margin; x++) {
                count++;
            }
        }

        int[][] candidates = new int[count][3]; // x, y, gradient (scaled to int for sorting)
        int ci = 0;
        for (int y = margin; y < height - margin; y++) {
            for (int x = margin; x < width - margin; x++) {
                candidates[ci][0] = x;
                candidates[ci][1] = y;
                candidates[ci][2] = (int) (magPix[y * width + x] * 1000);
                ci++;
            }
        }

        // Sort by gradient magnitude descending
        Arrays.sort(candidates, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) {
                return b[2] - a[2];
            }
        });

        // Greedily pick peaks with minimum spacing
        for (int[] cand : candidates) {
            if (found >= NUM_PATCHES) break;

            boolean tooClose = false;
            for (int j = 0; j < found; j++) {
                int ddx = cand[0] - positions[j][0];
                int ddy = cand[1] - positions[j][1];
                if (Math.sqrt(ddx * ddx + ddy * ddy) < MIN_SPACING) {
                    tooClose = true;
                    break;
                }
            }

            if (!tooClose) {
                positions[found][0] = cand[0];
                positions[found][1] = cand[1];
                found++;
            }
        }

        if (found < NUM_PATCHES) {
            return Arrays.copyOf(positions, found);
        }
        return positions;
    }

    /**
     * Extract a PATCH_SIZE x PATCH_SIZE patch centered at (cx, cy).
     */
    private static float[][] extractPatch(FloatProcessor fp, int cx, int cy) {
        int halfP = PATCH_SIZE / 2;
        float[][] patch = new float[PATCH_SIZE][PATCH_SIZE];
        float[] pixels = (float[]) fp.getPixels();
        int w = fp.getWidth();

        for (int py = 0; py < PATCH_SIZE; py++) {
            int iy = cy - halfP + py;
            for (int px = 0; px < PATCH_SIZE; px++) {
                int ix = cx - halfP + px;
                if (ix >= 0 && ix < fp.getWidth() && iy >= 0 && iy < fp.getHeight()) {
                    patch[py][px] = pixels[iy * w + ix];
                }
            }
        }
        return patch;
    }

    /**
     * Cross-correlate a reference patch within a search window around expected position.
     * Returns [dx, dy, quality] or null if out of bounds.
     */
    private static double[] correlateSearchWindow(FloatProcessor frame, float[][] refPatch,
                                                   int cx, int cy, int width, int height) {
        int halfP = PATCH_SIZE / 2;
        int halfS = SEARCH_WINDOW / 2;

        // Ensure search window is within frame
        int searchMinX = cx - halfS;
        int searchMinY = cy - halfS;
        int searchMaxX = cx + halfS;
        int searchMaxY = cy + halfS;

        if (searchMinX - halfP < 0 || searchMinY - halfP < 0
                || searchMaxX + halfP >= width || searchMaxY + halfP >= height) {
            return null;
        }

        float[] pixels = (float[]) frame.getPixels();
        int w = frame.getWidth();

        // Brute-force NCC search within window
        double bestNCC = -1;
        int bestSx = 0, bestSy = 0;

        // Pre-compute reference mean and std
        double refMean = 0;
        for (int py = 0; py < PATCH_SIZE; py++) {
            for (int px = 0; px < PATCH_SIZE; px++) {
                refMean += refPatch[py][px];
            }
        }
        refMean /= (PATCH_SIZE * PATCH_SIZE);
        double refStd = 0;
        for (int py = 0; py < PATCH_SIZE; py++) {
            for (int px = 0; px < PATCH_SIZE; px++) {
                double d = refPatch[py][px] - refMean;
                refStd += d * d;
            }
        }
        refStd = Math.sqrt(refStd);
        if (refStd < 1e-10) return null;

        // Search in steps of 1 pixel
        for (int sy = searchMinY; sy <= searchMaxY; sy++) {
            for (int sx = searchMinX; sx <= searchMaxX; sx++) {
                // Compute NCC at this position
                double frameMean = 0;
                for (int py = 0; py < PATCH_SIZE; py++) {
                    int iy = sy - halfP + py;
                    for (int px = 0; px < PATCH_SIZE; px++) {
                        int ix = sx - halfP + px;
                        frameMean += pixels[iy * w + ix];
                    }
                }
                frameMean /= (PATCH_SIZE * PATCH_SIZE);

                double frameStd = 0;
                double crossCorr = 0;
                for (int py = 0; py < PATCH_SIZE; py++) {
                    int iy = sy - halfP + py;
                    for (int px = 0; px < PATCH_SIZE; px++) {
                        int ix = sx - halfP + px;
                        double fv = pixels[iy * w + ix] - frameMean;
                        double rv = refPatch[py][px] - refMean;
                        frameStd += fv * fv;
                        crossCorr += fv * rv;
                    }
                }
                frameStd = Math.sqrt(frameStd);
                if (frameStd < 1e-10) continue;

                double ncc = crossCorr / (refStd * frameStd);
                if (ncc > bestNCC) {
                    bestNCC = ncc;
                    bestSx = sx;
                    bestSy = sy;
                }
            }
        }

        double dx = bestSx - cx;
        double dy = bestSy - cy;
        return new double[]{dx, dy, Math.max(0, bestNCC)};
    }

    /**
     * Compute median of a double array.
     */
    private static double median(double[] arr) {
        double[] sorted = arr.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 0) {
            return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        }
        return sorted[n / 2];
    }
}
