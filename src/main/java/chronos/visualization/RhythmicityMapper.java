package chronos.visualization;

import chronos.rhythm.CosinorFitter;
import chronos.rhythm.CosinorResult;
import chronos.rhythm.Detrending;
import chronos.rhythm.FFTAnalyzer;
import chronos.rhythm.FFTResult;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.FloatProcessor;

/**
 * Per-pixel rhythmicity mapping.
 * <p>
 * Runs cosinor fitting on every pixel's intensity time-series across the stack,
 * generating spatial heatmaps of period, phase, amplitude, R-squared, and p-value.
 * Output as 32-bit float TIFFs suitable for LUT overlay on mean projections.
 */
public class RhythmicityMapper {

    private RhythmicityMapper() {}

    /**
     * Generate per-pixel rhythmicity maps from a time-lapse stack.
     *
     * @param imp            input time-lapse stack
     * @param intervalHours  frame interval in hours
     * @param minPeriodH     minimum period to search (hours)
     * @param maxPeriodH     maximum period to search (hours)
     * @param detrendMethod  detrending method name (e.g., "Linear")
     * @param outputDir      directory to save the output maps
     * @param baseName       base name for output files
     */
    public static void generateMaps(ImagePlus imp, double intervalHours,
                                     double minPeriodH, double maxPeriodH,
                                     String detrendMethod, String outputDir,
                                     String baseName) {
        int width = imp.getWidth();
        int height = imp.getHeight();
        int nFrames = imp.getStackSize();

        if (nFrames < 6) {
            IJ.log("    Too few frames for rhythmicity mapping (" + nFrames + ")");
            return;
        }

        IJ.log("    Generating per-pixel rhythmicity maps (" + width + "x" + height
                + ", " + nFrames + " frames)...");

        // Build time array
        double[] timesH = new double[nFrames];
        for (int i = 0; i < nFrames; i++) {
            timesH[i] = i * intervalHours;
        }

        // Output maps
        float[] periodMap = new float[width * height];
        float[] phaseMap = new float[width * height];
        float[] amplitudeMap = new float[width * height];
        float[] rSquaredMap = new float[width * height];
        float[] pValueMap = new float[width * height];

        // Extract all pixel time-series at once for cache efficiency
        ImageStack stack = imp.getStack();
        float[][] allFrames = new float[nFrames][];
        for (int f = 0; f < nFrames; f++) {
            allFrames[f] = (float[]) stack.getProcessor(f + 1)
                    .convertToFloatProcessor().getPixels();
        }

        int totalPixels = width * height;
        int progressStep = Math.max(1, totalPixels / 20);

        for (int px = 0; px < totalPixels; px++) {
            // Extract this pixel's time-series
            double[] trace = new double[nFrames];
            for (int f = 0; f < nFrames; f++) {
                trace[f] = allFrames[f][px];
            }

            // Skip constant pixels (background)
            double min = trace[0], max = trace[0];
            for (double v : trace) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
            if (max - min < 1e-6) {
                periodMap[px] = Float.NaN;
                phaseMap[px] = Float.NaN;
                amplitudeMap[px] = 0;
                rSquaredMap[px] = 0;
                pValueMap[px] = 1;
                continue;
            }

            // Detrend
            double[] detrended = Detrending.detrend(trace, detrendMethod);

            // FFT for period estimation
            FFTResult fft = FFTAnalyzer.analyze(detrended, intervalHours,
                    minPeriodH, maxPeriodH);
            double estPeriod = fft.dominantPeriod;
            if (Double.isNaN(estPeriod)) {
                estPeriod = (minPeriodH + maxPeriodH) / 2.0;
            }

            // Cosinor fit
            CosinorResult cos = CosinorFitter.fit(timesH, detrended, estPeriod, false);

            periodMap[px] = (float) cos.period;
            phaseMap[px] = (float) cos.acrophaseHours;
            amplitudeMap[px] = (float) cos.amplitude;
            rSquaredMap[px] = (float) cos.rSquared;
            pValueMap[px] = (float) cos.pValue;

            if (px % progressStep == 0) {
                IJ.showProgress(px, totalPixels);
            }
        }

        IJ.showProgress(1.0);

        // Save maps as 32-bit TIFFs
        saveMap(periodMap, width, height, outputDir, baseName + "_period_map.tif", "Period (h)");
        saveMap(phaseMap, width, height, outputDir, baseName + "_phase_map.tif", "Phase (h)");
        saveMap(amplitudeMap, width, height, outputDir, baseName + "_amplitude_map.tif", "Amplitude");
        saveMap(rSquaredMap, width, height, outputDir, baseName + "_rsquared_map.tif", "R-squared");
        saveMap(pValueMap, width, height, outputDir, baseName + "_pvalue_map.tif", "p-value");

        IJ.log("    Saved 5 rhythmicity maps for " + baseName);
    }

    private static void saveMap(float[] pixels, int width, int height,
                                 String outputDir, String fileName, String title) {
        FloatProcessor fp = new FloatProcessor(width, height, pixels);
        ImagePlus mapImp = new ImagePlus(title, fp);
        new FileSaver(mapImp).saveAsTiff(outputDir + fileName);
        mapImp.close();
    }
}
