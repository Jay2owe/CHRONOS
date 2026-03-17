package chronos.roi;

import ij.IJ;
import ij.gui.Roi;
import ij.gui.ShapeRoi;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a rectangular grid of ROIs clipped to a boundary polygon.
 * Each grid cell is named "Grid_R{row}_C{col}" (1-indexed).
 */
public class GridRoiGenerator {

    /**
     * Generates a grid of rectangular ROIs within the given boundary ROI.
     *
     * @param boundary the outer boundary ROI (e.g., SCN outline)
     * @param rows     number of grid rows
     * @param cols     number of grid columns
     * @return array of ROIs, one per non-empty grid cell, named "Grid_R{row}_C{col}"
     */
    public static Roi[] generateGrid(Roi boundary, int rows, int cols) {
        if (boundary == null) {
            IJ.log("GridRoiGenerator: No boundary ROI provided.");
            return new Roi[0];
        }
        if (rows < 1 || cols < 1) {
            IJ.log("GridRoiGenerator: Invalid grid size (" + rows + "x" + cols + ").");
            return new Roi[0];
        }

        Rectangle bounds = boundary.getBounds();
        double cellWidth = (double) bounds.width / cols;
        double cellHeight = (double) bounds.height / rows;

        // Convert boundary to ShapeRoi for intersection operations
        ShapeRoi boundaryShape = toShapeRoi(boundary);
        if (boundaryShape == null) {
            IJ.log("GridRoiGenerator: Could not convert boundary to ShapeRoi.");
            return new Roi[0];
        }

        List<Roi> gridRois = new ArrayList<Roi>();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = bounds.x + (int) Math.round(c * cellWidth);
                int y = bounds.y + (int) Math.round(r * cellHeight);
                int w = (int) Math.round((c + 1) * cellWidth) - (int) Math.round(c * cellWidth);
                int h = (int) Math.round((r + 1) * cellHeight) - (int) Math.round(r * cellHeight);

                // Create cell rectangle ROI
                Roi cellRoi = new Roi(x, y, w, h);
                ShapeRoi cellShape = new ShapeRoi(cellRoi);

                // Intersect with boundary
                ShapeRoi clipped = cellShape.and(boundaryShape);

                // Check if the intersection is non-empty
                Rectangle clippedBounds = clipped.getBounds();
                if (clippedBounds.width > 0 && clippedBounds.height > 0) {
                    // Convert back to a standard ROI if possible
                    Roi finalRoi = clipped;
                    finalRoi.setName("Grid_R" + (r + 1) + "_C" + (c + 1));
                    gridRois.add(finalRoi);
                }
            }
        }

        IJ.log("GridRoiGenerator: Created " + gridRois.size() + " grid cells (" +
               rows + " rows x " + cols + " cols).");
        return gridRois.toArray(new Roi[0]);
    }

    /**
     * Converts any ROI type to a ShapeRoi for geometric operations.
     */
    private static ShapeRoi toShapeRoi(Roi roi) {
        if (roi instanceof ShapeRoi) {
            return (ShapeRoi) roi;
        }
        try {
            return new ShapeRoi(roi);
        } catch (Exception e) {
            IJ.log("GridRoiGenerator: Failed to convert ROI to ShapeRoi: " + e.getMessage());
            return null;
        }
    }
}
