package chronos.extraction;

import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import java.util.Arrays;

/**
 * Computes baseline fluorescence (F0) using various methods for dF/F calculation.
 */
public class BaselineCalculator {

    /**
     * Sliding percentile baseline. Returns an F0 value for each timepoint.
     *
     * @param trace      raw intensity trace
     * @param window     sliding window size in frames (centered)
     * @param percentile percentile value (0-100), e.g. 8 for 8th percentile
     * @return double[nFrames] baseline values
     */
    public static double[] slidingPercentile(double[] trace, int window, double percentile) {
        int n = trace.length;
        double[] baseline = new double[n];
        int halfWin = window / 2;

        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - halfWin);
            int end = Math.min(n, i + halfWin + 1);
            int len = end - start;

            double[] windowValues = new double[len];
            System.arraycopy(trace, start, windowValues, 0, len);
            Arrays.sort(windowValues);

            // Percentile index (0-based)
            double idx = (percentile / 100.0) * (len - 1);
            int lower = (int) Math.floor(idx);
            int upper = Math.min(lower + 1, len - 1);
            double frac = idx - lower;
            baseline[i] = windowValues[lower] * (1.0 - frac) + windowValues[upper] * frac;
        }

        return baseline;
    }

    /**
     * Mean of the first N frames as a single F0 value.
     *
     * @param trace raw intensity trace
     * @param n     number of frames to average
     * @return single F0 value
     */
    public static double firstNFramesMean(double[] trace, int n) {
        if (trace.length == 0) return 0;
        int count = Math.min(n, trace.length);
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += trace[i];
        }
        return sum / count;
    }

    /**
     * Mean of the whole trace as F0.
     */
    public static double wholeTraceMean(double[] trace) {
        if (trace.length == 0) return 0;
        double sum = 0;
        for (int i = 0; i < trace.length; i++) {
            sum += trace[i];
        }
        return sum / trace.length;
    }

    /**
     * Median of the whole trace as F0.
     */
    public static double wholeTraceMedian(double[] trace) {
        if (trace.length == 0) return 0;
        double[] sorted = new double[trace.length];
        System.arraycopy(trace, 0, sorted, 0, trace.length);
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 0) {
            return (sorted[mid - 1] + sorted[mid]) / 2.0;
        }
        return sorted[mid];
    }

    /**
     * Mono-exponential fit baseline. Returns fitted values for each timepoint.
     * Fits: F(t) = A * exp(-t/tau) + C
     *
     * @param trace raw intensity trace
     * @return double[nFrames] fitted baseline values
     */
    public static double[] exponentialFit(double[] trace) {
        final int n = trace.length;
        if (n < 3) {
            // Not enough points for fitting, return mean
            double mean = wholeTraceMean(trace);
            double[] result = new double[n];
            Arrays.fill(result, mean);
            return result;
        }

        // Initial parameter guesses: A, tau, C
        // A = first - last, tau = n/3, C = last value
        final double aInit = trace[0] - trace[n - 1];
        final double tauInit = n / 3.0;
        final double cInit = trace[n - 1];

        final double[] times = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = i;
        }

        MultivariateJacobianFunction model = new MultivariateJacobianFunction() {
            @Override
            public Pair<RealVector, RealMatrix> value(RealVector params) {
                double a = params.getEntry(0);
                double tau = params.getEntry(1);
                double c = params.getEntry(2);

                // Prevent division by zero
                if (Math.abs(tau) < 1e-10) tau = 1e-10;

                RealVector values = new ArrayRealVector(n);
                RealMatrix jacobian = new Array2DRowRealMatrix(n, 3);

                for (int i = 0; i < n; i++) {
                    double t = times[i];
                    double expVal = Math.exp(-t / tau);
                    values.setEntry(i, a * expVal + c);

                    // Partial derivatives
                    jacobian.setEntry(i, 0, expVal);           // dF/dA
                    jacobian.setEntry(i, 1, a * t * expVal / (tau * tau)); // dF/dtau
                    jacobian.setEntry(i, 2, 1.0);              // dF/dC
                }

                return new Pair<RealVector, RealMatrix>(values, jacobian);
            }
        };

        try {
            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(new double[]{aInit, tauInit, cInit})
                    .model(model)
                    .target(trace)
                    .maxEvaluations(1000)
                    .maxIterations(500)
                    .build();

            LevenbergMarquardtOptimizer optimizer = new LevenbergMarquardtOptimizer();
            double[] fitted = optimizer.optimize(problem).getPoint().toArray();

            double a = fitted[0];
            double tau = fitted[1];
            double c = fitted[2];

            double[] baseline = new double[n];
            for (int i = 0; i < n; i++) {
                baseline[i] = a * Math.exp(-times[i] / tau) + c;
            }
            return baseline;
        } catch (Exception e) {
            // If fitting fails, return whole-trace mean
            double mean = wholeTraceMean(trace);
            double[] result = new double[n];
            Arrays.fill(result, mean);
            return result;
        }
    }
}
