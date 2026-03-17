package chronos.rhythm;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

/**
 * FFT-based period estimation using Apache Commons Math FastFourierTransformer.
 * <p>
 * Algorithm:
 * 1. Zero-pad to next power of 2
 * 2. Apply Hann window to reduce spectral leakage
 * 3. Compute FFT
 * 4. Build power spectrum
 * 5. Find dominant peak in circadian frequency range
 */
public class FFTAnalyzer {

    private FFTAnalyzer() { }

    /**
     * Performs FFT-based period estimation on a time-series.
     *
     * @param trace              the detrended time-series values
     * @param frameIntervalHours time between samples in hours
     * @param minPeriodH         minimum period to search (hours)
     * @param maxPeriodH         maximum period to search (hours)
     * @return FFTResult with dominant period, power spectrum, and frequencies
     */
    public static FFTResult analyze(double[] trace, double frameIntervalHours,
                                    double minPeriodH, double maxPeriodH) {
        int n = trace.length;

        // Next power of 2
        int nPad = nextPowerOf2(n);

        // Apply Hann window and zero-pad
        double[] windowed = new double[nPad];
        for (int i = 0; i < n; i++) {
            double hann = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1)));
            windowed[i] = trace[i] * hann;
        }
        // Remaining elements are already 0 (zero-padding)

        // Compute FFT
        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);
        Complex[] spectrum = fft.transform(windowed, TransformType.FORWARD);

        // Build one-sided power spectrum (indices 0 to nPad/2)
        int halfN = nPad / 2;
        double[] power = new double[halfN + 1];
        double[] frequencies = new double[halfN + 1];
        double freqRes = 1.0 / (nPad * frameIntervalHours);

        for (int k = 0; k <= halfN; k++) {
            frequencies[k] = k * freqRes;
            double re = spectrum[k].getReal();
            double im = spectrum[k].getImaginary();
            power[k] = (re * re + im * im) / (nPad * nPad);
        }

        // Find peak in circadian range
        double minFreq = 1.0 / maxPeriodH;
        double maxFreq = 1.0 / minPeriodH;
        double peakPower = -1;
        int peakIdx = -1;

        for (int k = 1; k <= halfN; k++) {
            if (frequencies[k] >= minFreq && frequencies[k] <= maxFreq) {
                if (power[k] > peakPower) {
                    peakPower = power[k];
                    peakIdx = k;
                }
            }
        }

        double dominantPeriod = (peakIdx > 0) ? 1.0 / frequencies[peakIdx] : Double.NaN;

        return new FFTResult(dominantPeriod, peakPower, power, frequencies);
    }

    /**
     * Returns the smallest power of 2 that is >= n.
     */
    private static int nextPowerOf2(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }
}
