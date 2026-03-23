package chronos.preprocessing;

/**
 * Immutable result of a motion-correction registration.
 * Holds per-frame shifts, quality metrics, and metadata about the method used.
 */
public class RegistrationResult {

    /** Per-frame X shift (pixels) */
    public final double[] shiftX;
    /** Per-frame Y shift (pixels) */
    public final double[] shiftY;
    /** Per-frame correlation quality [0-1] */
    public final double[] quality;
    /** Method that produced this result */
    public final String method;
    /** Reference frame method used */
    public final String reference;
    /** Number of frames */
    public final int nFrames;
    /** Maximum shift magnitude across all frames */
    public final double maxShift;

    public RegistrationResult(double[] shiftX, double[] shiftY, double[] quality,
                              String method, String reference) {
        this.shiftX = shiftX;
        this.shiftY = shiftY;
        this.quality = quality;
        this.method = method;
        this.reference = reference;
        this.nFrames = shiftX.length;

        double max = 0;
        for (int i = 0; i < nFrames; i++) {
            double mag = Math.sqrt(shiftX[i] * shiftX[i] + shiftY[i] * shiftY[i]);
            if (mag > max) max = mag;
        }
        this.maxShift = max;
    }
}
