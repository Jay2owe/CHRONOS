package chronos.tracking;

/**
 * Represents a single tracked cell across time.
 * Contains per-frame position, area, and computed motility metrics.
 */
public class CellTrack {

    /** Unique track identifier from TrackMate. */
    public final int trackID;

    /** Frame indices where this cell was detected (0-based). */
    public final int[] frames;

    /** X position (pixels) at each detected frame. */
    public final double[] x;

    /** Y position (pixels) at each detected frame. */
    public final double[] y;

    /** Cell area (pixels^2) at each detected frame. */
    public final double[] area;

    public CellTrack(int trackID, int[] frames, double[] x, double[] y, double[] area) {
        this.trackID = trackID;
        this.frames = frames;
        this.x = x;
        this.y = y;
        this.area = area;
    }

    /** Number of frames this cell was tracked. */
    public int length() {
        return frames.length;
    }

    /** First frame this cell appeared. */
    public int firstFrame() {
        return frames.length > 0 ? frames[0] : -1;
    }

    /** Last frame this cell appeared. */
    public int lastFrame() {
        return frames.length > 0 ? frames[frames.length - 1] : -1;
    }

    /** Track duration in frames. */
    public int duration() {
        if (frames.length < 2) return 0;
        return frames[frames.length - 1] - frames[0];
    }
}
