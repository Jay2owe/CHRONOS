package chronos.rhythm;

/**
 * Extracts the ridge (path of maximum power) from a wavelet scalogram.
 * <p>
 * For each timepoint, finds the scale with maximum power to determine
 * the instantaneous period and amplitude. Optionally applies a penalty
 * for large period jumps between adjacent timepoints to smooth the ridge.
 * <p>
 * Instantaneous phase is estimated from the wavelet coefficients using
 * the relationship between consecutive timepoints along the ridge.
 */
public class WaveletRidgeExtractor {

    private WaveletRidgeExtractor() { }

    /**
     * Extracts the ridge from a wavelet power scalogram.
     *
     * @param power       wavelet power spectrum [nScales][nTimePoints]
     * @param scales      wavelet scales array
     * @param periods     Fourier periods corresponding to each scale (hours)
     * @param nTimePoints number of timepoints in the original signal
     * @return RidgeResult with instantaneous period, amplitude, phase, and scale indices
     */
    public static RidgeResult extractRidge(double[][] power, double[] scales,
                                           double[] periods, int nTimePoints) {
        int nScales = power.length;

        int[] ridgeIdx = new int[nTimePoints];
        double[] instantPeriod = new double[nTimePoints];
        double[] instantAmplitude = new double[nTimePoints];
        double[] instantPhase = new double[nTimePoints];

        // Step 1: Simple maximum power ridge
        for (int t = 0; t < nTimePoints; t++) {
            double maxPow = -1;
            int maxIdx = 0;
            for (int j = 0; j < nScales; j++) {
                if (power[j][t] > maxPow) {
                    maxPow = power[j][t];
                    maxIdx = j;
                }
            }
            ridgeIdx[t] = maxIdx;
            instantPeriod[t] = periods[maxIdx];
            instantAmplitude[t] = Math.sqrt(maxPow);
        }

        // Step 2: Smooth the ridge using dynamic programming with jump penalty.
        // Cost = -log(power) + lambda * |period_change|^2
        // This discourages large jumps while still following high-power regions.
        double lambda = 0.5; // penalty weight for period jumps
        smoothRidge(power, periods, ridgeIdx, instantPeriod, instantAmplitude, nScales, nTimePoints, lambda);

        // Step 3: Estimate instantaneous phase from amplitude modulation.
        // Use the Hilbert-transform-like approach: phase advances proportionally
        // to 2*pi*dt/period at each timepoint.
        // This is an approximation since we don't have the full complex coefficients here.
        estimatePhase(instantPeriod, instantPhase, nTimePoints);

        return new RidgeResult(instantPeriod, instantAmplitude, instantPhase, ridgeIdx);
    }

    /**
     * Smooths the ridge using forward-backward dynamic programming.
     * Minimizes: sum_t [ -log(power[j][t]) + lambda * (period[j] - period[j_prev])^2 ]
     */
    private static void smoothRidge(double[][] power, double[] periods,
                                    int[] ridgeIdx, double[] instantPeriod,
                                    double[] instantAmplitude,
                                    int nScales, int nTimePoints, double lambda) {
        if (nTimePoints < 3) return;

        // Forward pass: compute cumulative cost
        double[][] cost = new double[nTimePoints][nScales];
        int[][] backPtr = new int[nTimePoints][nScales];

        // Initialize first timepoint
        for (int j = 0; j < nScales; j++) {
            double p = power[0][j];
            cost[0][j] = (p > 0) ? -Math.log(p) : 1e10;
            backPtr[0][j] = j;
        }

        // Forward pass
        for (int t = 1; t < nTimePoints; t++) {
            for (int j = 0; j < nScales; j++) {
                double localCost = (power[j][t] > 0) ? -Math.log(power[j][t]) : 1e10;
                double bestPrev = Double.MAX_VALUE;
                int bestIdx = 0;

                // Search a window around the current scale to limit computation
                int searchRadius = Math.max(5, nScales / 4);
                int jStart = Math.max(0, j - searchRadius);
                int jEnd = Math.min(nScales - 1, j + searchRadius);

                for (int jp = jStart; jp <= jEnd; jp++) {
                    double dp = periods[j] - periods[jp];
                    double jumpCost = lambda * dp * dp;
                    double totalCost = cost[t - 1][jp] + jumpCost;
                    if (totalCost < bestPrev) {
                        bestPrev = totalCost;
                        bestIdx = jp;
                    }
                }

                cost[t][j] = bestPrev + localCost;
                backPtr[t][j] = bestIdx;
            }
        }

        // Find best ending scale
        double bestFinalCost = Double.MAX_VALUE;
        int bestFinalIdx = 0;
        for (int j = 0; j < nScales; j++) {
            if (cost[nTimePoints - 1][j] < bestFinalCost) {
                bestFinalCost = cost[nTimePoints - 1][j];
                bestFinalIdx = j;
            }
        }

        // Backtrace
        ridgeIdx[nTimePoints - 1] = bestFinalIdx;
        for (int t = nTimePoints - 2; t >= 0; t--) {
            ridgeIdx[t] = backPtr[t + 1][ridgeIdx[t + 1]];
        }

        // Update period and amplitude from smoothed ridge
        for (int t = 0; t < nTimePoints; t++) {
            int j = ridgeIdx[t];
            instantPeriod[t] = periods[j];
            instantAmplitude[t] = Math.sqrt(power[j][t]);
        }
    }

    /**
     * Estimates instantaneous phase by integrating the instantaneous frequency
     * (2*pi / instantaneous period) along the ridge.
     */
    private static void estimatePhase(double[] instantPeriod, double[] instantPhase,
                                      int nTimePoints) {
        if (nTimePoints == 0) return;

        instantPhase[0] = 0;
        for (int t = 1; t < nTimePoints; t++) {
            // Average period between consecutive points for trapezoidal integration
            double avgPeriod = (instantPeriod[t] + instantPeriod[t - 1]) / 2.0;
            if (avgPeriod > 0) {
                instantPhase[t] = instantPhase[t - 1] + 2.0 * Math.PI / avgPeriod;
            } else {
                instantPhase[t] = instantPhase[t - 1];
            }
            // Wrap to [-pi, pi]
            instantPhase[t] = wrapPhase(instantPhase[t]);
        }
    }

    /**
     * Wraps a phase angle to the range [-pi, pi].
     */
    private static double wrapPhase(double phase) {
        while (phase > Math.PI) phase -= 2.0 * Math.PI;
        while (phase < -Math.PI) phase += 2.0 * Math.PI;
        return phase;
    }
}
