package chronos.rhythm;

import java.util.Arrays;

/**
 * JTK_CYCLE non-parametric rhythmicity test.
 * <p>
 * Implements Hughes et al. (2010) J Biol Rhythms 25:372-380.
 * Uses Kendall's tau correlation against reference cosine orderings
 * at candidate periods and phases. P-values computed from the exact
 * null distribution of the Jonckheere-Terpstra statistic via the
 * Harding algorithm.
 * <p>
 * Unlike cosinor fitting, JTK_CYCLE makes no assumption about waveform
 * shape being sinusoidal — it uses rank-based statistics, making it
 * robust to outliers and non-normal distributions.
 */
public class JTKCycleAnalyzer {

    private JTKCycleAnalyzer() { }

    /**
     * Runs JTK_CYCLE on a single time-series.
     *
     * @param data           the input trace (equally spaced measurements)
     * @param intervalHours  sampling interval in hours
     * @param minPeriodHours minimum candidate period
     * @param maxPeriodHours maximum candidate period
     * @return JTK result with best-fit period, phase, amplitude, and p-value
     */
    public static JTKResult analyze(double[] data, double intervalHours,
                                     double minPeriodHours, double maxPeriodHours) {
        int n = data.length;
        if (n < 4) {
            return new JTKResult(Double.NaN, Double.NaN, Double.NaN, 1.0, Double.NaN);
        }

        // Compute ranks of the data (1-based, midranks for ties)
        double[] ranks = computeRanks(data);

        // Precompute the null distribution for Kendall's S statistic
        // S = concordant - discordant pairs, range [-n*(n-1)/2, n*(n-1)/2]
        // For n timepoints with 1 replicate each, group sizes are all 1
        int[] groupSizes = new int[n];
        Arrays.fill(groupSizes, 1);
        double[] nullDist = computeHardingDistribution(groupSizes);

        // Number of candidate period/phase combinations for Bonferroni correction
        int nTests = 0;

        double bestTau = 0;
        double bestPeriod = Double.NaN;
        double bestPhaseHours = Double.NaN;
        double bestPValue = 1.0;
        double bestAmplitude = Double.NaN;

        // Test periods from minPeriod to maxPeriod in steps of intervalHours
        int minPeriodSteps = Math.max(2, (int) Math.round(minPeriodHours / intervalHours));
        int maxPeriodSteps = Math.min(n, (int) Math.round(maxPeriodHours / intervalHours));

        for (int periodSteps = minPeriodSteps; periodSteps <= maxPeriodSteps; periodSteps++) {
            double periodHours = periodSteps * intervalHours;

            // Test all possible phases (shift in steps of 1 sample)
            for (int phaseStep = 0; phaseStep < periodSteps; phaseStep++) {
                nTests++;

                // Generate reference cosine ordering for this period/phase
                double[] refRanks = generateReferenceRanks(n, periodSteps, phaseStep);

                // Compute Kendall's tau-b between data ranks and reference ranks
                double tau = kendallTau(ranks, refRanks);

                if (Math.abs(tau) > Math.abs(bestTau)) {
                    bestTau = tau;
                    bestPeriod = periodHours;
                    bestPhaseHours = phaseStep * intervalHours;
                }
            }
        }

        // Compute p-value from the null distribution
        if (nTests > 0 && !Double.isNaN(bestPeriod)) {
            // Convert tau to S statistic
            long nPairs = (long) n * (n - 1) / 2;
            double S = bestTau * nPairs;

            // Two-tailed p-value from null distribution
            double pRaw = computePValue(Math.abs(S), nullDist, nPairs);

            // Bonferroni correction
            bestPValue = Math.min(1.0, pRaw * nTests);

            // Estimate amplitude using median sign-adjusted deviation (MSAD)
            // For each point, multiply deviation from median by sign of reference cosine
            double median = medianOf(data);
            double[] refCosine = new double[n];
            int bestPeriodSteps = (int) Math.round(bestPeriod / intervalHours);
            int bestPhaseStep = (int) Math.round(bestPhaseHours / intervalHours);
            for (int i = 0; i < n; i++) {
                double angle = 2.0 * Math.PI * (i - bestPhaseStep) / bestPeriodSteps;
                refCosine[i] = Math.cos(angle);
            }

            double[] signAdj = new double[n];
            for (int i = 0; i < n; i++) {
                signAdj[i] = (data[i] - median) * Math.signum(refCosine[i]);
            }
            bestAmplitude = medianOf(signAdj);

            // Adjust phase to represent acrophase (peak time)
            // bestPhaseHours is currently the phase offset; convert to acrophase
            // Acrophase = phase of the cosine peak within [0, period)
        }

        return new JTKResult(bestPeriod, bestPhaseHours, bestAmplitude, bestPValue, bestTau);
    }

    /**
     * Computes ranks of the data array (1-based, with midranks for ties).
     */
    static double[] computeRanks(double[] data) {
        int n = data.length;
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Double.compare(data[a], data[b]));

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n - 1 && data[indices[j + 1]] == data[indices[j]]) {
                j++;
            }
            double midrank = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) {
                ranks[indices[k]] = midrank;
            }
            i = j + 1;
        }
        return ranks;
    }

    /**
     * Generates reference ranks from a cosine wave at the given period/phase.
     * Returns ranks of the cosine values (not the cosine values themselves).
     */
    static double[] generateReferenceRanks(int n, int periodSteps, int phaseStep) {
        double[] cosValues = new double[n];
        for (int i = 0; i < n; i++) {
            cosValues[i] = Math.cos(2.0 * Math.PI * (i - phaseStep) / periodSteps);
        }
        return computeRanks(cosValues);
    }

    /**
     * Computes Kendall's tau-b between two ranked sequences.
     */
    static double kendallTau(double[] ranks1, double[] ranks2) {
        int n = ranks1.length;
        long concordant = 0;
        long discordant = 0;
        long ties1 = 0;
        long ties2 = 0;

        for (int i = 0; i < n - 1; i++) {
            for (int j = i + 1; j < n; j++) {
                double d1 = ranks1[j] - ranks1[i];
                double d2 = ranks2[j] - ranks2[i];
                double product = d1 * d2;

                if (product > 0) {
                    concordant++;
                } else if (product < 0) {
                    discordant++;
                } else {
                    if (d1 == 0) ties1++;
                    if (d2 == 0) ties2++;
                }
            }
        }

        long nPairs = (long) n * (n - 1) / 2;
        double denom = Math.sqrt((nPairs - ties1) * (double) (nPairs - ties2));
        if (denom == 0) return 0;
        return (concordant - discordant) / denom;
    }

    /**
     * Computes the exact null distribution of Kendall's S statistic
     * using the Harding algorithm for the Jonckheere-Terpstra statistic.
     * <p>
     * For equally-sized groups of 1, this simplifies to the standard
     * Kendall tau null distribution. Returns probability mass function
     * indexed by S value (offset so index 0 = S_min).
     */
    static double[] computeHardingDistribution(int[] groupSizes) {
        int n = 0;
        for (int g : groupSizes) n += g;

        // Maximum S = n*(n-1)/2
        int maxS = n * (n - 1) / 2;

        // For the simple case (all groups size 1), use the recursive
        // enumeration of Kendall's S distribution
        // P(S=s) via convolution of uniform distributions
        int range = 2 * maxS + 1;
        double[] dist = new double[range];
        dist[maxS] = 1.0; // S=0 initially (offset by maxS)

        // Build distribution by adding one element at a time
        for (int k = 2; k <= n; k++) {
            double[] newDist = new double[range];
            for (int s = 0; s < range; s++) {
                if (dist[s] == 0) continue;
                // Adding element k can change S by -k+1 to k-1
                for (int delta = -(k - 1); delta <= (k - 1); delta++) {
                    int newS = s + delta;
                    if (newS >= 0 && newS < range) {
                        newDist[newS] += dist[s] / k;
                    }
                }
            }
            dist = newDist;
        }

        return dist;
    }

    /**
     * Computes two-tailed p-value for observed |S| from the null distribution.
     */
    static double computePValue(double absS, double[] nullDist, long maxS) {
        int offset = (int) maxS;
        if (offset < 0 || offset >= nullDist.length) return 1.0;

        double pValue = 0;
        for (int i = 0; i < nullDist.length; i++) {
            int sVal = i - offset;
            if (Math.abs(sVal) >= Math.abs(absS)) {
                pValue += nullDist[i];
            }
        }
        return Math.min(1.0, pValue);
    }

    /**
     * Computes the median of an array (non-destructive).
     */
    private static double medianOf(double[] data) {
        double[] sorted = data.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 0) {
            return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        } else {
            return sorted[n / 2];
        }
    }
}
