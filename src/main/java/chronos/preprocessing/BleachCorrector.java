package chronos.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bleach and signal decay correction for time-lapse stacks.
 * Supports mono/bi-exponential fitting (for fluorescent reporters),
 * sliding percentile baseline (for bioluminescence), and simple ratio methods.
 * All methods produce correction factors that DIVIDE each frame (bleaching is multiplicative).
 */
public class BleachCorrector {

    /**
     * Fit a mono-exponential decay to the trough envelope of a trace.
     * Model: F(t) = A * exp(-t / tau) + C
     *
     * @param trace mean intensity per frame
     * @return correction factors (divide each frame by its factor)
     */
    public static double[] monoExponentialFit(double[] trace) {
        int n = trace.length;
        // Extract trough envelope (local minima)
        int[] troughIdx;
        double[] troughVals;
        List<int[]> troughs = findTroughs(trace);
        troughIdx = new int[troughs.size()];
        troughVals = new double[troughs.size()];
        for (int i = 0; i < troughs.size(); i++) {
            troughIdx[i] = troughs.get(i)[0];
            troughVals[i] = troughs.get(i)[1] != 0 ? trace[troughs.get(i)[0]] : trace[troughs.get(i)[0]];
        }
        // If too few troughs, use the raw trace
        double[] fitX;
        double[] fitY;
        if (troughs.size() < 3) {
            fitX = new double[n];
            fitY = new double[n];
            for (int i = 0; i < n; i++) {
                fitX[i] = i;
                fitY[i] = trace[i];
            }
        } else {
            fitX = new double[troughs.size()];
            fitY = new double[troughs.size()];
            for (int i = 0; i < troughs.size(); i++) {
                fitX[i] = troughIdx[i];
                fitY[i] = trace[troughIdx[i]];
            }
        }

        // Initial estimates
        double A0 = fitY[0];
        double C0 = fitY[fitY.length - 1];
        double tau0 = n / 3.0;

        try {
            final double[] xData = fitX;
            final double[] yData = fitY;

            MultivariateJacobianFunction model = new MultivariateJacobianFunction() {
                public Pair<RealVector, RealMatrix> value(RealVector params) {
                    double A = params.getEntry(0);
                    double tau = params.getEntry(1);
                    double C = params.getEntry(2);

                    RealVector values = new ArrayRealVector(xData.length);
                    RealMatrix jacobian = new Array2DRowRealMatrix(xData.length, 3);

                    for (int i = 0; i < xData.length; i++) {
                        double t = xData[i];
                        double expTerm = Math.exp(-t / tau);
                        values.setEntry(i, A * expTerm + C);
                        jacobian.setEntry(i, 0, expTerm);                    // dF/dA
                        jacobian.setEntry(i, 1, A * t * expTerm / (tau * tau)); // dF/dtau
                        jacobian.setEntry(i, 2, 1.0);                        // dF/dC
                    }
                    return new Pair<RealVector, RealMatrix>(values, jacobian);
                }
            };

            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(new double[]{A0, tau0, C0})
                    .model(model)
                    .target(yData)
                    .maxEvaluations(1000)
                    .maxIterations(500)
                    .build();

            LeastSquaresOptimizer.Optimum optimum =
                    new LevenbergMarquardtOptimizer().optimize(problem);

            double A = optimum.getPoint().getEntry(0);
            double tau = optimum.getPoint().getEntry(1);
            double C = optimum.getPoint().getEntry(2);

            // Generate correction factors for all frames
            double[] factors = new double[n];
            double f0 = A * Math.exp(0) + C; // value at t=0
            for (int i = 0; i < n; i++) {
                double fitted = A * Math.exp(-(double) i / tau) + C;
                factors[i] = fitted / f0;
            }
            return factors;

        } catch (Exception e) {
            IJ.log("  Warning: mono-exponential fit failed (" + e.getClass().getSimpleName()
                    + "), using simple ratio fallback");
            return simpleRatio(trace);
        }
    }

    /**
     * Fit a bi-exponential decay to the trough envelope of a trace.
     * Model: F(t) = A1 * exp(-t / tau1) + A2 * exp(-t / tau2) + C
     *
     * @param trace mean intensity per frame
     * @return correction factors (divide each frame by its factor)
     */
    public static double[] biExponentialFit(double[] trace) {
        int n = trace.length;

        // Extract trough envelope
        List<int[]> troughs = findTroughs(trace);
        double[] fitX;
        double[] fitY;
        if (troughs.size() < 5) {
            fitX = new double[n];
            fitY = new double[n];
            for (int i = 0; i < n; i++) {
                fitX[i] = i;
                fitY[i] = trace[i];
            }
        } else {
            fitX = new double[troughs.size()];
            fitY = new double[troughs.size()];
            for (int i = 0; i < troughs.size(); i++) {
                fitX[i] = troughs.get(i)[0];
                fitY[i] = trace[troughs.get(i)[0]];
            }
        }

        // Initial estimates
        double totalRange = fitY[0] - fitY[fitY.length - 1];
        double A1_0 = totalRange * 0.6;
        double A2_0 = totalRange * 0.4;
        double tau1_0 = n / 5.0;
        double tau2_0 = n / 1.5;
        double C0 = fitY[fitY.length - 1];

        try {
            final double[] xData = fitX;
            final double[] yData = fitY;

            MultivariateJacobianFunction model = new MultivariateJacobianFunction() {
                public Pair<RealVector, RealMatrix> value(RealVector params) {
                    double A1 = params.getEntry(0);
                    double tau1 = params.getEntry(1);
                    double A2 = params.getEntry(2);
                    double tau2 = params.getEntry(3);
                    double C = params.getEntry(4);

                    RealVector values = new ArrayRealVector(xData.length);
                    RealMatrix jacobian = new Array2DRowRealMatrix(xData.length, 5);

                    for (int i = 0; i < xData.length; i++) {
                        double t = xData[i];
                        double exp1 = Math.exp(-t / tau1);
                        double exp2 = Math.exp(-t / tau2);
                        values.setEntry(i, A1 * exp1 + A2 * exp2 + C);

                        jacobian.setEntry(i, 0, exp1);                           // dF/dA1
                        jacobian.setEntry(i, 1, A1 * t * exp1 / (tau1 * tau1));   // dF/dtau1
                        jacobian.setEntry(i, 2, exp2);                           // dF/dA2
                        jacobian.setEntry(i, 3, A2 * t * exp2 / (tau2 * tau2));   // dF/dtau2
                        jacobian.setEntry(i, 4, 1.0);                            // dF/dC
                    }
                    return new Pair<RealVector, RealMatrix>(values, jacobian);
                }
            };

            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(new double[]{A1_0, tau1_0, A2_0, tau2_0, C0})
                    .model(model)
                    .target(yData)
                    .maxEvaluations(2000)
                    .maxIterations(1000)
                    .build();

            LeastSquaresOptimizer.Optimum optimum =
                    new LevenbergMarquardtOptimizer().optimize(problem);

            double A1 = optimum.getPoint().getEntry(0);
            double tau1 = optimum.getPoint().getEntry(1);
            double A2 = optimum.getPoint().getEntry(2);
            double tau2 = optimum.getPoint().getEntry(3);
            double C = optimum.getPoint().getEntry(4);

            double f0 = A1 + A2 + C; // value at t=0
            double[] factors = new double[n];
            for (int i = 0; i < n; i++) {
                double fitted = A1 * Math.exp(-(double) i / tau1) + A2 * Math.exp(-(double) i / tau2) + C;
                factors[i] = fitted / f0;
            }
            return factors;

        } catch (Exception e) {
            IJ.log("  Warning: bi-exponential fit failed (" + e.getClass().getSimpleName()
                    + "), falling back to mono-exponential");
            return monoExponentialFit(trace);
        }
    }

    /**
     * Sliding percentile baseline estimation.
     * Best for bioluminescence signal decay correction.
     *
     * @param trace         mean intensity per frame
     * @param windowFrames  window size in frames (should span >= 1 circadian cycle)
     * @param percentile    percentile to use (e.g., 8.0 for 8th percentile)
     * @return baseline values for each frame (divide raw by baseline for correction)
     */
    public static double[] slidingPercentile(double[] trace, int windowFrames, double percentile) {
        int n = trace.length;
        double[] baseline = new double[n];
        int halfWin = windowFrames / 2;

        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - halfWin);
            int end = Math.min(n - 1, i + halfWin);
            int winSize = end - start + 1;

            double[] window = new double[winSize];
            System.arraycopy(trace, start, window, 0, winSize);
            Arrays.sort(window);

            int idx = (int) Math.round((percentile / 100.0) * (winSize - 1));
            idx = Math.max(0, Math.min(winSize - 1, idx));
            baseline[i] = window[idx];

            // Avoid division by zero
            if (baseline[i] <= 0) {
                baseline[i] = 1.0;
            }
        }

        return baseline;
    }

    /**
     * Simple ratio correction: divide each frame by its mean relative to frame 1.
     *
     * @param trace mean intensity per frame
     * @return correction factors for each frame
     */
    public static double[] simpleRatio(double[] trace) {
        int n = trace.length;
        double[] factors = new double[n];
        double f0 = trace[0];
        if (f0 <= 0) f0 = 1.0;

        for (int i = 0; i < n; i++) {
            factors[i] = trace[i] / f0;
            if (factors[i] <= 0) {
                factors[i] = 1.0;
            }
        }
        return factors;
    }

    /**
     * Apply correction factors to a stack by dividing each frame.
     *
     * @param imp     input stack
     * @param factors one factor per frame (frame pixels are divided by this)
     * @return corrected stack (new ImagePlus)
     */
    public static ImagePlus correctStack(ImagePlus imp, double[] factors) {
        ImagePlus result = imp.duplicate();
        ImageStack stack = result.getStack();
        int nSlices = stack.getSize();
        int nFactors = Math.min(nSlices, factors.length);

        for (int i = 1; i <= nFactors; i++) {
            double factor = factors[i - 1];
            if (factor <= 0 || Double.isNaN(factor)) {
                factor = 1.0;
            }

            ImageProcessor ip = stack.getProcessor(i);
            int pixelCount = ip.getWidth() * ip.getHeight();
            for (int j = 0; j < pixelCount; j++) {
                float val = ip.getf(j);
                ip.setf(j, (float) (val / factor));
            }

            if (i % 50 == 0 || i == nSlices) {
                IJ.showProgress(i, nSlices);
            }
        }

        result.setTitle(imp.getTitle() + "_bleachCorr");
        return result;
    }

    /**
     * Extract the mean intensity trace from a stack (one value per frame).
     */
    public static double[] extractMeanTrace(ImagePlus imp) {
        ImageStack stack = imp.getStack();
        int nSlices = stack.getSize();
        double[] trace = new double[nSlices];

        for (int i = 1; i <= nSlices; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            ImageStatistics stats = ImageStatistics.getStatistics(ip, ImageStatistics.MEAN, null);
            trace[i - 1] = stats.mean;
        }
        return trace;
    }

    /**
     * Find local minima (troughs) in a trace.
     * Returns list of [index, 0] pairs.
     */
    private static List<int[]> findTroughs(double[] trace) {
        List<int[]> troughs = new ArrayList<int[]>();
        int n = trace.length;
        if (n < 3) {
            troughs.add(new int[]{0, 0});
            troughs.add(new int[]{n - 1, 0});
            return troughs;
        }

        // Always include first and last points
        troughs.add(new int[]{0, 0});

        // Use a smoothed version to find robust minima
        double[] smooth = boxSmooth(trace, 5);

        for (int i = 2; i < n - 2; i++) {
            if (smooth[i] <= smooth[i - 1] && smooth[i] <= smooth[i + 1]
                    && smooth[i] <= smooth[i - 2] && smooth[i] <= smooth[i + 2]) {
                troughs.add(new int[]{i, 0});
            }
        }

        troughs.add(new int[]{n - 1, 0});
        return troughs;
    }

    /**
     * Simple box smoothing for trough detection.
     */
    private static double[] boxSmooth(double[] data, int radius) {
        int n = data.length;
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - radius);
            int end = Math.min(n - 1, i + radius);
            double sum = 0;
            for (int j = start; j <= end; j++) {
                sum += data[j];
            }
            result[i] = sum / (end - start + 1);
        }
        return result;
    }
}
