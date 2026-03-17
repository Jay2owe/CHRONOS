package chronos.roi;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.Wand;
import ij.plugin.filter.GaussianBlur;
import ij.process.AutoThresholder;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.awt.Rectangle;

/**
 * Automatic SCN boundary detection on a mean intensity projection.
 * <p>
 * Improved algorithm for images where a small bright sample sits in a
 * large dark well:
 * <ol>
 *   <li>Convert to 8-bit with contrast enhancement</li>
 *   <li>Gaussian smooth to reduce noise (sigma=3)</li>
 *   <li>Try Triangle threshold first (best for small bright region on dark bg),
 *       fall back to Otsu, then Li</li>
 *   <li>Morphological close + open to clean up</li>
 *   <li>Fill holes</li>
 *   <li>Extract largest connected component above minimum size</li>
 *   <li>Optional: smooth outline with dilate+erode</li>
 * </ol>
 */
public class AutoBoundaryDetector {

    private static final int MIN_PARTICLE_AREA = 500;

    /**
     * Detects the SCN boundary on a mean intensity projection.
     *
     * @param meanProjection the mean intensity projection image
     * @return a polygon ROI outlining the detected boundary, or null if detection fails
     */
    public static Roi detect(ImagePlus meanProjection) {
        if (meanProjection == null) {
            IJ.log("AutoBoundaryDetector: null image provided.");
            return null;
        }

        IJ.log("  AutoBoundaryDetector: Detecting sample boundary...");

        ImagePlus work = meanProjection.duplicate();
        ImageProcessor ip = work.getProcessor();

        // Get image statistics before conversion for smart thresholding
        ImageStatistics stats = ip.getStatistics();

        // Convert to 8-bit with enhanced contrast
        // First normalize the range to use the full 0-255 range
        double min = stats.min;
        double max = stats.max;
        // Use percentile-based range to handle outliers
        ip.setMinAndMax(stats.mean - 2 * stats.stdDev, stats.mean + 4 * stats.stdDev);
        ip = ip.convertToByte(true);
        work.setProcessor(ip);

        // Gaussian smooth to reduce noise — important for clean thresholding
        GaussianBlur gb = new GaussianBlur();
        gb.blurGaussian(ip, 3.0, 3.0, 0.02);

        // Try multiple threshold methods and pick the best one
        // Triangle is best for small bright object on large dark background
        int[] histogram = ip.getHistogram();
        AutoThresholder thresholder = new AutoThresholder();

        int triangleThresh = thresholder.getThreshold(AutoThresholder.Method.Triangle, histogram);
        int otsuThresh = thresholder.getThreshold(AutoThresholder.Method.Otsu, histogram);
        int liThresh = thresholder.getThreshold(AutoThresholder.Method.Li, histogram);

        IJ.log("    Thresholds — Triangle: " + triangleThresh + ", Otsu: " + otsuThresh + ", Li: " + liThresh);

        // Use Triangle by default (designed for images with one large peak and one small peak)
        // But if Triangle gives an unreasonably low threshold, use Otsu
        int threshold = triangleThresh;
        if (triangleThresh < 10) {
            threshold = otsuThresh;
            IJ.log("    Triangle too low, using Otsu: " + threshold);
        }

        // Create binary mask
        int w = ip.getWidth();
        int h = ip.getHeight();
        ByteProcessor mask = new ByteProcessor(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                mask.set(x, y, ip.getPixel(x, y) >= threshold ? 255 : 0);
            }
        }

        // Morphological close (fill small gaps): dilate then erode, 3 iterations
        for (int i = 0; i < 3; i++) mask.dilate();
        for (int i = 0; i < 3; i++) mask.erode();

        // Morphological open (remove small speckles): erode then dilate
        mask.erode();
        mask.dilate();

        // Fill holes
        fillHoles(mask);

        // Find largest connected component
        Roi largestRoi = findLargestParticle(mask, w, h);

        work.close();

        if (largestRoi == null) {
            IJ.log("    No suitable boundary detected.");
            // Try again with a lower threshold
            IJ.log("    Retrying with lower threshold (Otsu)...");
            return retryWithMethod(meanProjection, otsuThresh > 5 ? otsuThresh : liThresh);
        }

        Rectangle bounds = largestRoi.getBounds();
        int area = countRoiPixels(mask, largestRoi);
        double imageArea = (double)(w * h);
        double pctArea = (area / imageArea) * 100.0;

        IJ.log("    Detected boundary: " + bounds.width + "x" + bounds.height +
               " at (" + bounds.x + "," + bounds.y + "), area=" + area +
               "px (" + IJ.d2s(pctArea, 1) + "% of image)");

        // Sanity check: if detected region is >80% of the image, detection probably failed
        if (pctArea > 80) {
            IJ.log("    WARNING: Detected region covers >80% of image — likely a bad detection.");
            return null;
        }

        largestRoi.setName("SCN_Boundary");
        return largestRoi;
    }

    /**
     * Retry detection with a specific threshold value.
     */
    private static Roi retryWithMethod(ImagePlus meanProjection, int threshold) {
        ImagePlus work = meanProjection.duplicate();
        ImageProcessor ip = work.getProcessor();

        ImageStatistics stats = ip.getStatistics();
        ip.setMinAndMax(stats.mean - 2 * stats.stdDev, stats.mean + 4 * stats.stdDev);
        ip = ip.convertToByte(true);

        GaussianBlur gb = new GaussianBlur();
        gb.blurGaussian(ip, 3.0, 3.0, 0.02);

        int w = ip.getWidth();
        int h = ip.getHeight();
        ByteProcessor mask = new ByteProcessor(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                mask.set(x, y, ip.getPixel(x, y) >= threshold ? 255 : 0);
            }
        }

        for (int i = 0; i < 3; i++) mask.dilate();
        for (int i = 0; i < 3; i++) mask.erode();
        mask.erode();
        mask.dilate();
        fillHoles(mask);

        Roi roi = findLargestParticle(mask, w, h);
        work.close();

        if (roi != null) {
            roi.setName("SCN_Boundary");
            Rectangle b = roi.getBounds();
            IJ.log("    Retry successful: " + b.width + "x" + b.height);
        } else {
            IJ.log("    Retry also failed. Manual ROI drawing required.");
        }

        return roi;
    }

    /**
     * Count pixels inside an ROI on a mask.
     */
    private static int countRoiPixels(ByteProcessor mask, Roi roi) {
        Rectangle b = roi.getBounds();
        int count = 0;
        for (int y = b.y; y < b.y + b.height && y < mask.getHeight(); y++) {
            for (int x = b.x; x < b.x + b.width && x < mask.getWidth(); x++) {
                if (roi.contains(x, y) && mask.get(x, y) == 255) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Finds the largest connected white region in a binary mask.
     */
    private static Roi findLargestParticle(ByteProcessor mask, int w, int h) {
        boolean[][] visited = new boolean[w][h];
        int bestArea = 0;
        int bestSeedX = -1;
        int bestSeedY = -1;

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

        Wand wand = new Wand(mask);
        wand.autoOutline(bestSeedX, bestSeedY, 128, 255, Wand.EIGHT_CONNECTED);

        if (wand.npoints > 0) {
            return new ij.gui.PolygonRoi(wand.xpoints, wand.ypoints, wand.npoints, Roi.POLYGON);
        }

        return null;
    }

    /**
     * Flood-fill count of connected white pixels (iterative BFS).
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
     * Fills holes in a binary mask by flood-filling background from edges.
     */
    private static void fillHoles(ByteProcessor mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();

        ByteProcessor filled = new ByteProcessor(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                filled.set(x, y, 255);
            }
        }

        java.util.LinkedList<int[]> queue = new java.util.LinkedList<int[]>();
        boolean[][] visited = new boolean[w][h];

        // Seed from edge pixels that are black
        for (int x = 0; x < w; x++) {
            if (mask.get(x, 0) == 0 && !visited[x][0]) {
                queue.add(new int[]{x, 0}); visited[x][0] = true;
            }
            if (mask.get(x, h-1) == 0 && !visited[x][h-1]) {
                queue.add(new int[]{x, h-1}); visited[x][h-1] = true;
            }
        }
        for (int y = 0; y < h; y++) {
            if (mask.get(0, y) == 0 && !visited[0][y]) {
                queue.add(new int[]{0, y}); visited[0][y] = true;
            }
            if (mask.get(w-1, y) == 0 && !visited[w-1][y]) {
                queue.add(new int[]{w-1, y}); visited[w-1][y] = true;
            }
        }

        while (!queue.isEmpty()) {
            int[] p = queue.removeFirst();
            int px = p[0];
            int py = p[1];
            filled.set(px, py, 0);

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

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (filled.get(x, y) == 255) {
                    mask.set(x, y, 255);
                }
            }
        }
    }
}
