package chronos.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * Temporal filtering across frames in a time-lapse stack.
 * Provides moving average and Savitzky-Golay smoothing.
 */
public class TemporalFilter {

    /**
     * Apply a moving average filter across frames (temporal dimension).
     * For each output frame t, averages frames in [t - w/2, t + w/2].
     * Edge handling: window shrinks at boundaries.
     *
     * @param imp          input stack
     * @param windowFrames number of frames in the averaging window (odd recommended)
     * @return temporally smoothed stack (new ImagePlus)
     */
    public static ImagePlus movingAverage(ImagePlus imp, int windowFrames) {
        if (windowFrames <= 1) {
            return imp;
        }

        int nSlices = imp.getStackSize();
        int width = imp.getWidth();
        int height = imp.getHeight();
        int nPixels = width * height;
        int halfWin = windowFrames / 2;

        ImageStack inputStack = imp.getStack();
        ImageStack outputStack = new ImageStack(width, height);

        // Read all frames into float arrays for efficient access
        float[][] frames = new float[nSlices][];
        for (int i = 0; i < nSlices; i++) {
            ImageProcessor ip = inputStack.getProcessor(i + 1);
            FloatProcessor fp = ip.convertToFloatProcessor();
            frames[i] = (float[]) fp.getPixels();
        }

        // Compute moving average for each output frame
        for (int t = 0; t < nSlices; t++) {
            int start = Math.max(0, t - halfWin);
            int end = Math.min(nSlices - 1, t + halfWin);
            int winSize = end - start + 1;

            float[] averaged = new float[nPixels];

            // Sum all frames in window
            for (int f = start; f <= end; f++) {
                float[] src = frames[f];
                for (int j = 0; j < nPixels; j++) {
                    averaged[j] += src[j];
                }
            }

            // Divide by window size
            float invWin = 1.0f / winSize;
            for (int j = 0; j < nPixels; j++) {
                averaged[j] *= invWin;
            }

            // Convert back to the original bit depth if needed
            FloatProcessor fp = new FloatProcessor(width, height, averaged);
            ImageProcessor outIp;
            if (inputStack.getProcessor(1) instanceof ij.process.ShortProcessor) {
                outIp = fp.convertToShortProcessor(false);
            } else if (inputStack.getProcessor(1) instanceof ij.process.ByteProcessor) {
                outIp = fp.convertToByteProcessor(false);
            } else {
                outIp = fp;
            }

            outputStack.addSlice(inputStack.getSliceLabel(t + 1), outIp);

            if ((t + 1) % 50 == 0 || t == nSlices - 1) {
                IJ.showProgress(t + 1, nSlices);
            }
        }

        ImagePlus result = new ImagePlus(imp.getTitle() + "_tempFilt", outputStack);
        result.setCalibration(imp.getCalibration().copy());
        return result;
    }

    /**
     * Apply Savitzky-Golay smoothing to a 1D trace (e.g., extracted mean intensity).
     * Uses polynomial fitting within a sliding window.
     *
     * @param trace       input signal values
     * @param windowFrames window size (must be odd, will be forced odd if even)
     * @param polyOrder    polynomial order (typically 2 or 3; must be < windowFrames)
     * @return smoothed trace
     */
    public static double[] savitzkyGolay(double[] trace, int windowFrames, int polyOrder) {
        int n = trace.length;
        if (windowFrames < 3) windowFrames = 3;
        if (windowFrames % 2 == 0) windowFrames++;
        if (polyOrder >= windowFrames) polyOrder = windowFrames - 1;

        int halfWin = windowFrames / 2;

        // Compute S-G coefficients for the smoothing (zeroth derivative)
        double[] coeffs = computeSGCoefficients(halfWin, polyOrder);

        double[] result = new double[n];

        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = -halfWin; j <= halfWin; j++) {
                int idx = i + j;
                // Mirror at boundaries
                if (idx < 0) idx = -idx;
                if (idx >= n) idx = 2 * n - idx - 2;
                if (idx < 0) idx = 0;
                if (idx >= n) idx = n - 1;

                sum += coeffs[j + halfWin] * trace[idx];
            }
            result[i] = sum;
        }

        return result;
    }

    /**
     * Compute Savitzky-Golay convolution coefficients for smoothing.
     * Uses the standard least-squares approach with Gram polynomials.
     */
    private static double[] computeSGCoefficients(int halfWin, int polyOrder) {
        int windowSize = 2 * halfWin + 1;

        // Build the Vandermonde-like matrix J where J[i][k] = i^k
        // i ranges from -halfWin to halfWin
        double[][] J = new double[windowSize][polyOrder + 1];
        for (int i = 0; i < windowSize; i++) {
            double x = i - halfWin;
            J[i][0] = 1.0;
            for (int k = 1; k <= polyOrder; k++) {
                J[i][k] = J[i][k - 1] * x;
            }
        }

        // Compute (J^T * J)
        double[][] JtJ = new double[polyOrder + 1][polyOrder + 1];
        for (int r = 0; r <= polyOrder; r++) {
            for (int c = 0; c <= polyOrder; c++) {
                double sum = 0;
                for (int i = 0; i < windowSize; i++) {
                    sum += J[i][r] * J[i][c];
                }
                JtJ[r][c] = sum;
            }
        }

        // Invert (J^T * J) using Gauss-Jordan elimination
        double[][] inv = invertMatrix(JtJ, polyOrder + 1);

        // Coefficients = first row of (J^T * J)^(-1) * J^T
        // The smoothing coefficients are the first row of the hat matrix H = J * (J^T J)^-1 * J^T
        double[] coeffs = new double[windowSize];
        for (int i = 0; i < windowSize; i++) {
            double sum = 0;
            for (int k = 0; k <= polyOrder; k++) {
                sum += inv[0][k] * J[i][k];
            }
            coeffs[i] = sum;
        }

        return coeffs;
    }

    /**
     * Invert a square matrix using Gauss-Jordan elimination.
     */
    private static double[][] invertMatrix(double[][] matrix, int n) {
        double[][] augmented = new double[n][2 * n];

        // Build augmented matrix [A | I]
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                augmented[i][j] = matrix[i][j];
            }
            augmented[i][i + n] = 1.0;
        }

        // Forward elimination with partial pivoting
        for (int col = 0; col < n; col++) {
            // Find pivot
            int maxRow = col;
            double maxVal = Math.abs(augmented[col][col]);
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(augmented[row][col]) > maxVal) {
                    maxVal = Math.abs(augmented[row][col]);
                    maxRow = row;
                }
            }
            // Swap rows
            double[] temp = augmented[col];
            augmented[col] = augmented[maxRow];
            augmented[maxRow] = temp;

            double pivot = augmented[col][col];
            if (Math.abs(pivot) < 1e-15) continue;

            // Scale pivot row
            for (int j = 0; j < 2 * n; j++) {
                augmented[col][j] /= pivot;
            }

            // Eliminate column
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double factor = augmented[row][col];
                for (int j = 0; j < 2 * n; j++) {
                    augmented[row][j] -= factor * augmented[col][j];
                }
            }
        }

        // Extract inverse
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                inv[i][j] = augmented[i][j + n];
            }
        }
        return inv;
    }
}
