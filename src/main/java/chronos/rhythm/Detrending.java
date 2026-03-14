package chronos.rhythm;

import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;

/**
 * Static methods for removing trends from time-series data prior to
 * spectral or cosinor analysis.
 */
public class Detrending {

    private Detrending() { }

    /**
     * Returns a copy of the trace with no detrending applied.
     */
    public static double[] none(double[] trace) {
        double[] out = new double[trace.length];
        System.arraycopy(trace, 0, out, 0, trace.length);
        return out;
    }

    /**
     * Subtracts a linear (degree-1) trend from the trace.
     */
    public static double[] linear(double[] trace) {
        return polynomial(trace, 1);
    }

    /**
     * Subtracts a polynomial trend of given degree from the trace.
     * Uses Apache Commons Math PolynomialCurveFitter.
     *
     * @param trace  the input time-series
     * @param degree polynomial degree (1 = linear, 2 = quadratic, 3 = cubic)
     * @return detrended copy of the trace
     */
    public static double[] polynomial(double[] trace, int degree) {
        int n = trace.length;
        if (n < degree + 1) {
            return none(trace);
        }

        // Build observation points (x = index, y = value)
        WeightedObservedPoints obs = new WeightedObservedPoints();
        for (int i = 0; i < n; i++) {
            obs.add(i, trace[i]);
        }

        // Fit polynomial
        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(degree);
        double[] coeffs = fitter.fit(obs.toList());

        // Subtract the polynomial trend
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double trend = 0;
            double xi = 1.0;
            for (int c = 0; c < coeffs.length; c++) {
                trend += coeffs[c] * xi;
                xi *= i;
            }
            out[i] = trace[i] - trend;
        }
        return out;
    }

    /**
     * Convenience method that selects the detrending approach by name.
     *
     * @param trace  the input time-series
     * @param method one of "None", "Linear", "Quadratic", "Cubic"
     * @return detrended copy
     */
    public static double[] detrend(double[] trace, String method) {
        if ("Linear".equals(method)) {
            return linear(trace);
        } else if ("Quadratic".equals(method)) {
            return polynomial(trace, 2);
        } else if ("Cubic".equals(method)) {
            return polynomial(trace, 3);
        } else {
            return none(trace);
        }
    }
}
