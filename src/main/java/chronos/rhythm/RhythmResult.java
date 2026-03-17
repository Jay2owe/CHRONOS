package chronos.rhythm;

/**
 * Per-ROI rhythm analysis result holding all fitted parameters.
 */
public class RhythmResult {

    public final String roiName;
    public final double period;
    public final double phaseRad;
    public final double phaseHours;
    public final double amplitude;
    public final double mesor;
    public final double dampingTau;
    public final double rSquared;
    public final double pValue;
    public final boolean isRhythmic;

    /** Optional: raw FFT result for this ROI (may be null). */
    public FFTResult fftResult;

    /** Optional: raw autocorrelation result for this ROI (may be null). */
    public AutocorrResult autocorrResult;

    /** Optional: raw Lomb-Scargle result for this ROI (may be null). */
    public LSResult lsResult;

    /** Optional: cosinor fit result (may be null). */
    public CosinorResult cosinorResult;

    /** Optional: JTK_CYCLE result (may be null). */
    public JTKResult jtkResult;

    public RhythmResult(String roiName, double period, double phaseRad,
                        double phaseHours, double amplitude, double mesor,
                        double dampingTau, double rSquared, double pValue,
                        boolean isRhythmic) {
        this.roiName = roiName;
        this.period = period;
        this.phaseRad = phaseRad;
        this.phaseHours = phaseHours;
        this.amplitude = amplitude;
        this.mesor = mesor;
        this.dampingTau = dampingTau;
        this.rSquared = rSquared;
        this.pValue = pValue;
        this.isRhythmic = isRhythmic;
    }
}
