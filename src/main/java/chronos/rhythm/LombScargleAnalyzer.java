package chronos.rhythm;

/**
 * Standard Lomb-Scargle periodogram for unevenly-sampled data.
 * <p>
 * Also works on evenly-sampled data; does not assume uniform spacing.
 * Significance testing uses the exponential null distribution for white noise.
 */
public class LombScargleAnalyzer {

    private LombScargleAnalyzer() { }

    /**
     * Computes the Lomb-Scargle periodogram.
     *
     * @param times      observation times (hours)
     * @param values     observed values (should be detrended / mean-subtracted)
     * @param minPeriodH minimum period to test (hours)
     * @param maxPeriodH maximum period to test (hours)
     * @param numFreqs   number of frequency bins to evaluate
     * @return LSResult with dominant period, power, frequencies, and significance levels
     */
    public static LSResult analyze(double[] times, double[] values,
                                   double minPeriodH, double maxPeriodH,
                                   int numFreqs) {
        int n = values.length;

        // Subtract mean
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += values[i];
        }
        double mean = sum / n;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = values[i] - mean;
        }

        // Compute variance
        double varSum = 0;
        for (int i = 0; i < n; i++) {
            varSum += y[i] * y[i];
        }
        double variance = varSum / n;

        // Frequency grid: from 1/maxPeriod to 1/minPeriod
        double fMin = 1.0 / maxPeriodH;
        double fMax = 1.0 / minPeriodH;
        double[] frequencies = new double[numFreqs];
        double[] power = new double[numFreqs];
        double df = (fMax - fMin) / (numFreqs - 1);

        for (int fi = 0; fi < numFreqs; fi++) {
            frequencies[fi] = fMin + fi * df;
        }

        // Compute LS power for each frequency
        for (int fi = 0; fi < numFreqs; fi++) {
            double omega = 2.0 * Math.PI * frequencies[fi];

            // Compute tau (time offset for Lomb-Scargle)
            double sin2Sum = 0;
            double cos2Sum = 0;
            for (int i = 0; i < n; i++) {
                double arg = 2.0 * omega * times[i];
                sin2Sum += Math.sin(arg);
                cos2Sum += Math.cos(arg);
            }
            double tau = Math.atan2(sin2Sum, cos2Sum) / (2.0 * omega);

            // Compute power
            double cosSum = 0, sinSum = 0;
            double cos2 = 0, sin2 = 0;

            for (int i = 0; i < n; i++) {
                double phase = omega * (times[i] - tau);
                double cosVal = Math.cos(phase);
                double sinVal = Math.sin(phase);

                cosSum += y[i] * cosVal;
                sinSum += y[i] * sinVal;
                cos2 += cosVal * cosVal;
                sin2 += sinVal * sinVal;
            }

            double p = 0;
            if (variance > 0) {
                double term1 = (cos2 > 0) ? (cosSum * cosSum) / cos2 : 0;
                double term2 = (sin2 > 0) ? (sinSum * sinSum) / sin2 : 0;
                p = (term1 + term2) / (2.0 * variance);
            }
            power[fi] = p;
        }

        // Find peak
        double peakPower = -1;
        int peakIdx = 0;
        for (int fi = 0; fi < numFreqs; fi++) {
            if (power[fi] > peakPower) {
                peakPower = power[fi];
                peakIdx = fi;
            }
        }
        double dominantPeriod = 1.0 / frequencies[peakIdx];

        // Significance levels: probability of exceeding power P under null
        // p-value = 1 - (1 - exp(-P))^M where M ~ effective number of independent freqs
        double M = numFreqs; // conservative estimate
        double[] significance = new double[numFreqs];
        for (int fi = 0; fi < numFreqs; fi++) {
            double prob = Math.exp(-power[fi]);
            double pVal = 1.0 - Math.pow(1.0 - prob, M);
            significance[fi] = Math.max(0, Math.min(1.0, pVal));
        }

        return new LSResult(dominantPeriod, peakPower, power, frequencies, significance);
    }
}
