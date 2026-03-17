package chronos.rhythm;

/**
 * Result from Morlet continuous wavelet transform (CWT).
 * <p>
 * Contains the full scalogram (power as a function of scale and time),
 * significance testing results, cone of influence, and optionally the
 * extracted ridge.
 */
public class WaveletResult {

    /** Wavelet power spectrum: power[scaleIndex][timeIndex]. Dimensions: nScales x nTimePoints. */
    public final double[][] power;

    /** Wavelet scales used in the transform. */
    public final double[] scales;

    /** Fourier periods corresponding to each scale (hours). */
    public final double[] periods;

    /** Time values for each timepoint (hours). */
    public final double[] times;

    /**
     * Significance levels: significance[scaleIndex][timeIndex].
     * Values > 1.0 indicate the power at that point exceeds the
     * 95% confidence level against the red-noise null hypothesis.
     */
    public final double[][] significance;

    /** Cone of influence boundary — the e-folding time (hours) at each timepoint. */
    public final double[] coneOfInfluence;

    /** Ridge extraction result (may be null if not yet extracted). */
    public RidgeResult ridge;

    public WaveletResult(double[][] power, double[] scales, double[] periods,
                         double[] times, double[][] significance,
                         double[] coneOfInfluence) {
        this.power = power;
        this.scales = scales;
        this.periods = periods;
        this.times = times;
        this.significance = significance;
        this.coneOfInfluence = coneOfInfluence;
    }
}
