package chronos.rhythm;

/**
 * Result from wavelet ridge extraction.
 * <p>
 * Contains instantaneous period, amplitude, and phase along the ridge
 * (the path of maximum power through the scalogram).
 */
public class RidgeResult {

    /** Instantaneous period (hours) at each timepoint along the ridge. */
    public final double[] instantPeriod;

    /** Instantaneous amplitude (sqrt of power) at each timepoint along the ridge. */
    public final double[] instantAmplitude;

    /** Instantaneous phase (radians) at each timepoint along the ridge. */
    public final double[] instantPhase;

    /** Scale index of the ridge at each timepoint. */
    public final int[] ridgeScaleIndices;

    public RidgeResult(double[] instantPeriod, double[] instantAmplitude,
                       double[] instantPhase, int[] ridgeScaleIndices) {
        this.instantPeriod = instantPeriod;
        this.instantAmplitude = instantAmplitude;
        this.instantPhase = instantPhase;
        this.ridgeScaleIndices = ridgeScaleIndices;
    }
}
