package chronos.rhythm;

/**
 * Result from JTK_CYCLE non-parametric rhythmicity test.
 */
public class JTKResult {

    /** Estimated period in hours. */
    public final double period;

    /** Phase (acrophase) in hours from the start of the recording. */
    public final double phaseHours;

    /** Amplitude estimated by median sign-adjusted deviation (MSAD). */
    public final double amplitude;

    /** Bonferroni-corrected p-value for rhythmicity. */
    public final double pValue;

    /** Kendall's tau correlation coefficient (best across all period/phase combos). */
    public final double tau;

    public JTKResult(double period, double phaseHours, double amplitude,
                     double pValue, double tau) {
        this.period = period;
        this.phaseHours = phaseHours;
        this.amplitude = amplitude;
        this.pValue = pValue;
        this.tau = tau;
    }
}
