package chronos.rhythm;

/**
 * Result from FFT-based period estimation.
 */
public class FFTResult {

    public final double dominantPeriod;
    public final double peakPower;
    public final double[] power;
    public final double[] frequencies;

    public FFTResult(double dominantPeriod, double peakPower,
                     double[] power, double[] frequencies) {
        this.dominantPeriod = dominantPeriod;
        this.peakPower = peakPower;
        this.power = power;
        this.frequencies = frequencies;
    }
}
