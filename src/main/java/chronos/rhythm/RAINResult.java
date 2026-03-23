package chronos.rhythm;

/**
 * Result from RAIN (Rhythmicity Analysis Incorporating Non-parametric methods).
 * Detects asymmetric waveforms that JTK_CYCLE and cosinor miss.
 */
public class RAINResult {

    /** Estimated period in hours. */
    public final double period;

    /** Phase of peak (hours from recording start). */
    public final double peakPhaseHours;

    /** Relative position of the peak within the period (0-1). 0.5 = symmetric. */
    public final double peakShape;

    /** P-value for rhythmicity (Bonferroni-corrected). */
    public final double pValue;

    /** Best umbrella statistic across all period/peak combinations. */
    public final double statistic;

    public RAINResult(double period, double peakPhaseHours, double peakShape,
                      double pValue, double statistic) {
        this.period = period;
        this.peakPhaseHours = peakPhaseHours;
        this.peakShape = peakShape;
        this.pValue = pValue;
        this.statistic = statistic;
    }
}
