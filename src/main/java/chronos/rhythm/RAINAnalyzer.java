package chronos.rhythm;

import java.util.Arrays;

/**
 * RAIN — Rhythmicity Analysis Incorporating Non-parametric methods.
 * <p>
 * Implements Thaben & Westermark (2014) J Biol Rhythms 29:391-400.
 * Uses the Mack-Wolfe umbrella test to detect rhythms with arbitrary
 * waveform shapes (asymmetric rise/fall). Unlike JTK_CYCLE, RAIN does
 * not assume symmetric waveforms, making it especially powerful for
 * detecting circadian rhythms in microglia morphology, immune markers,
 * and other signals with steep onset / slow decay.
 * <p>
 * Algorithm:
 * 1. For each candidate period, fold the data into one cycle
 * 2. For each possible peak position within the period:
 *    a. Compute Jonckheere-Terpstra (JT) statistic for ascending phase
 *    b. Compute JT statistic for descending phase
 *    c. Combine as umbrella statistic U = JT_up + JT_down
 * 3. Best peak position maximises U
 * 4. P-value from normal approximation of U under H0
 * 5. Bonferroni correction across all tested periods
 */
public class RAINAnalyzer {

    private RAINAnalyzer() {}

    /**
     * Run RAIN on a single time-series.
     *
     * @param data           equally spaced measurements
     * @param intervalHours  sampling interval in hours
     * @param minPeriodHours minimum candidate period
     * @param maxPeriodHours maximum candidate period
     * @return RAINResult with best period, peak shape, and p-value
     */
    public static RAINResult analyze(double[] data, double intervalHours,
                                      double minPeriodHours, double maxPeriodHours) {
        int n = data.length;
        if (n < 6) {
            return new RAINResult(Double.NaN, Double.NaN, 0.5, 1.0, 0);
        }

        int minPeriodSteps = Math.max(4, (int) Math.round(minPeriodHours / intervalHours));
        int maxPeriodSteps = Math.min(n, (int) Math.round(maxPeriodHours / intervalHours));

        double bestStat = Double.NEGATIVE_INFINITY;
        double bestPValue = 1.0;
        int bestPeriodSteps = minPeriodSteps;
        int bestPeakStep = 0;
        int nTests = 0;

        for (int periodSteps = minPeriodSteps; periodSteps <= maxPeriodSteps; periodSteps++) {
            // For each possible peak position within the period
            for (int peakStep = 0; peakStep < periodSteps; peakStep++) {
                nTests++;

                // Fold data into groups by phase within the period
                // Group i contains all data points at phase i (mod periodSteps)
                double[][] groups = foldData(data, periodSteps);

                // Reorder groups so that the peak is at the umbrella apex
                // Rising: groups[0..peakStep], Falling: groups[peakStep..periodSteps-1]
                double[][] risingGroups = new double[peakStep + 1][];
                for (int i = 0; i <= peakStep; i++) {
                    risingGroups[i] = groups[i];
                }

                int fallingLen = periodSteps - peakStep;
                double[][] fallingGroups = new double[fallingLen][];
                for (int i = 0; i < fallingLen; i++) {
                    // Reverse order for descending test
                    fallingGroups[i] = groups[peakStep + i];
                }
                // Reverse falling groups so JT tests for decreasing trend
                reverseArray(fallingGroups);

                // Compute JT statistics
                double jtUp = jonckheere(risingGroups);
                double jtDown = jonckheere(fallingGroups);
                double umbrella = jtUp + jtDown;

                // Normal approximation for p-value
                double[] upMuSigma = jtMeanVariance(risingGroups);
                double[] downMuSigma = jtMeanVariance(fallingGroups);
                double mu = upMuSigma[0] + downMuSigma[0];
                double sigma = Math.sqrt(upMuSigma[1] + downMuSigma[1]);

                double pValue = 1.0;
                if (sigma > 0) {
                    double z = (umbrella - mu) / sigma;
                    pValue = normalSurvival(z);
                }

                if (umbrella > bestStat) {
                    bestStat = umbrella;
                    bestPValue = pValue;
                    bestPeriodSteps = periodSteps;
                    bestPeakStep = peakStep;
                }
            }
        }

        // Bonferroni correction
        if (nTests > 0) {
            bestPValue = Math.min(1.0, bestPValue * nTests);
        }

        double bestPeriodHours = bestPeriodSteps * intervalHours;
        double peakPhaseHours = bestPeakStep * intervalHours;
        double peakShape = (double) bestPeakStep / bestPeriodSteps; // 0-1, 0.5=symmetric

        return new RAINResult(bestPeriodHours, peakPhaseHours, peakShape, bestPValue, bestStat);
    }

    /**
     * Fold time-series data into groups by phase within a period.
     * Group i contains all data points at positions i, i+period, i+2*period, etc.
     */
    private static double[][] foldData(double[] data, int periodSteps) {
        int n = data.length;
        double[][] groups = new double[periodSteps][];

        for (int phase = 0; phase < periodSteps; phase++) {
            int count = 0;
            for (int j = phase; j < n; j += periodSteps) count++;
            groups[phase] = new double[count];
            int idx = 0;
            for (int j = phase; j < n; j += periodSteps) {
                groups[phase][idx++] = data[j];
            }
        }
        return groups;
    }

    /**
     * Jonckheere-Terpstra statistic: counts pairs (i,j) where i < j
     * and observation in group i < observation in group j.
     * Tests for an ordered alternative (increasing trend across groups).
     */
    private static double jonckheere(double[][] groups) {
        int k = groups.length;
        double jt = 0;
        for (int i = 0; i < k - 1; i++) {
            for (int j = i + 1; j < k; j++) {
                jt += mannWhitneyU(groups[i], groups[j]);
            }
        }
        return jt;
    }

    /**
     * Mann-Whitney U statistic: count of pairs where x[a] < y[b],
     * with 0.5 for ties.
     */
    private static double mannWhitneyU(double[] x, double[] y) {
        double u = 0;
        for (double xi : x) {
            for (double yj : y) {
                if (xi < yj) u += 1.0;
                else if (xi == yj) u += 0.5;
            }
        }
        return u;
    }

    /**
     * Mean and variance of the JT statistic under the null hypothesis
     * (no trend). Uses the standard formulas for the JT distribution.
     */
    private static double[] jtMeanVariance(double[][] groups) {
        int k = groups.length;
        int N = 0;
        for (double[] g : groups) N += g.length;

        // Mean = (N^2 - sum(ni^2)) / 4
        long sumNiSq = 0;
        for (double[] g : groups) sumNiSq += (long) g.length * g.length;
        double mu = ((long) N * N - sumNiSq) / 4.0;

        // Variance (no ties formula):
        // sigma^2 = [N^2(2N+3) - sum(ni^2(2ni+3))] / 72
        long a = (long) N * N * (2L * N + 3);
        long b = 0;
        for (double[] g : groups) {
            long ni = g.length;
            b += ni * ni * (2 * ni + 3);
        }
        double variance = (a - b) / 72.0;

        return new double[]{mu, Math.max(0, variance)};
    }

    /** Reverse an array in place. */
    private static void reverseArray(double[][] arr) {
        int n = arr.length;
        for (int i = 0; i < n / 2; i++) {
            double[] tmp = arr[i];
            arr[i] = arr[n - 1 - i];
            arr[n - 1 - i] = tmp;
        }
    }

    /**
     * Upper tail probability of the standard normal distribution.
     * Uses rational approximation (Abramowitz & Stegun 26.2.17).
     */
    private static double normalSurvival(double z) {
        if (z < -8) return 1.0;
        if (z > 8) return 0.0;
        if (z < 0) return 1.0 - normalSurvival(-z);

        double t = 1.0 / (1.0 + 0.2316419 * z);
        double poly = t * (0.319381530 + t * (-0.356563782 +
                t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double pdf = Math.exp(-0.5 * z * z) / Math.sqrt(2.0 * Math.PI);
        return pdf * poly;
    }
}
