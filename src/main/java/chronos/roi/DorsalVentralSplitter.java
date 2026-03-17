package chronos.roi;

import ij.IJ;
import ij.gui.Line;
import ij.gui.Roi;
import ij.gui.ShapeRoi;

import java.awt.Rectangle;
import java.awt.Polygon;

/**
 * Splits an SCN outline ROI into Dorsal and Ventral sub-ROIs using a
 * user-drawn dividing line. The line is extended to span the full width
 * of the SCN bounding box, then used to clip the outline into two halves.
 * <p>
 * Convention: Dorsal = above the line (lower Y values), Ventral = below
 * the line (higher Y values), following standard neuroanatomical orientation
 * where dorsal is up in coronal sections.
 */
public class DorsalVentralSplitter {

    /**
     * Splits the SCN outline into dorsal and ventral sub-ROIs.
     *
     * @param scnOutline   the whole-SCN polygon ROI
     * @param dividingLine a straight line drawn across the SCN
     * @return array of [dorsalRoi, ventralRoi], named "Dorsal" and "Ventral".
     *         Returns empty array if splitting fails.
     */
    public static Roi[] split(Roi scnOutline, Line dividingLine) {
        if (scnOutline == null || dividingLine == null) {
            IJ.log("DorsalVentralSplitter: Null SCN outline or dividing line.");
            return new Roi[0];
        }

        Rectangle bounds = scnOutline.getBounds();
        int margin = 10; // extend beyond bounds to ensure full coverage

        // Get the dividing line endpoints
        double x1 = dividingLine.x1d;
        double y1 = dividingLine.y1d;
        double x2 = dividingLine.x2d;
        double y2 = dividingLine.y2d;

        // Compute line equation: y = mx + b, or handle vertical lines
        double dx = x2 - x1;
        double dy = y2 - y1;

        // Extend line to span well beyond the SCN bounding box
        int leftX = bounds.x - margin;
        int rightX = bounds.x + bounds.width + margin;
        int topY = bounds.y - margin;
        int bottomY = bounds.y + bounds.height + margin;

        double leftY, rightY;
        if (Math.abs(dx) < 0.001) {
            // Nearly vertical line -- unusual for D/V split but handle it
            IJ.log("DorsalVentralSplitter: Warning -- nearly vertical dividing line.");
            leftY = topY;
            rightY = bottomY;
        } else {
            double slope = dy / dx;
            double intercept = y1 - slope * x1;
            leftY = slope * leftX + intercept;
            rightY = slope * rightX + intercept;
        }

        // Create dorsal polygon (above the line = lower Y)
        // Polygon: top-left -> top-right -> right-line-point -> left-line-point
        Polygon dorsalPoly = new Polygon();
        dorsalPoly.addPoint(leftX, topY);
        dorsalPoly.addPoint(rightX, topY);
        dorsalPoly.addPoint(rightX, (int) Math.round(rightY));
        dorsalPoly.addPoint(leftX, (int) Math.round(leftY));

        // Create ventral polygon (below the line = higher Y)
        // Polygon: left-line-point -> right-line-point -> bottom-right -> bottom-left
        Polygon ventralPoly = new Polygon();
        ventralPoly.addPoint(leftX, (int) Math.round(leftY));
        ventralPoly.addPoint(rightX, (int) Math.round(rightY));
        ventralPoly.addPoint(rightX, bottomY);
        ventralPoly.addPoint(leftX, bottomY);

        // Convert to ShapeRois and intersect with SCN outline
        ShapeRoi scnShape = toShapeRoi(scnOutline);
        if (scnShape == null) {
            IJ.log("DorsalVentralSplitter: Could not convert SCN outline to ShapeRoi.");
            return new Roi[0];
        }

        ShapeRoi dorsalClip = new ShapeRoi(new ij.gui.PolygonRoi(dorsalPoly, Roi.POLYGON));
        ShapeRoi ventralClip = new ShapeRoi(new ij.gui.PolygonRoi(ventralPoly, Roi.POLYGON));

        ShapeRoi dorsalResult = dorsalClip.and(scnShape);
        ShapeRoi ventralResult = ventralClip.and(scnShape);

        // Validate both results are non-empty
        Rectangle dorsalBounds = dorsalResult.getBounds();
        Rectangle ventralBounds = ventralResult.getBounds();

        if (dorsalBounds.width == 0 || dorsalBounds.height == 0) {
            IJ.log("DorsalVentralSplitter: Dorsal ROI is empty -- line may be outside SCN.");
            return new Roi[0];
        }
        if (ventralBounds.width == 0 || ventralBounds.height == 0) {
            IJ.log("DorsalVentralSplitter: Ventral ROI is empty -- line may be outside SCN.");
            return new Roi[0];
        }

        dorsalResult.setName("Dorsal");
        ventralResult.setName("Ventral");

        IJ.log("DorsalVentralSplitter: Split into Dorsal (" +
               dorsalBounds.width + "x" + dorsalBounds.height + ") and Ventral (" +
               ventralBounds.width + "x" + ventralBounds.height + ").");

        return new Roi[]{dorsalResult, ventralResult};
    }

    /**
     * Converts any ROI to a ShapeRoi.
     */
    private static ShapeRoi toShapeRoi(Roi roi) {
        if (roi instanceof ShapeRoi) {
            return (ShapeRoi) roi;
        }
        try {
            return new ShapeRoi(roi);
        } catch (Exception e) {
            return null;
        }
    }
}
