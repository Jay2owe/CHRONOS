package chronos.rhythm;

/**
 * Autocorrelation-based period estimation.
 * <p>
 * Algorithm:
 * 1. Normalize trace (subtract mean, divide by std)
 * 2. Compute autocorrelation for lags 0 to N/2
 * 3. Find first peak in circadian range
 * 4. Rhythmicity index = height of second peak (~2*period lag)
 */
public class AutocorrelationAnalyzer {

    private AutocorrelationAnalyzer() { }

    /**
     * Performs autocorrelation-based period estimation.
     *
     * @param trace              the detrended time-series values
     * @param frameIntervalHours time between samples in hours
     * @param minPeriodH         minimum period to search (hours)
     * @param maxPeriodH         maximum period to search (hours)
     * @return AutocorrResult with estimated period, rhythmicity index, and autocorrelation values
     */
    public static AutocorrResult analyze(double[] trace, double frameIntervalHours,
                                         double minPeriodH, double maxPeriodH) {
        int n = trace.length;
        int maxLag = n / 2;

        // Normalize: subtract mean, divide by std
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += trace[i];
        }
        double mean = sum / n;

        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            double d = trace[i] - mean;
            sumSq += d * d;
        }
        double variance = sumSq / n;
        double std = Math.sqrt(variance);

        double[] normalized = new double[n];
        for (int i = 0; i < n; i++) {
            normalized[i] = (std > 0) ? (trace[i] - mean) / std : 0;
        }

        // Compute autocorrelation
        double[] acf = new double[maxLag + 1];
        double[] lags = new double[maxLag + 1];

        for (int lag = 0; lag <= maxLag; lag++) {
            lags[lag] = lag * frameIntervalHours;
            double s = 0;
            int count = n - lag;
            for (int i = 0; i < count; i++) {
                s += normalized[i] * normalized[i + lag];
            }
            acf[lag] = s / n; // Biased estimator (divides by n, not n-lag)
        }

        // Find peaks in circadian range
        int minLag = Math.max(1, (int) Math.floor(minPeriodH / frameIntervalHours));
        int maxLagSearch = Math.min(maxLag, (int) Math.ceil(maxPeriodH / frameIntervalHours));

        // Find first peak (local maximum) in circadian range
        int firstPeakLag = -1;
        double firstPeakVal = -Double.MAX_VALUE;

        for (int lag = minLag; lag <= maxLagSearch; lag++) {
            // Check for local maximum (must be higher than neighbours)
            if (lag > 0 && lag < maxLag) {
                if (acf[lag] > acf[lag - 1] && acf[lag] >= acf[lag + 1] && acf[lag] > 0) {
                    if (acf[lag] > firstPeakVal) {
                        firstPeakVal = acf[lag];
                        firstPeakLag = lag;
                    }
                }
            }
        }

        double estimatedPeriod;
        if (firstPeakLag > 0) {
            estimatedPeriod = firstPeakLag * frameIntervalHours;
        } else {
            // Fallback: no clear peak found
            estimatedPeriod = Double.NaN;
        }

        // Rhythmicity index: height of the second peak (~2 * estimated period)
        double rhythmicityIndex = 0;
        if (firstPeakLag > 0) {
            int secondPeakCenter = firstPeakLag * 2;
            int searchStart = Math.max(minLag, secondPeakCenter - firstPeakLag / 2);
            int searchEnd = Math.min(maxLag, secondPeakCenter + firstPeakLag / 2);

            for (int lag = searchStart; lag <= searchEnd; lag++) {
                if (lag > 0 && lag < maxLag) {
                    if (acf[lag] > acf[lag - 1] && acf[lag] >= acf[lag + 1]) {
                        if (acf[lag] > rhythmicityIndex) {
                            rhythmicityIndex = acf[lag];
                        }
                    }
                }
            }
        }

        return new AutocorrResult(estimatedPeriod, rhythmicityIndex, acf, lags);
    }
}
