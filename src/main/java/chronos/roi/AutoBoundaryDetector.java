package chronos.roi;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.gui.Wand;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.filter.ParticleAnalyzer;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;

/**
 * Automatic SCN boundary detection on a mean intensity projection.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>Auto-threshold using Otsu method</li>
 *   <li>Morphological cleanup (close, fill holes)</li>
 *   <li>Extract largest particle above minimum size</li>
 *   <li>Return as polygon ROI</li>
 * </ol>
 * Returns null if detection fails (too noisy, no clear boundary).
 */
public class AutoBoundaryDetector {

    /** Minimum particle area in pixels squared */
    private static final int MIN_PARTICLE_AREA = 1000;

    /**
     * Detects the SCN boundary on a mean intensity projection.
     *
     * @param meanProjection the mean intensity projection image
     * @return a polygon ROI outlining the detected SCN boundary, or null if detection fails
     */
    public static Roi detect(ImagePlus meanProjection) {
        if (meanProjection == null) {
            IJ.log("AutoBoundaryDetector: null image provided.");
            return null;
        }

        IJ.log("AutoBoundaryDetector: Detecting SCN boundary...");

        // Work on a duplicate to avoid modifying the original
        ImagePlus work = meanProjection.duplicate();
        ImageProcessor ip = work.getProcessor();

        // Convert to 8-bit if necessary
        if (ip.getBitDepth() != 8) {
            ip = ip.convertToByte(true);
            work.setProcessor(ip);
        }

        // Apply Otsu auto-threshold
        ip.setAutoThreshold("Otsu dark");
        int threshold = (int) ip.getMinThreshold();
        IJ.log("  Otsu threshold: " + threshold);

        // Create binary mask
        ByteProcessor mask = new ByteProcessor(ip.getWidth(), ip.getHeight());
        for (int y = 0; y < ip.getHeight(); y++) {
            for (int x = 0; x < ip.getWidth(); x++) {
                if (ip.getPixel(x, y) >= threshold) {
                    mask.set(x, y, 255);
                } else {
                    mask.set(x, y, 0);
                }
            }
        }

        // Morphological close (dilate then erode) to fill small gaps
        mask.dilate();
        mask.dilate();
        mask.erode();
        mask.erode();

        // Fill holes
        fillHoles(mask);

        // Use Particle Analyzer to find largest particle
        ImagePlus maskImp = new ImagePlus("mask", mask);
        maskImp.getProcessor().setThreshold(128, 255, ImageProcessor.NO_LUT_UPDATE);

        ResultsTable rt = new ResultsTable();
        int options = ParticleAnalyzer.SHOW_NONE | ParticleAnalyzer.ADD_TO_MANAGER;

        // We cannot rely on ROI Manager in headless mode, so use a different approach:
        // Use Wand tool to trace the largest connected region
        Roi largestRoi = findLargestParticle(mask);

        work.close();
        maskImp.close();

        if (largestRoi == null) {
            IJ.log("  AutoBoundaryDetector: No suitable boundary detected.");
            return null;
        }

        Rectangle bounds = largestRoi.getBounds();
        double area = bounds.width * bounds.height; // approximate
        IJ.log("  Detected boundary: " + bounds.width + "x" + bounds.height +
               " at (" + bounds.x + ", " + bounds.y + ")");

        largestRoi.setName("SCN_Boundary");
        return largestRoi;
    }

    /**
     * Finds the largest connected white region in a binary mask using flood-fill
     * and wand tracing.
     */
    private static Roi findLargestParticle(ByteProcessor mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();

        // Track visited pixels
        boolean[][] visited = new boolean[w][h];
        int bestArea = 0;
        int bestSeedX = -1;
        int bestSeedY = -1;

        // Scan for connected components by flood-filling
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (mask.get(x, y) == 255 && !visited[x][y]) {
                    int area = floodCount(mask, visited, x, y, w, h);
                    if (area > bestArea && area >= MIN_PARTICLE_AREA) {
                        bestArea = area;
                        bestSeedX = x;
                        bestSeedY = y;
                    }
                }
            }
        }

        if (bestSeedX < 0) {
            return null;
        }

        // Use Wand to trace the outline of the largest particle
        Wand wand = new Wand(mask);
        wand.autoOutline(bestSeedX, bestSeedY, 128, 255, Wand.EIGHT_CONNECTED);

        if (wand.npoints > 0) {
            Roi roi = new ij.gui.PolygonRoi(wand.xpoints, wand.ypoints, wand.npoints,
                                            Roi.POLYGON);
            return roi;
        }

        return null;
    }

    /**
     * Flood-fill count of connected white pixels, marking them as visited.
     * Uses iterative approach to avoid stack overflow.
     */
    private static int floodCount(ByteProcessor mask, boolean[][] visited,
                                  int startX, int startY, int w, int h) {
        java.util.LinkedList<int[]> queue = new java.util.LinkedList<int[]>();
        queue.add(new int[]{startX, startY});
        visited[startX][startY] = true;
        int count = 0;

        while (!queue.isEmpty()) {
            int[] p = queue.removeFirst();
            int px = p[0];
            int py = p[1];
            count++;

            // Check 4-connected neighbors
            int[][] neighbors = {{px-1, py}, {px+1, py}, {px, py-1}, {px, py+1}};
            for (int[] n : neighbors) {
                int nx = n[0];
                int ny = n[1];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h &&
                    !visited[nx][ny] && mask.get(nx, ny) == 255) {
                    visited[nx][ny] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }

        return count;
    }

    /**
     * Fills holes in a binary mask (white objects on black background).
     * Flood-fills from edges with black, then inverts logic.
     */
    private static void fillHoles(ByteProcessor mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();

        // Create a copy, flood-fill background from edges
        ByteProcessor bg = (ByteProcessor) mask.duplicate();

        // Mark all as foreground initially
        ByteProcessor filled = new ByteProcessor(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                filled.set(x, y, 255);
            }
        }

        // Flood-fill from edges to find background
        java.util.LinkedList<int[]> queue = new java.util.LinkedList<int[]>();
        boolean[][] visited = new boolean[w][h];

        // Seed from all edge pixels that are black
        for (int x = 0; x < w; x++) {
            if (mask.get(x, 0) == 0 && !visited[x][0]) {
                queue.add(new int[]{x, 0});
                visited[x][0] = true;
            }
            if (mask.get(x, h-1) == 0 && !visited[x][h-1]) {
                queue.add(new int[]{x, h-1});
                visited[x][h-1] = true;
            }
        }
        for (int y = 0; y < h; y++) {
            if (mask.get(0, y) == 0 && !visited[0][y]) {
                queue.add(new int[]{0, y});
                visited[0][y] = true;
            }
            if (mask.get(w-1, y) == 0 && !visited[w-1][y]) {
                queue.add(new int[]{w-1, y});
                visited[w-1][y] = true;
            }
        }

        while (!queue.isEmpty()) {
            int[] p = queue.removeFirst();
            int px = p[0];
            int py = p[1];
            filled.set(px, py, 0); // This is confirmed background

            int[][] neighbors = {{px-1, py}, {px+1, py}, {px, py-1}, {px, py+1}};
            for (int[] n : neighbors) {
                int nx = n[0];
                int ny = n[1];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h &&
                    !visited[nx][ny] && mask.get(nx, ny) == 0) {
                    visited[nx][ny] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }

        // Copy the filled result back: anything that is 255 in 'filled' and was 0
        // in original mask is a filled hole
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (filled.get(x, y) == 255) {
                    mask.set(x, y, 255);
                }
            }
        }
    }
}
