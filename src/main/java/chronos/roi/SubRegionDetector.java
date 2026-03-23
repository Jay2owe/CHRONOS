package chronos.roi;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.Wand;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated SCN sub-region detection: Core vs Shell.
 * <p>
 * After auto-boundary detection provides the whole SCN outline, this class
 * subdivides it into Core (brighter, ventromedial) and Shell (dimmer, dorsolateral)
 * using k-means clustering on mean intensity within the boundary.
 * <p>
 * Algorithm:
 * 1. Extract pixel intensities within the SCN boundary ROI
 * 2. K-means clustering (k=2) on intensity values
 * 3. Build binary masks for each cluster
 * 4. Morphological cleanup (close + open)
 * 5. Extract polygon ROIs from each cluster mask
 * 6. Label the brighter cluster as "Core", dimmer as "Shell"
 */
public class SubRegionDetector {

    private SubRegionDetector() {}

    /**
     * Detect Core and Shell sub-regions within an SCN boundary.
     *
     * @param meanProjection the mean intensity projection
     * @param scnBoundary    the whole SCN boundary ROI
     * @return array of ROIs: [0]=Core, [1]=Shell, or empty if detection fails
     */
    public static Roi[] detectCoreShell(ImagePlus meanProjection, Roi scnBoundary) {
        if (meanProjection == null || scnBoundary == null) {
            return new Roi[0];
        }

        IJ.log("  SubRegionDetector: Detecting Core/Shell...");

        ImageProcessor ip = meanProjection.getProcessor().convertToFloatProcessor();
        int w = ip.getWidth();
        int h = ip.getHeight();
        float[] pixels = (float[]) ip.getPixels();
        Rectangle bounds = scnBoundary.getBounds();

        // Extract intensities within the boundary
        List<Float> intensities = new ArrayList<Float>();
        List<int[]> coords = new ArrayList<int[]>();

        for (int y = bounds.y; y < bounds.y + bounds.height && y < h; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width && x < w; x++) {
                if (scnBoundary.contains(x, y)) {
                    intensities.add(pixels[y * w + x]);
                    coords.add(new int[]{x, y});
                }
            }
        }

        if (intensities.size() < 50) {
            IJ.log("    Too few pixels inside boundary (" + intensities.size() + ")");
            return new Roi[0];
        }

        // K-means clustering (k=2) on intensity
        float[] values = new float[intensities.size()];
        for (int i = 0; i < values.length; i++) values[i] = intensities.get(i);

        int[] labels = kMeans2(values);

        // Determine which cluster is brighter (Core)
        double sum0 = 0, sum1 = 0;
        int count0 = 0, count1 = 0;
        for (int i = 0; i < values.length; i++) {
            if (labels[i] == 0) { sum0 += values[i]; count0++; }
            else { sum1 += values[i]; count1++; }
        }
        double mean0 = count0 > 0 ? sum0 / count0 : 0;
        double mean1 = count1 > 0 ? sum1 / count1 : 0;

        int coreLabel = (mean0 > mean1) ? 0 : 1;

        IJ.log("    Cluster 0: mean=" + IJ.d2s(mean0, 1) + " (" + count0 + " px)");
        IJ.log("    Cluster 1: mean=" + IJ.d2s(mean1, 1) + " (" + count1 + " px)");
        IJ.log("    Core = cluster " + coreLabel + " (brighter)");

        // Build masks for each cluster
        ByteProcessor coreMask = new ByteProcessor(w, h);
        ByteProcessor shellMask = new ByteProcessor(w, h);

        for (int i = 0; i < coords.size(); i++) {
            int x = coords.get(i)[0];
            int y = coords.get(i)[1];
            if (labels[i] == coreLabel) {
                coreMask.set(x, y, 255);
            } else {
                shellMask.set(x, y, 255);
            }
        }

        // Morphological cleanup
        cleanupMask(coreMask);
        cleanupMask(shellMask);

        // Extract ROIs
        Roi coreRoi = maskToRoi(coreMask, w, h);
        Roi shellRoi = maskToRoi(shellMask, w, h);

        List<Roi> result = new ArrayList<Roi>();
        if (coreRoi != null) {
            coreRoi.setName("SCN_Core");
            result.add(coreRoi);
            Rectangle cb = coreRoi.getBounds();
            IJ.log("    Core: " + cb.width + "x" + cb.height + " at (" + cb.x + "," + cb.y + ")");
        }
        if (shellRoi != null) {
            shellRoi.setName("SCN_Shell");
            result.add(shellRoi);
            Rectangle sb = shellRoi.getBounds();
            IJ.log("    Shell: " + sb.width + "x" + sb.height + " at (" + sb.x + "," + sb.y + ")");
        }

        return result.toArray(new Roi[0]);
    }

    /**
     * Simple k-means clustering with k=2 on 1D data.
     * Returns array of labels (0 or 1) for each data point.
     */
    private static int[] kMeans2(float[] data) {
        int n = data.length;
        int[] labels = new int[n];

        // Initialize centroids as min and max
        float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
        for (float v : data) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float c0 = min + (max - min) * 0.33f;
        float c1 = min + (max - min) * 0.67f;

        for (int iter = 0; iter < 50; iter++) {
            // Assign
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int newLabel = (Math.abs(data[i] - c0) <= Math.abs(data[i] - c1)) ? 0 : 1;
                if (newLabel != labels[i]) {
                    labels[i] = newLabel;
                    changed = true;
                }
            }
            if (!changed) break;

            // Update centroids
            double sum0 = 0, sum1 = 0;
            int count0 = 0, count1 = 0;
            for (int i = 0; i < n; i++) {
                if (labels[i] == 0) { sum0 += data[i]; count0++; }
                else { sum1 += data[i]; count1++; }
            }
            c0 = count0 > 0 ? (float) (sum0 / count0) : c0;
            c1 = count1 > 0 ? (float) (sum1 / count1) : c1;
        }

        return labels;
    }

    private static void cleanupMask(ByteProcessor mask) {
        // Close: fill small gaps
        for (int i = 0; i < 2; i++) mask.dilate();
        for (int i = 0; i < 2; i++) mask.erode();
        // Open: remove speckles
        mask.erode();
        mask.dilate();
    }

    private static Roi maskToRoi(ByteProcessor mask, int w, int h) {
        // Find the largest white region and trace its outline
        boolean[][] visited = new boolean[w][h];
        int bestArea = 0;
        int bestX = -1, bestY = -1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (mask.get(x, y) == 255 && !visited[x][y]) {
                    int area = floodCount(mask, visited, x, y, w, h);
                    if (area > bestArea && area >= 100) {
                        bestArea = area;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
        }

        if (bestX < 0) return null;

        Wand wand = new Wand(mask);
        wand.autoOutline(bestX, bestY, 128, 255, Wand.EIGHT_CONNECTED);
        if (wand.npoints > 0) {
            return new PolygonRoi(wand.xpoints, wand.ypoints, wand.npoints, Roi.POLYGON);
        }
        return null;
    }

    private static int floodCount(ByteProcessor mask, boolean[][] visited,
                                   int startX, int startY, int w, int h) {
        java.util.LinkedList<int[]> queue = new java.util.LinkedList<int[]>();
        queue.add(new int[]{startX, startY});
        visited[startX][startY] = true;
        int count = 0;

        while (!queue.isEmpty()) {
            int[] p = queue.removeFirst();
            count++;
            int[][] nb = {{p[0]-1,p[1]},{p[0]+1,p[1]},{p[0],p[1]-1},{p[0],p[1]+1}};
            for (int[] n : nb) {
                if (n[0] >= 0 && n[0] < w && n[1] >= 0 && n[1] < h
                        && !visited[n[0]][n[1]] && mask.get(n[0], n[1]) == 255) {
                    visited[n[0]][n[1]] = true;
                    queue.add(n);
                }
            }
        }
        return count;
    }
}
