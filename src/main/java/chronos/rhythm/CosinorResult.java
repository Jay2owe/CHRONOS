package chronos.rhythm;

/**
 * Result from cosinor curve fitting.
 */
public class CosinorResult {

    public final double mesor;
    public final double amplitude;
    public final double period;
    public final double acrophaseRad;
    public final double acrophaseHours;
    public final double dampingTau;
    public final double rSquared;
    public final double pValue;
    public final double[] fittedValues;

    public CosinorResult(double mesor, double amplitude, double period,
                         double acrophaseRad, double acrophaseHours,
                         double dampingTau, double rSquared, double pValue,
                         double[] fittedValues) {
        this.mesor = mesor;
        this.amplitude = amplitude;
        this.period = period;
        this.acrophaseRad = acrophaseRad;
        this.acrophaseHours = acrophaseHours;
        this.dampingTau = dampingTau;
        this.rSquared = rSquared;
        this.pValue = pValue;
        this.fittedValues = fittedValues;
    }
}
