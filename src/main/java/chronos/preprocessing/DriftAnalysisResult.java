package chronos.preprocessing;

/**
 * Immutable result of automatic drift characterization.
 * Produced by {@link DriftAnalyzer#analyze}.
 */
public class DriftAnalysisResult {

    /** Drift pattern: "sparse", "continuous", or "chaotic" */
    public final String driftPattern;
    /** Per-frame X drift from reference (pixels, full resolution) */
    public final double[] dx;
    /** Per-frame Y drift from reference (pixels, full resolution) */
    public final double[] dy;
    /** Per-frame phase correlation quality [0-1] */
    public final double[] correlationQuality;
    /** Frame indices where drift jumps were detected */
    public final int[] transitionFrames;
    /** Number of detected transitions */
    public final int nTransitions;
    /** Whether rotation was detected (left/right half shifts differ) */
    public final boolean rotationLikely;
    /** Recommended registration method */
    public final String recommendedMethod;
    /** Human-readable reason for the recommendation */
    public final String recommendationReason;

    public DriftAnalysisResult(String driftPattern,
                               double[] dx, double[] dy, double[] correlationQuality,
                               int[] transitionFrames, boolean rotationLikely,
                               String recommendedMethod, String recommendationReason) {
        this.driftPattern = driftPattern;
        this.dx = dx;
        this.dy = dy;
        this.correlationQuality = correlationQuality;
        this.transitionFrames = transitionFrames;
        this.nTransitions = transitionFrames.length;
        this.rotationLikely = rotationLikely;
        this.recommendedMethod = recommendedMethod;
        this.recommendationReason = recommendationReason;
    }
}
