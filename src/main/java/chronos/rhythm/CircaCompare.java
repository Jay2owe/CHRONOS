package chronos.rhythm;

import org.apache.commons.math3.fitting.leastsquares.*;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.distribution.FDistribution;
import org.apache.commons.math3.util.Pair;

/**
 * CircaCompare: statistical comparison of circadian rhythm parameters
 * between two groups.
 * <p>
 * Implements the approach of Parsons et al. (2019) Bioinformatics 36:1208-1212.
 * Fits a joint cosinor model with group-specific mesor, amplitude, and phase
 * differences, then tests each difference via F-test (nested model comparison).
 * <p>
 * Usage: compare dorsal vs ventral SCN, WT vs KO, treated vs control, etc.
 */
public class CircaCompare {

    private CircaCompare() { }

    /**
     * Compares two groups of circadian data.
     *
     * @param times1  time values (hours) for group 1
     * @param values1 trace values for group 1
     * @param times2  time values (hours) for group 2
     * @param values2 trace values for group 2
     * @param period  fixed period for cosinor fitting (hours)
     * @return comparison result with p-values for mesor, amplitude, and phase differences
     */
    public static CircaCompareResult compare(double[] times1, double[] values1,
                                              double[] times2, double[] values2,
                                              double period) {
        int n1 = times1.length;
        int n2 = times2.length;
        int nTotal = n1 + n2;

        // Merge data arrays
        double[] allTimes = new double[nTotal];
        double[] allValues = new double[nTotal];
        int[] group = new int[nTotal]; // 0 = group1, 1 = group2
        System.arraycopy(times1, 0, allTimes, 0, n1);
        System.arraycopy(times2, 0, allTimes, n1, n2);
        System.arraycopy(values1, 0, allValues, 0, n1);
        System.arraycopy(values2, 0, allValues, n1, n2);
        for (int i = 0; i < n1; i++) group[i] = 0;
        for (int i = n1; i < nTotal; i++) group[i] = 1;

        double omega = 2.0 * Math.PI / period;

        // 1. Fit individual cosinor to each group to check rhythmicity
        CosinorResult fit1 = CosinorFitter.fit(times1, values1, period, false);
        CosinorResult fit2 = CosinorFitter.fit(times2, values2, period, false);

        boolean group1Rhythmic = fit1.pValue < 0.05;
        boolean group2Rhythmic = fit2.pValue < 0.05;

        // 2. Fit FULL model: Y = (M + dM*g) + (A + dA*g)*cos(omega*t - (phi + dphi*g))
        // Parameters: [M, A, phi, dM, dA, dphi]
        double[] fullParams = fitFullModel(allTimes, allValues, group, omega,
                fit1, fit2);

        double fullRSS = computeRSS(allTimes, allValues, group, omega, fullParams);
        int fullDF = nTotal - 6;

        // 3. Test mesor difference (dM=0)
        double[] noMesorDiffParams = fitConstrainedModel(allTimes, allValues, group,
                omega, fullParams, true, false, false);
        double noMesorRSS = computeRSS(allTimes, allValues, group, omega,
                expandConstrained(noMesorDiffParams, true, false, false));
        double pMesor = fTestPValue(noMesorRSS, fullRSS, 1, fullDF);

        // 4. Test amplitude difference (dA=0)
        double[] noAmpDiffParams = fitConstrainedModel(allTimes, allValues, group,
                omega, fullParams, false, true, false);
        double noAmpRSS = computeRSS(allTimes, allValues, group, omega,
                expandConstrained(noAmpDiffParams, false, true, false));
        double pAmplitude = fTestPValue(noAmpRSS, fullRSS, 1, fullDF);

        // 5. Test phase difference (dphi=0)
        double[] noPhaseDiffParams = fitConstrainedModel(allTimes, allValues, group,
                omega, fullParams, false, false, true);
        double noPhaseRSS = computeRSS(allTimes, allValues, group, omega,
                expandConstrained(noPhaseDiffParams, false, false, true));
        double pPhase = fTestPValue(noPhaseRSS, fullRSS, 1, fullDF);

        // Extract differences from full model
        double mesorDiff = fullParams[3];
        double ampDiff = fullParams[4];
        double phaseDiffRad = fullParams[5];
        double phaseDiffHours = phaseDiffRad * period / (2.0 * Math.PI);
        // Normalize phase difference to [-period/2, period/2]
        while (phaseDiffHours > period / 2.0) phaseDiffHours -= period;
        while (phaseDiffHours < -period / 2.0) phaseDiffHours += period;

        return new CircaCompareResult(
                fit1.mesor, fit2.mesor, mesorDiff, pMesor,
                fit1.amplitude, fit2.amplitude, ampDiff, pAmplitude,
                fit1.acrophaseHours, fit2.acrophaseHours, phaseDiffHours, pPhase,
                group1Rhythmic, group2Rhythmic
        );
    }

    /**
     * Fits the full 6-parameter model using Levenberg-Marquardt.
     * Parameters: [M, A, phi, dM, dA, dphi]
     */
    private static double[] fitFullModel(final double[] times, final double[] values,
                                          final int[] group, final double omega,
                                          CosinorResult fit1, CosinorResult fit2) {
        // Initial guesses from individual fits
        double M0 = fit1.mesor;
        double A0 = fit1.amplitude;
        double phi0 = fit1.acrophaseRad;
        double dM0 = fit2.mesor - fit1.mesor;
        double dA0 = fit2.amplitude - fit1.amplitude;
        double dphi0 = fit2.acrophaseRad - fit1.acrophaseRad;

        final int n = times.length;

        MultivariateJacobianFunction model = new MultivariateJacobianFunction() {
            @Override
            public Pair<RealVector, RealMatrix> value(RealVector params) {
                double M = params.getEntry(0);
                double A = params.getEntry(1);
                double phi = params.getEntry(2);
                double dM = params.getEntry(3);
                double dA = params.getEntry(4);
                double dphi = params.getEntry(5);

                RealVector residuals = new ArrayRealVector(n);
                RealMatrix jacobian = new Array2DRowRealMatrix(n, 6);

                for (int i = 0; i < n; i++) {
                    int g = group[i];
                    double t = times[i];
                    double mG = M + dM * g;
                    double aG = A + dA * g;
                    double pG = phi + dphi * g;

                    double cosVal = Math.cos(omega * t - pG);
                    double sinVal = Math.sin(omega * t - pG);

                    double predicted = mG + aG * cosVal;
                    residuals.setEntry(i, predicted);

                    // Jacobian: d(predicted)/d(param)
                    jacobian.setEntry(i, 0, 1.0);                    // d/dM
                    jacobian.setEntry(i, 1, cosVal);                  // d/dA
                    jacobian.setEntry(i, 2, aG * sinVal);             // d/dphi
                    jacobian.setEntry(i, 3, g);                       // d/ddM
                    jacobian.setEntry(i, 4, g * cosVal);              // d/ddA
                    jacobian.setEntry(i, 5, g * aG * sinVal);         // d/ddphi
                }
                return new Pair<RealVector, RealMatrix>(residuals, jacobian);
            }
        };

        RealVector target = new ArrayRealVector(values);
        RealVector start = new ArrayRealVector(new double[]{M0, A0, phi0, dM0, dA0, dphi0});

        try {
            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .model(model)
                    .target(target)
                    .start(start)
                    .maxEvaluations(5000)
                    .maxIterations(1000)
                    .build();

            LeastSquaresOptimizer.Optimum result =
                    new LevenbergMarquardtOptimizer().optimize(problem);

            double[] params = result.getPoint().toArray();
            // Ensure amplitudes are positive
            if (params[1] < 0) {
                params[1] = -params[1];
                params[2] += Math.PI;
            }
            return params;
        } catch (Exception e) {
            // Fallback: return initial guesses
            return new double[]{M0, A0, phi0, dM0, dA0, dphi0};
        }
    }

    /**
     * Fits a constrained model where one or more differences are zero.
     * Returns the reduced parameter set.
     */
    private static double[] fitConstrainedModel(final double[] times, final double[] values,
                                                  final int[] group, final double omega,
                                                  double[] fullParams,
                                                  final boolean zeroDM, final boolean zeroDA,
                                                  final boolean zeroDPhi) {
        // Count free parameters
        int nFree = 6;
        if (zeroDM) nFree--;
        if (zeroDA) nFree--;
        if (zeroDPhi) nFree--;

        // Build initial guess from full model, excluding constrained params
        double[] init = new double[nFree];
        int idx = 0;
        init[idx++] = fullParams[0]; // M
        init[idx++] = fullParams[1]; // A
        init[idx++] = fullParams[2]; // phi
        if (!zeroDM) init[idx++] = fullParams[3];
        if (!zeroDA) init[idx++] = fullParams[4];
        if (!zeroDPhi) init[idx++] = fullParams[5];

        final int n = times.length;
        final int nF = nFree;

        MultivariateJacobianFunction model = new MultivariateJacobianFunction() {
            @Override
            public Pair<RealVector, RealMatrix> value(RealVector params) {
                int pi = 0;
                double M = params.getEntry(pi++);
                double A = params.getEntry(pi++);
                double phi = params.getEntry(pi++);
                double dM = zeroDM ? 0 : params.getEntry(pi++);
                double dA = zeroDA ? 0 : params.getEntry(pi++);
                double dphi = zeroDPhi ? 0 : params.getEntry(pi++);

                RealVector residuals = new ArrayRealVector(n);
                RealMatrix jacobian = new Array2DRowRealMatrix(n, nF);

                for (int i = 0; i < n; i++) {
                    int g = group[i];
                    double t = times[i];
                    double mG = M + dM * g;
                    double aG = A + dA * g;
                    double pG = phi + dphi * g;

                    double cosVal = Math.cos(omega * t - pG);
                    double sinVal = Math.sin(omega * t - pG);

                    residuals.setEntry(i, mG + aG * cosVal);

                    int ji = 0;
                    jacobian.setEntry(i, ji++, 1.0);
                    jacobian.setEntry(i, ji++, cosVal);
                    jacobian.setEntry(i, ji++, aG * sinVal);
                    if (!zeroDM) jacobian.setEntry(i, ji++, g);
                    if (!zeroDA) jacobian.setEntry(i, ji++, g * cosVal);
                    if (!zeroDPhi) jacobian.setEntry(i, ji++, g * aG * sinVal);
                }
                return new Pair<RealVector, RealMatrix>(residuals, jacobian);
            }
        };

        RealVector target = new ArrayRealVector(values);
        RealVector start = new ArrayRealVector(init);

        try {
            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .model(model)
                    .target(target)
                    .start(start)
                    .maxEvaluations(5000)
                    .maxIterations(1000)
                    .build();

            LeastSquaresOptimizer.Optimum result =
                    new LevenbergMarquardtOptimizer().optimize(problem);
            return result.getPoint().toArray();
        } catch (Exception e) {
            return init;
        }
    }

    /**
     * Expands a constrained parameter set back to the full 6-parameter layout
     * with zeros for the constrained terms.
     */
    private static double[] expandConstrained(double[] constrained,
                                               boolean zeroDM, boolean zeroDA, boolean zeroDPhi) {
        double[] full = new double[6];
        int idx = 0;
        full[0] = constrained[idx++]; // M
        full[1] = constrained[idx++]; // A
        full[2] = constrained[idx++]; // phi
        full[3] = zeroDM ? 0 : constrained[idx++];
        full[4] = zeroDA ? 0 : constrained[idx++];
        full[5] = zeroDPhi ? 0 : constrained[idx++];
        return full;
    }

    /**
     * Computes residual sum of squares for the full model with given parameters.
     */
    private static double computeRSS(double[] times, double[] values, int[] group,
                                      double omega, double[] params) {
        double M = params[0], A = params[1], phi = params[2];
        double dM = params[3], dA = params[4], dphi = params[5];

        double rss = 0;
        for (int i = 0; i < times.length; i++) {
            int g = group[i];
            double predicted = (M + dM * g) +
                    (A + dA * g) * Math.cos(omega * times[i] - (phi + dphi * g));
            double residual = values[i] - predicted;
            rss += residual * residual;
        }
        return rss;
    }

    /**
     * F-test p-value for nested model comparison.
     */
    private static double fTestPValue(double rssReduced, double rssFull,
                                       int dfDiff, int dfFull) {
        if (dfFull <= 0 || dfDiff <= 0 || rssFull <= 0) return 1.0;

        double fStat = ((rssReduced - rssFull) / dfDiff) / (rssFull / dfFull);
        if (fStat <= 0 || Double.isNaN(fStat) || Double.isInfinite(fStat)) return 1.0;

        try {
            FDistribution fDist = new FDistribution(dfDiff, dfFull);
            return 1.0 - fDist.cumulativeProbability(fStat);
        } catch (Exception e) {
            return 1.0;
        }
    }
}
