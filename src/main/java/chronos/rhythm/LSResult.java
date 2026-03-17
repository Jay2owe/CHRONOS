package chronos.rhythm;

/**
 * Result from Lomb-Scargle periodogram analysis.
 */
public class LSResult {

    public final double dominantPeriod;
    public final double peakPower;
    public final double[] power;
    public final double[] frequencies;
    public final double[] significanceLevel;

    public LSResult(double dominantPeriod, double peakPower,
                    double[] power, double[] frequencies,
                    double[] significanceLevel) {
        this.dominantPeriod = dominantPeriod;
        this.peakPower = peakPower;
        this.power = power;
        this.frequencies = frequencies;
        this.significanceLevel = significanceLevel;
    }
}
