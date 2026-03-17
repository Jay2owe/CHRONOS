package chronos.rhythm;

import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.apache.commons.math3.complex.Complex;

import java.util.ArrayList;
import java.util.List;

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
     * Sinc filter (ideal bandpass) detrending — removes all frequencies below
     * a cutoff period via FFT. This is the approach used by pyBOAT.
     * <p>
     * Removes slow trends (periods longer than cutoffPeriod) while perfectly
     * preserving all oscillatory content at shorter periods.
     *
     * @param trace        the input time-series
     * @param cutoffPeriod the cutoff period in number of samples. Frequencies
     *                     with period > cutoffPeriod are removed.
     * @return detrended copy with slow trends removed
     */
    public static double[] sincFilter(double[] trace, double cutoffPeriod) {
        int n = trace.length;
        if (n < 4 || cutoffPeriod <= 0) return none(trace);

        // Pad to next power of 2 for FFT
        int nPad = 1;
        while (nPad < n) nPad <<= 1;
        nPad <<= 1; // double for zero-padding to reduce edge effects

        double[] padded = new double[nPad];
        System.arraycopy(trace, 0, padded, 0, n);
        // Mirror-pad the end to reduce edge effects
        for (int i = n; i < nPad; i++) {
            int mirrorIdx = Math.max(0, 2 * n - 2 - i);
            if (mirrorIdx >= 0 && mirrorIdx < n) {
                padded[i] = trace[mirrorIdx];
            }
        }

        // Forward FFT
        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);
        Complex[] spectrum = fft.transform(padded, TransformType.FORWARD);

        // Zero out low frequencies (long periods = slow trends)
        // Cutoff frequency = 1/cutoffPeriod (in cycles per sample)
        double cutoffFreq = 1.0 / cutoffPeriod;
        for (int k = 0; k < nPad; k++) {
            double freq = (double) k / nPad; // frequency in cycles per sample
            if (k > nPad / 2) freq = 1.0 - freq; // mirror for negative frequencies

            if (freq < cutoffFreq) {
                spectrum[k] = Complex.ZERO;
            }
        }

        // Inverse FFT
        Complex[] filtered = fft.transform(spectrum, TransformType.INVERSE);

        // Extract real part, trimmed to original length
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = filtered[i].getReal();
        }
        return out;
    }

    /**
     * Empirical Mode Decomposition (EMD) detrending.
     * <p>
     * Adaptively decomposes the signal into Intrinsic Mode Functions (IMFs)
     * and a residual trend. The trend (last residual) is subtracted, leaving
     * all oscillatory content. Makes no assumptions about trend shape.
     * <p>
     * Particularly effective for bioluminescence signal decay which is
     * neither exponential nor polynomial.
     *
     * @param trace the input time-series
     * @return detrended copy (original minus residual trend)
     */
    public static double[] emd(double[] trace) {
        int n = trace.length;
        if (n < 10) return none(trace);

        double[] residual = trace.clone();
        double[] trend = new double[n];

        // Extract IMFs until residual is monotonic or max iterations reached
        int maxIMFs = 10;
        for (int imfNum = 0; imfNum < maxIMFs; imfNum++) {
            double[] imf = siftIMF(residual);
            if (imf == null) break; // residual is monotonic

            // Subtract IMF from residual
            for (int i = 0; i < n; i++) {
                residual[i] -= imf[i];
            }
        }

        // residual is now the trend
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = trace[i] - residual[i];
        }
        return out;
    }

    /**
     * Sifts one IMF from the signal using the EMD sifting process.
     * Returns null if the signal is monotonic (no more IMFs to extract).
     */
    private static double[] siftIMF(double[] signal) {
        int n = signal.length;

        if (!hasEnoughExtrema(signal)) return null;

        double[] h = signal.clone();
        int maxSiftIterations = 20;
        double siftThreshold = 0.3;

        for (int iter = 0; iter < maxSiftIterations; iter++) {
            // Find maxima and minima indices
            List<Integer> maxIdx = new ArrayList<Integer>();
            List<Integer> minIdx = new ArrayList<Integer>();
            findExtremaIndices(h, maxIdx, minIdx);

            if (maxIdx.size() < 2 || minIdx.size() < 2) return null;

            // Build upper envelope (spline through maxima)
            double[] upperEnv = interpolateEnvelope(h, maxIdx, n);
            // Build lower envelope (spline through minima)
            double[] lowerEnv = interpolateEnvelope(h, minIdx, n);

            // Compute mean envelope and subtract
            double[] hNew = new double[n];
            double sdNumerator = 0;
            double sdDenominator = 0;
            for (int i = 0; i < n; i++) {
                double mean = (upperEnv[i] + lowerEnv[i]) / 2.0;
                hNew[i] = h[i] - mean;
                sdNumerator += mean * mean;
                sdDenominator += h[i] * h[i];
            }

            double sd = (sdDenominator > 0) ? sdNumerator / sdDenominator : 0;
            h = hNew;

            if (sd < siftThreshold && iter > 0) break;
        }

        return h;
    }

    /**
     * Checks if the signal has enough extrema to extract an IMF.
     */
    private static boolean hasEnoughExtrema(double[] signal) {
        int maxCount = 0, minCount = 0;
        for (int i = 1; i < signal.length - 1; i++) {
            if (signal[i] > signal[i - 1] && signal[i] > signal[i + 1]) maxCount++;
            else if (signal[i] < signal[i - 1] && signal[i] < signal[i + 1]) minCount++;
            if (maxCount >= 2 && minCount >= 2) return true;
        }
        return false;
    }

    /**
     * Finds indices of local maxima and minima in the signal.
     */
    private static void findExtremaIndices(double[] signal, List<Integer> maxIdx,
                                            List<Integer> minIdx) {
        int n = signal.length;
        for (int i = 1; i < n - 1; i++) {
            if (signal[i] > signal[i - 1] && signal[i] > signal[i + 1]) {
                maxIdx.add(i);
            } else if (signal[i] < signal[i - 1] && signal[i] < signal[i + 1]) {
                minIdx.add(i);
            }
        }
    }

    /**
     * Interpolates an envelope through extrema using cubic spline.
     * Adds boundary points by mirroring the first/last extremum values
     * to the signal edges for natural boundary handling.
     *
     * @param signal    the signal (to read values at extrema indices)
     * @param indices   indices of extrema (maxima or minima)
     * @param n         total signal length
     * @return envelope array of length n
     */
    private static double[] interpolateEnvelope(double[] signal, List<Integer> indices, int n) {
        int nE = indices.size();

        // Add boundary points: mirror first/last extremum to edges
        int nPts = nE + 2;
        double[] x = new double[nPts];
        double[] y = new double[nPts];

        // Left boundary: mirror first extremum about index 0
        int firstIdx = indices.get(0);
        x[0] = Math.max(0, -firstIdx);
        y[0] = signal[firstIdx];

        // Actual extrema
        for (int i = 0; i < nE; i++) {
            int idx = indices.get(i);
            x[i + 1] = idx;
            y[i + 1] = signal[idx];
        }

        // Right boundary: mirror last extremum about index n-1
        int lastIdx = indices.get(nE - 1);
        x[nPts - 1] = Math.min(n - 1, 2 * (n - 1) - lastIdx);
        y[nPts - 1] = signal[lastIdx];

        // Ensure x is strictly increasing (fix any duplicate/non-monotonic entries)
        // Left boundary
        if (x[0] >= x[1]) x[0] = Math.max(0, x[1] - 1);
        // Right boundary
        if (x[nPts - 1] <= x[nPts - 2]) x[nPts - 1] = Math.min(n - 1, x[nPts - 2] + 1);

        // Check for duplicates in middle (shouldn't happen, but safety)
        for (int i = 1; i < nPts; i++) {
            if (x[i] <= x[i - 1]) {
                x[i] = x[i - 1] + 0.5;
            }
        }

        // Cubic spline interpolation
        try {
            SplineInterpolator interpolator = new SplineInterpolator();
            PolynomialSplineFunction spline = interpolator.interpolate(x, y);

            double[] envelope = new double[n];
            for (int i = 0; i < n; i++) {
                double xi = i;
                // Clamp to spline domain
                if (xi < x[0]) xi = x[0];
                if (xi > x[nPts - 1]) xi = x[nPts - 1];
                envelope[i] = spline.value(xi);
            }
            return envelope;
        } catch (Exception e) {
            // Fallback: linear interpolation between first and last extremum
            double[] envelope = new double[n];
            double v0 = signal[indices.get(0)];
            double v1 = signal[indices.get(nE - 1)];
            for (int i = 0; i < n; i++) {
                envelope[i] = v0 + (v1 - v0) * i / (n - 1.0);
            }
            return envelope;
        }
    }

    /**
     * LOESS (Locally Estimated Scatterplot Smoothing) detrending.
     * <p>
     * Fits a locally weighted polynomial regression and subtracts it.
     * Good general-purpose detrending with adjustable bandwidth.
     *
     * @param trace     the input time-series
     * @param bandwidth LOESS bandwidth (fraction of data to use for each
     *                  local fit). Range 0-1; typical 0.3-0.5 for circadian.
     * @return detrended copy
     */
    public static double[] loess(double[] trace, double bandwidth) {
        int n = trace.length;
        if (n < 4 || bandwidth <= 0 || bandwidth > 1) return none(trace);

        org.apache.commons.math3.analysis.interpolation.LoessInterpolator
                loessInterp = new org.apache.commons.math3.analysis.interpolation.LoessInterpolator(
                bandwidth, 2);

        double[] xvals = new double[n];
        for (int i = 0; i < n; i++) xvals[i] = i;

        try {
            double[] trend = loessInterp.smooth(xvals, trace);
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                out[i] = trace[i] - trend[i];
            }
            return out;
        } catch (Exception e) {
            return none(trace);
        }
    }

    /**
     * Convenience method that selects the detrending approach by name.
     *
     * @param trace  the input time-series
     * @param method one of "None", "Linear", "Quadratic", "Cubic",
     *               "Sinc Filter", "EMD", "LOESS"
     * @return detrended copy
     */
    public static double[] detrend(double[] trace, String method) {
        if ("Linear".equals(method)) {
            return linear(trace);
        } else if ("Quadratic".equals(method)) {
            return polynomial(trace, 2);
        } else if ("Cubic".equals(method)) {
            return polynomial(trace, 3);
        } else if ("Sinc Filter".equals(method)) {
            // Default cutoff: remove trends longer than 48 hours worth of samples
            // Caller should use sincFilter() directly for custom cutoff
            return sincFilter(trace, trace.length / 2.0);
        } else if ("EMD".equals(method)) {
            return emd(trace);
        } else if ("LOESS".equals(method)) {
            return loess(trace, 0.4);
        } else {
            return none(trace);
        }
    }
}
