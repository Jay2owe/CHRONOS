package chronos.rhythm;

import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

/**
 * Morlet Continuous Wavelet Transform (CWT) following Torrence &amp; Compo (1998).
 * <p>
 * The algorithm operates in the frequency domain for efficiency:
 * for each scale, the Morlet wavelet is constructed in Fourier space,
 * multiplied by the FFT of the signal, and inverse-transformed to yield
 * wavelet coefficients. Power = |W(n,s)|^2.
 * <p>
 * Includes cone of influence computation and significance testing
 * against a red-noise (lag-1 autocorrelation) null hypothesis.
 * <p>
 * Reference: Torrence, C. and Compo, G. P. (1998). A practical guide to
 * wavelet analysis. Bulletin of the American Meteorological Society, 79(1), 61-78.
 */
public class WaveletAnalyzer {

    private WaveletAnalyzer() { }

    /**
     * Performs Morlet CWT on a time-series.
     *
     * @param trace              input time-series values (will be mean-subtracted internally)
     * @param frameIntervalHours time between samples in hours (dt)
     * @param minPeriodH         minimum period to analyze (hours)
     * @param maxPeriodH         maximum period to analyze (hours)
     * @param numScales          number of scales (logarithmic spacing)
     * @param w0                 Morlet wavelet central frequency (typically 6)
     * @return WaveletResult containing power scalogram, significance, COI, etc.
     */
    public static WaveletResult analyze(double[] trace, double frameIntervalHours,
                                        double minPeriodH, double maxPeriodH,
                                        int numScales, double w0) {
        int n = trace.length;
        double dt = frameIntervalHours;

        // Mean-subtract the input
        double mean = 0;
        for (int i = 0; i < n; i++) {
            mean += trace[i];
        }
        mean /= n;

        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = trace[i] - mean;
        }

        // Compute variance for significance testing
        double variance = 0;
        for (int i = 0; i < n; i++) {
            variance += x[i] * x[i];
        }
        variance /= n;

        // Compute lag-1 autocorrelation for red noise model
        double lag1 = computeLag1Autocorrelation(x);

        // Relationship: period = (4*pi*s) / (w0 + sqrt(2 + w0^2))
        // So: s = period * (w0 + sqrt(2 + w0^2)) / (4*pi)
        double periodFactor = (w0 + Math.sqrt(2.0 + w0 * w0)) / (4.0 * Math.PI);

        // Compute scales: logarithmic spacing from minPeriod to maxPeriod
        // s_j = s_0 * 2^(j * dj)
        double s0 = minPeriodH * periodFactor;
        double sMax = maxPeriodH * periodFactor;
        double dj = Math.log(sMax / s0) / ((numScales - 1) * Math.log(2.0));

        double[] scales = new double[numScales];
        double[] periods = new double[numScales];
        for (int j = 0; j < numScales; j++) {
            scales[j] = s0 * Math.pow(2.0, j * dj);
            periods[j] = scales[j] / periodFactor;
        }

        // Zero-pad to next power of 2 for FFT
        int nPad = nextPowerOf2(n);

        // FFT of the (zero-padded) signal
        double[] padded = new double[nPad];
        System.arraycopy(x, 0, padded, 0, n);

        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);
        org.apache.commons.math3.complex.Complex[] xHat = fft.transform(padded, TransformType.FORWARD);

        // Angular frequencies: omega_k = 2*pi*k / (N*dt)  for k <= N/2
        //                      omega_k = -2*pi*(N-k) / (N*dt)  for k > N/2
        double[] omega = new double[nPad];
        for (int k = 0; k <= nPad / 2; k++) {
            omega[k] = 2.0 * Math.PI * k / (nPad * dt);
        }
        for (int k = nPad / 2 + 1; k < nPad; k++) {
            omega[k] = -2.0 * Math.PI * (nPad - k) / (nPad * dt);
        }

        // Compute wavelet coefficients for each scale
        double[][] power = new double[numScales][n];
        double[][] significance = new double[numScales][n];

        // Normalization constant for Morlet: C_delta
        double piQuarterInv = Math.pow(Math.PI, -0.25);

        // Precompute chi-squared 95% threshold for dof=2 (one-sided): chi2_95(2) = 5.991
        double chi2Threshold = 5.991;

        for (int j = 0; j < numScales; j++) {
            double s = scales[j];

            // Construct Morlet daughter wavelet in frequency domain
            // Psi_hat(s*omega_k) = pi^(-1/4) * sqrt(2*pi*s/dt) * H(omega_k) * exp(-(s*omega_k - w0)^2 / 2)
            // The sqrt(2*pi*s/dt) normalization ensures energy conservation
            double norm = Math.sqrt(2.0 * Math.PI * s / dt) * piQuarterInv;

            double[] psiRe = new double[nPad];
            double[] psiIm = new double[nPad]; // Morlet in freq domain is real-valued

            for (int k = 0; k < nPad; k++) {
                if (omega[k] > 0) {
                    // Heaviside step function: only positive frequencies
                    double arg = (s * omega[k] - w0);
                    psiRe[k] = norm * Math.exp(-0.5 * arg * arg);
                }
                // psiRe[k] = 0 for omega[k] <= 0 (already initialized to 0)
            }

            // Multiply FFT of signal by wavelet in frequency domain
            // W(n,s) = IFFT[ X_hat(k) * Psi_hat*(s*omega_k) ]
            // Since Psi_hat is real, conjugate = Psi_hat itself
            double[] productRe = new double[nPad];
            double[] productIm = new double[nPad];
            for (int k = 0; k < nPad; k++) {
                double xRe = xHat[k].getReal();
                double xIm = xHat[k].getImaginary();
                productRe[k] = xRe * psiRe[k];
                productIm[k] = xIm * psiRe[k];
            }

            // Inverse FFT to get wavelet coefficients
            org.apache.commons.math3.complex.Complex[] productComplex =
                    new org.apache.commons.math3.complex.Complex[nPad];
            for (int k = 0; k < nPad; k++) {
                productComplex[k] = new org.apache.commons.math3.complex.Complex(productRe[k], productIm[k]);
            }

            org.apache.commons.math3.complex.Complex[] wCoeffs = fft.transform(productComplex, TransformType.INVERSE);

            // Extract power for the original (non-padded) timepoints
            for (int t = 0; t < n; t++) {
                double re = wCoeffs[t].getReal();
                double im = wCoeffs[t].getImaginary();
                power[j][t] = re * re + im * im;
            }

            // Significance testing against red noise
            // Theoretical red noise spectrum at this scale:
            // P_k = (1 - lag1^2) / (1 - 2*lag1*cos(2*pi*freq_k/N) + lag1^2)
            // But for wavelet significance, we use the scale-averaged form:
            // fft_theor(s) = variance * (1 - lag1^2) / (1 + lag1^2 - 2*lag1*cos(2*pi*dt/period))
            double freq = dt / periods[j];
            double denom = 1.0 + lag1 * lag1 - 2.0 * lag1 * Math.cos(2.0 * Math.PI * freq);
            double fftTheor = variance * (1.0 - lag1 * lag1) / denom;
            if (denom <= 0 || fftTheor <= 0) {
                fftTheor = variance; // fallback
            }

            // Significance level: power / (fftTheor * chi2_threshold/2)
            // Values > 1 mean significant at 95% level
            double sigThresh = fftTheor * chi2Threshold / 2.0;
            for (int t = 0; t < n; t++) {
                significance[j][t] = power[j][t] / sigThresh;
            }
        }

        // Compute cone of influence
        // COI(n) = sqrt(2) * s * sqrt(2*ln(2)) — but this is the e-folding time
        // for the Morlet wavelet. The COI defines the region where edge effects
        // are important. For each timepoint, compute the distance to the nearest
        // edge in time, then convert to a period.
        double coiFactor = Math.sqrt(2.0) * dt;
        double[] coi = new double[n];
        for (int t = 0; t < n; t++) {
            int distToEdge = Math.min(t, n - 1 - t);
            // COI period: the maximum reliable period at this timepoint
            // coi = coiFactor * distToEdge (in time units) / periodFactor gives period
            coi[t] = coiFactor * distToEdge / periodFactor;
            // Ensure non-negative
            if (coi[t] < 0) coi[t] = 0;
        }

        // Build time array
        double[] times = new double[n];
        for (int t = 0; t < n; t++) {
            times[t] = t * dt;
        }

        return new WaveletResult(power, scales, periods, times, significance, coi);
    }

    /**
     * Computes the lag-1 autocorrelation of a zero-mean signal.
     */
    private static double computeLag1Autocorrelation(double[] x) {
        int n = x.length;
        if (n < 2) return 0;

        double sum0 = 0;
        double sum1 = 0;
        for (int i = 0; i < n - 1; i++) {
            sum1 += x[i] * x[i + 1];
            sum0 += x[i] * x[i];
        }
        sum0 += x[n - 1] * x[n - 1];

        if (sum0 == 0) return 0;
        double lag1 = sum1 / sum0;
        // Clamp to valid range
        if (lag1 < -1) lag1 = -1;
        if (lag1 > 1) lag1 = 1;
        return lag1;
    }

    /**
     * Returns the smallest power of 2 >= n.
     */
    private static int nextPowerOf2(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }
}
