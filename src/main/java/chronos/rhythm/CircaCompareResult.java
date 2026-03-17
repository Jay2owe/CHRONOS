package chronos.rhythm;

/**
 * Result of CircaCompare statistical comparison between two circadian groups.
 */
public class CircaCompareResult {

    /** Group 1 mesor. */
    public final double mesor1;
    /** Group 2 mesor. */
    public final double mesor2;
    /** Mesor difference (group2 - group1). */
    public final double mesorDiff;
    /** P-value for mesor difference. */
    public final double pMesor;

    /** Group 1 amplitude. */
    public final double amplitude1;
    /** Group 2 amplitude. */
    public final double amplitude2;
    /** Amplitude difference. */
    public final double amplitudeDiff;
    /** P-value for amplitude difference. */
    public final double pAmplitude;

    /** Group 1 acrophase (hours). */
    public final double phase1Hours;
    /** Group 2 acrophase (hours). */
    public final double phase2Hours;
    /** Phase difference (hours). */
    public final double phaseDiffHours;
    /** P-value for phase difference. */
    public final double pPhase;

    /** Whether group 1 is rhythmic on its own. */
    public final boolean group1Rhythmic;
    /** Whether group 2 is rhythmic on its own. */
    public final boolean group2Rhythmic;

    public CircaCompareResult(double mesor1, double mesor2, double mesorDiff, double pMesor,
                               double amplitude1, double amplitude2, double amplitudeDiff, double pAmplitude,
                               double phase1Hours, double phase2Hours, double phaseDiffHours, double pPhase,
                               boolean group1Rhythmic, boolean group2Rhythmic) {
        this.mesor1 = mesor1;
        this.mesor2 = mesor2;
        this.mesorDiff = mesorDiff;
        this.pMesor = pMesor;
        this.amplitude1 = amplitude1;
        this.amplitude2 = amplitude2;
        this.amplitudeDiff = amplitudeDiff;
        this.pAmplitude = pAmplitude;
        this.phase1Hours = phase1Hours;
        this.phase2Hours = phase2Hours;
        this.phaseDiffHours = phaseDiffHours;
        this.pPhase = pPhase;
        this.group1Rhythmic = group1Rhythmic;
        this.group2Rhythmic = group2Rhythmic;
    }
}
