package chronos.rhythm;

import org.apache.commons.math3.analysis.MultivariateMatrixFunction;
import org.apache.commons.math3.analysis.MultivariateVectorFunction;
import org.apache.commons.math3.distribution.FDistribution;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer;
import org.apache.commons.math3.linear.RealVector;

/**
 * Cosinor curve fitting for circadian time-series.
 * <p>
 * Standard model:  Y = M + A * cos(2*pi*t/T - phi)
 * Damped model:    Y = M + A * exp(-lambda*t) * cos(2*pi*t/T - phi)
 * <p>
 * Uses Apache Commons Math LevenbergMarquardtOptimizer.
 * F-test compares cosinor fit vs flat-line (mesor-only) model.
 */
public class CosinorFitter {

    private static final double TWO_PI = 2.0 * Math.PI;

    private CosinorFitter() { }

    /**
     * Fits a cosinor model to time-series data.
     *
     * @param times         time values in hours
     * @param values        observed values
     * @param initialPeriod initial period estimate from FFT/autocorrelation (hours)
     * @param damped        if true, fit the damped cosinor model
     * @return CosinorResult with fitted parameters and statistics
     */
    public static CosinorResult fit(double[] times, double[] values,
                                    double initialPeriod, boolean damped) {
        int n = values.length;

        // Compute initial guesses
        double sumVal = 0;
        double minVal = Double.MAX_VALUE;
        double maxVal = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            sumVal += values[i];
            if (values[i] < minVal) minVal = values[i];
            if (values[i] > maxVal) maxVal = values[i];
        }
        double meanVal = sumVal / n;
        double ampGuess = (maxVal - minVal) / 2.0;

        // Initial parameter vector
        // Standard: [M, A, T, phi]
        // Damped:   [M, A, T, phi, lambda]
        double[] start;
        if (damped) {
            start = new double[] { meanVal, ampGuess, initialPeriod, 0.0, 0.01 };
        } else {
            start = new double[] { meanVal, ampGuess, initialPeriod, 0.0 };
        }

        // Target values
        final double[] target = values;
        final boolean isDamped = damped;
        final int nPts = n;
        final double[] t = times;

        // Model function
        MultivariateVectorFunction model = new MultivariateVectorFunction() {
            @Override
            public double[] value(double[] params) {
                double M = params[0];
                double A = params[1];
                double T = params[2];
                double phi = params[3];
                double lambda = isDamped ? params[4] : 0;

                double[] predicted = new double[nPts];
                for (int i = 0; i < nPts; i++) {
                    double cosArg = TWO_PI * t[i] / T - phi;
                    double envelope = isDamped ? Math.exp(-lambda * t[i]) : 1.0;
                    predicted[i] = M + A * envelope * Math.cos(cosArg);
                }
                return predicted;
            }
        };

        // Jacobian
        MultivariateMatrixFunction jacobian = new MultivariateMatrixFunction() {
            @Override
            public double[][] value(double[] params) {
                double M = params[0];
                double A = params[1];
                double T = params[2];
                double phi = params[3];
                double lambda = isDamped ? params[4] : 0;

                int nParams = isDamped ? 5 : 4;
                double[][] jac = new double[nPts][nParams];

                for (int i = 0; i < nPts; i++) {
                    double cosArg = TWO_PI * t[i] / T - phi;
                    double cosVal = Math.cos(cosArg);
                    double sinVal = Math.sin(cosArg);
                    double envelope = isDamped ? Math.exp(-lambda * t[i]) : 1.0;

                    // dY/dM
                    jac[i][0] = 1.0;
                    // dY/dA
                    jac[i][1] = envelope * cosVal;
                    // dY/dT = A * envelope * sin(cosArg) * (2*pi*t / T^2)
                    jac[i][2] = A * envelope * sinVal * (TWO_PI * t[i] / (T * T));
                    // dY/dphi = A * envelope * sin(cosArg)
                    jac[i][3] = A * envelope * sinVal;

                    if (isDamped) {
                        // dY/dlambda = -A * t * exp(-lambda*t) * cos(cosArg)
                        jac[i][4] = -A * t[i] * envelope * cosVal;
                    }
                }
                return jac;
            }
        };

        // Solve
        double[] optimized;
        try {
            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(start)
                    .model(model, jacobian)
                    .target(target)
                    .maxEvaluations(5000)
                    .maxIterations(1000)
                    .build();

            LevenbergMarquardtOptimizer optimizer = new LevenbergMarquardtOptimizer();
            LeastSquaresOptimizer.Optimum result = optimizer.optimize(problem);
            RealVector point = result.getPoint();
            optimized = point.toArray();
        } catch (Exception e) {
            // If optimizer fails, return NaN result
            return new CosinorResult(meanVal, Double.NaN, initialPeriod,
                    Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, 1.0, new double[n]);
        }

        // Extract parameters
        double M = optimized[0];
        double A = Math.abs(optimized[1]); // amplitude is always positive
        double T = Math.abs(optimized[2]); // period must be positive
        double phi = optimized[3];
        double lambda = damped ? optimized[4] : 0;

        // Normalize phase to [0, 2*pi)
        phi = phi % TWO_PI;
        if (phi < 0) phi += TWO_PI;

        // Compute fitted values
        double[] fitted = new double[n];
        for (int i = 0; i < n; i++) {
            double cosArg = TWO_PI * t[i] / T - phi;
            double envelope = damped ? Math.exp(-lambda * t[i]) : 1.0;
            fitted[i] = M + A * envelope * Math.cos(cosArg);
        }

        // Compute R-squared
        double ssRes = 0;
        double ssTot = 0;
        for (int i = 0; i < n; i++) {
            double resid = values[i] - fitted[i];
            ssRes += resid * resid;
            double totDev = values[i] - meanVal;
            ssTot += totDev * totDev;
        }
        double rSquared = (ssTot > 0) ? 1.0 - (ssRes / ssTot) : 0;

        // F-test: cosinor vs flat line
        // RSS_null = SS_total (flat line at mean)
        // RSS_cosinor = SS_residual
        int nParams = damped ? 5 : 4;
        int dfDiff = nParams - 1; // difference in parameters (cosinor minus flat-line)
        int dfResid = n - nParams;

        double pValue = 1.0;
        if (dfResid > 0 && ssRes > 0) {
            double fStat = ((ssTot - ssRes) / dfDiff) / (ssRes / dfResid);
            if (fStat > 0 && !Double.isInfinite(fStat)) {
                try {
                    FDistribution fDist = new FDistribution(dfDiff, dfResid);
                    pValue = 1.0 - fDist.cumulativeProbability(fStat);
                } catch (Exception e) {
                    pValue = 1.0;
                }
            }
        }

        // Convert acrophase to hours: phase_hours = phi * T / (2*pi)
        double acrophaseHours = phi * T / TWO_PI;
        // Wrap to [0, T)
        acrophaseHours = acrophaseHours % T;
        if (acrophaseHours < 0) acrophaseHours += T;

        // Damping time constant: tau = 1/lambda
        double dampingTau = (damped && lambda > 0) ? 1.0 / lambda : Double.NaN;

        return new CosinorResult(M, A, T, phi, acrophaseHours, dampingTau,
                rSquared, pValue, fitted);
    }
}
