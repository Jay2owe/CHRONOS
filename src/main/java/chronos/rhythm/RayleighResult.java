package chronos.rhythm;

/**
 * Result from the Rayleigh test for circular uniformity.
 */
public class RayleighResult {

    public final double vectorLength;
    public final double zStatistic;
    public final double pValue;
    public final double meanDirectionRad;
    public final double meanDirectionHours;

    public RayleighResult(double vectorLength, double zStatistic, double pValue,
                          double meanDirectionRad, double meanDirectionHours) {
        this.vectorLength = vectorLength;
        this.zStatistic = zStatistic;
        this.pValue = pValue;
        this.meanDirectionRad = meanDirectionRad;
        this.meanDirectionHours = meanDirectionHours;
    }
}
