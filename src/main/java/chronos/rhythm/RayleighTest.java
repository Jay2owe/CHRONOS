package chronos.rhythm;

/**
 * Rayleigh test for circular uniformity.
 * <p>
 * Tests whether a set of phase angles are uniformly distributed around
 * the circle or show significant clustering (directionality).
 * Uses the Mardia approximation for the p-value.
 */
public class RayleighTest {

    private RayleighTest() { }

    /**
     * Performs the Rayleigh test on a set of circular phase values.
     *
     * @param phasesRad array of phase angles in radians
     * @return RayleighResult with vector length R, Z statistic, p-value, and mean direction
     */
    public static RayleighResult test(double[] phasesRad) {
        int n = phasesRad.length;
        if (n == 0) {
            return new RayleighResult(0, 0, 1.0, Double.NaN, Double.NaN);
        }

        // Compute sum of cos and sin
        double cosSum = 0;
        double sinSum = 0;
        for (int i = 0; i < n; i++) {
            cosSum += Math.cos(phasesRad[i]);
            sinSum += Math.sin(phasesRad[i]);
        }

        // Mean resultant length R
        double R = Math.sqrt(cosSum * cosSum + sinSum * sinSum) / n;

        // Rayleigh Z statistic
        double Z = n * R * R;

        // P-value using Mardia approximation for small samples
        // p = exp(-Z) * (1 + (2Z - Z^2)/(4n) - (24Z - 132Z^2 + 76Z^3 - 9Z^4)/(288n^2))
        double pValue;
        double Z2 = Z * Z;
        double Z3 = Z2 * Z;
        double Z4 = Z3 * Z;
        double n2 = (double) n * n;

        pValue = Math.exp(-Z) * (1.0
                + (2.0 * Z - Z2) / (4.0 * n)
                - (24.0 * Z - 132.0 * Z2 + 76.0 * Z3 - 9.0 * Z4) / (288.0 * n2));

        // Clamp to [0, 1]
        if (pValue < 0) pValue = 0;
        if (pValue > 1) pValue = 1.0;

        // Mean direction
        double meanDirectionRad = Math.atan2(sinSum, cosSum);
        if (meanDirectionRad < 0) meanDirectionRad += 2.0 * Math.PI;

        // Convert to hours (assuming phase in [0, 2*pi) maps to [0, 24) hours)
        double meanDirectionHours = meanDirectionRad * 24.0 / (2.0 * Math.PI);

        return new RayleighResult(R, Z, pValue, meanDirectionRad, meanDirectionHours);
    }
}
