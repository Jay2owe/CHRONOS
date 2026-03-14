package chronos.rhythm;

/**
 * Result from autocorrelation-based period estimation.
 */
public class AutocorrResult {

    public final double estimatedPeriod;
    public final double rhythmicityIndex;
    public final double[] autocorrValues;
    public final double[] lags;

    public AutocorrResult(double estimatedPeriod, double rhythmicityIndex,
                          double[] autocorrValues, double[] lags) {
        this.estimatedPeriod = estimatedPeriod;
        this.rhythmicityIndex = rhythmicityIndex;
        this.autocorrValues = autocorrValues;
        this.lags = lags;
    }
}
