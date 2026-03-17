package chronos.rhythm;

import chronos.Analysis;
import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.io.CsvReader;
import chronos.io.CsvWriter;
import chronos.ui.PipelineDialog;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Module 4: Rhythm Analysis.
 * <p>
 * Loads dF/F traces from .circadian/traces/, detrends, runs selected period
 * estimation methods (FFT, autocorrelation, Lomb-Scargle), fits cosinor model,
 * computes rhythmicity statistics, and saves results to .circadian/rhythm/.
 */
public class RhythmAnalysis implements Analysis {

    private boolean headless = false;
    private int parallelThreads = 1;

    private static final String[] DETREND_METHODS = {
            "None", "Linear", "Quadratic", "Cubic", "Sinc Filter", "LOESS", "EMD"
    };

    private static final String[] COSINOR_MODELS = {
            "Standard", "Damped"
    };

    @Override
    public boolean execute(String directory) {
        IJ.log("===== CHRONOS — Rhythm Analysis =====");

        // Load config
        SessionConfig config = SessionConfigIO.readFromDirectory(directory);

        // Show dialog unless headless
        if (!headless) {
            if (!showDialog(config)) {
                IJ.log("Rhythm Analysis: Cancelled by user.");
                return false;
            }
            SessionConfigIO.writeToDirectory(directory, config);
        }

        // Locate trace files
        String tracesDir = directory + File.separator + ".circadian" + File.separator + "traces";
        String rhythmDir = directory + File.separator + ".circadian" + File.separator + "rhythm";
        new File(rhythmDir).mkdirs();

        File traceFolder = new File(tracesDir);
        if (!traceFolder.exists()) {
            IJ.error("Rhythm Analysis", "No traces directory found at: " + tracesDir);
            return false;
        }

        // Find dF/F trace files
        String[] traceFiles = traceFolder.list(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.startsWith("DeltaF_F_Traces_") && name.endsWith(".csv");
            }
        });

        if (traceFiles == null || traceFiles.length == 0) {
            IJ.error("Rhythm Analysis", "No dF/F trace files found in: " + tracesDir);
            return false;
        }

        Arrays.sort(traceFiles);
        IJ.log("Found " + traceFiles.length + " trace file(s)");

        double frameIntervalHours = config.frameIntervalMin / 60.0;

        int processed = 0;
        for (int fi = 0; fi < traceFiles.length; fi++) {
            String filename = traceFiles[fi];
            IJ.log("");
            IJ.log("[" + (fi + 1) + "/" + traceFiles.length + "] " + filename);
            IJ.showProgress(fi, traceFiles.length);

            String tracePath = tracesDir + File.separator + filename;

            // Read traces
            String[][] headerHolder = new String[1][];
            double[][] traces = CsvReader.readTraces(tracePath, headerHolder);
            if (traces == null || traces.length == 0) {
                IJ.log("  WARNING: Could not read traces from " + tracePath);
                continue;
            }

            String[] roiNames = headerHolder[0];
            int nRois = traces.length;
            int nFrames = traces[0].length;
            IJ.log("  " + nRois + " ROI(s), " + nFrames + " frames");

            // Build time array in hours
            double[] timesH = new double[nFrames];
            for (int i = 0; i < nFrames; i++) {
                timesH[i] = i * frameIntervalHours;
            }

            // Extract base name from filename
            // e.g. "DeltaF_F_Traces_recording1.csv" -> "recording1"
            String baseName = filename;
            if (baseName.startsWith("DeltaF_F_Traces_")) {
                baseName = baseName.substring("DeltaF_F_Traces_".length());
            }
            if (baseName.endsWith(".csv")) {
                baseName = baseName.substring(0, baseName.length() - 4);
            }

            // Process each ROI
            List<RhythmResult> results = new ArrayList<RhythmResult>();

            // Storage for per-ROI FFT and autocorrelation data (for CSV export)
            double[][] fftPowerAll = null;
            double[] fftFrequencies = null;
            double[][] acfValuesAll = null;
            double[] acfLags = null;
            double[][] cosinorFitsAll = new double[nRois][nFrames];

            for (int r = 0; r < nRois; r++) {
                String roiName = roiNames[r];

                // Detrend
                double[] detrended = Detrending.detrend(traces[r], config.detrendingMethod);

                // --- Period estimation ---
                double bestPeriod = Double.NaN;
                FFTResult fftResult = null;
                AutocorrResult acResult = null;
                LSResult lsResult = null;

                // FFT
                if (config.runFFT) {
                    fftResult = FFTAnalyzer.analyze(detrended, frameIntervalHours,
                            config.periodMinHours, config.periodMaxHours);
                    if (!Double.isNaN(fftResult.dominantPeriod)) {
                        bestPeriod = fftResult.dominantPeriod;
                    }

                    // Store for CSV export (first ROI defines dimensions)
                    if (r == 0) {
                        fftFrequencies = fftResult.frequencies;
                        fftPowerAll = new double[nRois][];
                    }
                    fftPowerAll[r] = fftResult.power;
                }

                // Autocorrelation
                if (config.runAutocorrelation) {
                    acResult = AutocorrelationAnalyzer.analyze(detrended, frameIntervalHours,
                            config.periodMinHours, config.periodMaxHours);

                    // Use autocorrelation period if FFT didn't produce one
                    if (Double.isNaN(bestPeriod) && !Double.isNaN(acResult.estimatedPeriod)) {
                        bestPeriod = acResult.estimatedPeriod;
                    }

                    if (r == 0) {
                        acfLags = acResult.lags;
                        acfValuesAll = new double[nRois][];
                    }
                    acfValuesAll[r] = acResult.autocorrValues;
                }

                // Lomb-Scargle
                if (config.runLombScargle) {
                    lsResult = LombScargleAnalyzer.analyze(timesH, detrended,
                            config.periodMinHours, config.periodMaxHours, 500);

                    if (Double.isNaN(bestPeriod) && !Double.isNaN(lsResult.dominantPeriod)) {
                        bestPeriod = lsResult.dominantPeriod;
                    }
                }

                // Fallback: use center of search range
                if (Double.isNaN(bestPeriod)) {
                    bestPeriod = (config.periodMinHours + config.periodMaxHours) / 2.0;
                }

                // --- Cosinor fitting ---
                boolean damped = "Damped".equals(config.cosinorModel);
                CosinorResult cosinorResult = CosinorFitter.fit(timesH, detrended,
                        bestPeriod, damped);

                // Store fitted curve
                if (cosinorResult.fittedValues != null) {
                    System.arraycopy(cosinorResult.fittedValues, 0,
                            cosinorFitsAll[r], 0, nFrames);
                }

                // --- JTK_CYCLE (if enabled) ---
                JTKResult jtkResult = null;
                if (config.runJTKCycle) {
                    jtkResult = JTKCycleAnalyzer.analyze(detrended, frameIntervalHours,
                            config.periodMinHours, config.periodMaxHours);
                }

                // Use cosinor period as final period if valid
                double finalPeriod = cosinorResult.period;
                if (Double.isNaN(finalPeriod) || finalPeriod <= 0) {
                    finalPeriod = bestPeriod;
                }

                // Determine if rhythmic based on significance
                // If JTK is enabled, use combined evidence (either test significant)
                boolean isRhythmic = cosinorResult.pValue < config.significanceThreshold;
                if (jtkResult != null && jtkResult.pValue < config.significanceThreshold) {
                    isRhythmic = true;
                }

                RhythmResult rr = new RhythmResult(
                        roiName,
                        finalPeriod,
                        cosinorResult.acrophaseRad,
                        cosinorResult.acrophaseHours,
                        cosinorResult.amplitude,
                        cosinorResult.mesor,
                        cosinorResult.dampingTau,
                        cosinorResult.rSquared,
                        cosinorResult.pValue,
                        isRhythmic
                );
                rr.fftResult = fftResult;
                rr.autocorrResult = acResult;
                rr.lsResult = lsResult;
                rr.cosinorResult = cosinorResult;
                rr.jtkResult = jtkResult;

                results.add(rr);

                IJ.log("  " + roiName + ": T=" + String.format("%.2f", finalPeriod) + "h, " +
                        "A=" + String.format("%.4f", cosinorResult.amplitude) + ", " +
                        "R2=" + String.format("%.3f", cosinorResult.rSquared) + ", " +
                        "p=" + String.format("%.4f", cosinorResult.pValue) +
                        (isRhythmic ? " *" : ""));
            }

            // --- Rayleigh test across all rhythmic ROIs ---
            List<Double> rhythmicPhases = new ArrayList<Double>();
            for (RhythmResult rr : results) {
                if (rr.isRhythmic && !Double.isNaN(rr.phaseRad)) {
                    rhythmicPhases.add(rr.phaseRad);
                }
            }

            if (rhythmicPhases.size() > 1) {
                double[] phasesArr = new double[rhythmicPhases.size()];
                for (int i = 0; i < phasesArr.length; i++) {
                    phasesArr[i] = rhythmicPhases.get(i);
                }
                RayleighResult rayleigh = RayleighTest.test(phasesArr);
                IJ.log("  Rayleigh test: R=" + String.format("%.3f", rayleigh.vectorLength) +
                        ", p=" + String.format("%.4f", rayleigh.pValue) +
                        ", mean direction=" + String.format("%.1f", rayleigh.meanDirectionHours) + "h");
            }

            int nRhythmic = 0;
            for (RhythmResult rr : results) {
                if (rr.isRhythmic) nRhythmic++;
            }
            IJ.log("  Rhythmic: " + nRhythmic + "/" + nRois + " ROIs (" +
                    String.format("%.0f", 100.0 * nRhythmic / nRois) + "%)");

            // --- CircaCompare group comparison (if enabled) ---
            if (config.runCircaCompare && nRois >= 4) {
                runCircaCompare(rhythmDir, baseName, traces, timesH, roiNames,
                        results, config);
            }

            // --- Wavelet analysis (if enabled) ---
            WaveletResult[] waveletResults = null;
            if (config.runWavelet) {
                IJ.log("  Running wavelet analysis...");
                waveletResults = new WaveletResult[nRois];
                for (int r = 0; r < nRois; r++) {
                    double[] detrended = Detrending.detrend(traces[r], config.detrendingMethod);
                    WaveletResult wr = WaveletAnalyzer.analyze(detrended, frameIntervalHours,
                            config.periodMinHours, config.periodMaxHours, 100, 6.0);

                    // Extract ridge
                    RidgeResult ridge = WaveletRidgeExtractor.extractRidge(
                            wr.power, wr.scales, wr.periods, nFrames);
                    wr.ridge = ridge;
                    waveletResults[r] = wr;

                    // Compute mean ridge period
                    double meanRidgePeriod = 0;
                    for (int t = 0; t < nFrames; t++) {
                        meanRidgePeriod += ridge.instantPeriod[t];
                    }
                    meanRidgePeriod /= nFrames;
                    IJ.log("    " + roiNames[r] + ": mean ridge period=" +
                            String.format("%.2f", meanRidgePeriod) + "h");

                    // Save scalogram as 32-bit TIFF
                    saveScalogramTiff(rhythmDir, roiNames[r], wr);
                }

                // Save ridge data CSV
                saveWaveletRidgeCsv(rhythmDir, baseName, waveletResults, roiNames,
                        timesH, config.frameIntervalMin);
            }

            // --- Save outputs ---
            saveRhythmSummary(rhythmDir, baseName, results);
            saveCosinorFits(rhythmDir, baseName, timesH, cosinorFitsAll, roiNames,
                    config.frameIntervalMin);

            if (fftPowerAll != null && fftFrequencies != null) {
                saveFFTPeriodograms(rhythmDir, baseName, fftFrequencies, fftPowerAll, roiNames);
            }

            if (acfValuesAll != null && acfLags != null) {
                saveAutocorrelation(rhythmDir, baseName, acfLags, acfValuesAll, roiNames);
            }

            processed++;
        }

        IJ.showProgress(1.0);
        IJ.log("");
        IJ.log("Rhythm Analysis: Complete. Processed " + processed + "/" + traceFiles.length + " file(s).");
        return true;
    }

    /**
     * Shows the Rhythm Analysis configuration dialog.
     */
    private boolean showDialog(SessionConfig config) {
        PipelineDialog dlg = new PipelineDialog("CHRONOS - Rhythm Analysis");

        dlg.addHeader("Period Search Range");
        dlg.addNumericField("Min Period (hours):", config.periodMinHours, 1);
        dlg.addNumericField("Max Period (hours):", config.periodMaxHours, 1);

        dlg.addSpacer(8);
        dlg.addHeader("Detrending");
        dlg.addChoice("Detrending Method:", DETREND_METHODS, config.detrendingMethod);

        dlg.addSpacer(8);
        dlg.addHeader("Period Estimation Methods");
        dlg.addToggle("FFT", config.runFFT);
        dlg.addToggle("Autocorrelation", config.runAutocorrelation);
        dlg.addToggle("Lomb-Scargle", config.runLombScargle);
        dlg.addToggle("Wavelet (Stage 5)", config.runWavelet);
        dlg.addToggle("JTK_CYCLE (non-parametric)", config.runJTKCycle);

        dlg.addSpacer(8);
        dlg.addHeader("Cosinor Fitting");
        dlg.addChoice("Cosinor Model:", COSINOR_MODELS, config.cosinorModel);

        dlg.addSpacer(8);
        dlg.addHeader("Statistics");
        dlg.addNumericField("Significance Threshold:", config.significanceThreshold, 3);
        dlg.addToggle("CircaCompare (group comparison)", config.runCircaCompare);
        dlg.addHelpText("Compares rhythm parameters between ROI groups (e.g. Dorsal vs Ventral).");

        if (!dlg.showDialog()) {
            return false;
        }

        // Read values back
        config.periodMinHours = dlg.getNextNumber();
        config.periodMaxHours = dlg.getNextNumber();
        config.detrendingMethod = dlg.getNextChoice();
        config.runFFT = dlg.getNextBoolean();
        config.runAutocorrelation = dlg.getNextBoolean();
        config.runLombScargle = dlg.getNextBoolean();
        config.runWavelet = dlg.getNextBoolean();
        config.runJTKCycle = dlg.getNextBoolean();
        config.cosinorModel = dlg.getNextChoice();
        config.significanceThreshold = dlg.getNextNumber();
        config.runCircaCompare = dlg.getNextBoolean();

        return true;
    }

    // ---- Output writers ----

    /**
     * Saves the rhythm summary CSV (one row per ROI).
     */
    private void saveRhythmSummary(String rhythmDir, String baseName,
                                   List<RhythmResult> results) {
        String path = rhythmDir + File.separator + "Rhythm_Summary_" + baseName + ".csv";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("ROI,Period_h,Phase_rad,Phase_h,Amplitude,Mesor,Damping_tau," +
                    "R_squared,p_value,Is_Rhythmic," +
                    "JTK_Period_h,JTK_Phase_h,JTK_Amplitude,JTK_p_value,JTK_tau");

            for (RhythmResult rr : results) {
                StringBuilder sb = new StringBuilder();
                sb.append(rr.roiName).append(",");
                sb.append(fmt(rr.period)).append(",");
                sb.append(fmt(rr.phaseRad)).append(",");
                sb.append(fmt(rr.phaseHours)).append(",");
                sb.append(fmt(rr.amplitude)).append(",");
                sb.append(fmt(rr.mesor)).append(",");
                sb.append(fmt(rr.dampingTau)).append(",");
                sb.append(fmt(rr.rSquared)).append(",");
                sb.append(fmt(rr.pValue)).append(",");
                sb.append(rr.isRhythmic ? "TRUE" : "FALSE").append(",");

                if (rr.jtkResult != null) {
                    sb.append(fmt(rr.jtkResult.period)).append(",");
                    sb.append(fmt(rr.jtkResult.phaseHours)).append(",");
                    sb.append(fmt(rr.jtkResult.amplitude)).append(",");
                    sb.append(fmt(rr.jtkResult.pValue)).append(",");
                    sb.append(fmt(rr.jtkResult.tau));
                } else {
                    sb.append("NaN,NaN,NaN,NaN,NaN");
                }
                pw.println(sb.toString());
            }

            IJ.log("  Saved: " + path);
        } catch (IOException e) {
            IJ.log("  ERROR saving rhythm summary: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Saves cosinor fitted curves CSV.
     */
    private void saveCosinorFits(String rhythmDir, String baseName,
                                 double[] timesH, double[][] fits, String[] roiNames,
                                 double frameIntervalMin) {
        String path = rhythmDir + File.separator + "Cosinor_Fits_" + baseName + ".csv";
        CsvWriter.writeTraces(path, roiNames, fits, frameIntervalMin);
    }

    /**
     * Saves FFT periodogram data for all ROIs.
     */
    private void saveFFTPeriodograms(String rhythmDir, String baseName,
                                     double[] frequencies, double[][] power,
                                     String[] roiNames) {
        String path = rhythmDir + File.separator + "FFT_Periodograms_" + baseName + ".csv";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));

            // Header
            StringBuilder sb = new StringBuilder();
            sb.append("Frequency_hz,Period_h");
            for (String name : roiNames) {
                sb.append(",").append(name);
            }
            pw.println(sb.toString());

            // Data rows
            int nFreqs = frequencies.length;
            for (int k = 1; k < nFreqs; k++) { // skip DC component at k=0
                sb.setLength(0);
                sb.append(fmt(frequencies[k]));
                double period = (frequencies[k] > 0) ? 1.0 / frequencies[k] : Double.NaN;
                sb.append(",").append(fmt(period));

                for (int r = 0; r < roiNames.length; r++) {
                    if (power[r] != null && k < power[r].length) {
                        sb.append(",").append(fmt(power[r][k]));
                    } else {
                        sb.append(",NaN");
                    }
                }
                pw.println(sb.toString());
            }

            IJ.log("  Saved: " + path);
        } catch (IOException e) {
            IJ.log("  ERROR saving FFT periodograms: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Saves autocorrelation data for all ROIs.
     */
    private void saveAutocorrelation(String rhythmDir, String baseName,
                                     double[] lags, double[][] acf,
                                     String[] roiNames) {
        String path = rhythmDir + File.separator + "Autocorrelation_" + baseName + ".csv";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));

            // Header
            StringBuilder sb = new StringBuilder();
            sb.append("Lag_h");
            for (String name : roiNames) {
                sb.append(",").append(name);
            }
            pw.println(sb.toString());

            // Data rows
            int nLags = lags.length;
            for (int k = 0; k < nLags; k++) {
                sb.setLength(0);
                sb.append(fmt(lags[k]));

                for (int r = 0; r < roiNames.length; r++) {
                    if (acf[r] != null && k < acf[r].length) {
                        sb.append(",").append(fmt(acf[r][k]));
                    } else {
                        sb.append(",NaN");
                    }
                }
                pw.println(sb.toString());
            }

            IJ.log("  Saved: " + path);
        } catch (IOException e) {
            IJ.log("  ERROR saving autocorrelation: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Saves a wavelet scalogram as a 32-bit TIFF image.
     * Rows = scales (periods), columns = timepoints. Pixel value = power.
     */
    private void saveScalogramTiff(String rhythmDir, String roiName, WaveletResult wr) {
        int nScales = wr.power.length;
        int nTime = wr.power[0].length;

        FloatProcessor fp = new FloatProcessor(nTime, nScales);
        for (int j = 0; j < nScales; j++) {
            for (int t = 0; t < nTime; t++) {
                // Row 0 = longest period (top), row nScales-1 = shortest period (bottom)
                fp.setf(t, nScales - 1 - j, (float) wr.power[j][t]);
            }
        }

        ImagePlus imp = new ImagePlus("Scalogram_" + roiName, fp);
        String path = rhythmDir + File.separator + "Scalogram_" + roiName + ".tif";
        ij.io.FileSaver fs = new ij.io.FileSaver(imp);
        fs.saveAsTiff(path);
        IJ.log("    Saved scalogram: " + path);
    }

    /**
     * Saves wavelet ridge data for all ROIs to a CSV file.
     */
    private void saveWaveletRidgeCsv(String rhythmDir, String baseName,
                                      WaveletResult[] waveletResults,
                                      String[] roiNames, double[] timesH,
                                      double frameIntervalMin) {
        String path = rhythmDir + File.separator + "Wavelet_Ridge_" + baseName + ".csv";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));

            // Header
            StringBuilder sb = new StringBuilder();
            sb.append("Frame,Time_h");
            for (String name : roiNames) {
                sb.append(",").append(name).append("_Period_h");
                sb.append(",").append(name).append("_Amplitude");
                sb.append(",").append(name).append("_Phase_rad");
            }
            pw.println(sb.toString());

            // Data rows
            int nFrames = timesH.length;
            for (int t = 0; t < nFrames; t++) {
                sb.setLength(0);
                sb.append(t + 1);
                sb.append(",").append(fmt(timesH[t]));

                for (int r = 0; r < roiNames.length; r++) {
                    RidgeResult ridge = waveletResults[r].ridge;
                    sb.append(",").append(fmt(ridge.instantPeriod[t]));
                    sb.append(",").append(fmt(ridge.instantAmplitude[t]));
                    sb.append(",").append(fmt(ridge.instantPhase[t]));
                }
                pw.println(sb.toString());
            }

            IJ.log("  Saved: " + path);
        } catch (IOException e) {
            IJ.log("  ERROR saving wavelet ridge CSV: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Runs CircaCompare on pairs of ROI groups.
     * Groups are determined by ROI name prefix (e.g. "Dorsal_*" vs "Ventral_*",
     * or "Grid_R0_*" vs "Grid_R1_*"). If no clear grouping is found,
     * splits ROIs into first-half and second-half spatially.
     */
    private void runCircaCompare(String rhythmDir, String baseName,
                                  double[][] traces, double[] timesH,
                                  String[] roiNames, List<RhythmResult> results,
                                  SessionConfig config) {
        IJ.log("  Running CircaCompare group comparison...");

        // Try to find natural groups by prefix
        List<Integer> group1 = new ArrayList<Integer>();
        List<Integer> group2 = new ArrayList<Integer>();

        // Check for Dorsal/Ventral naming
        for (int i = 0; i < roiNames.length; i++) {
            String name = roiNames[i].toLowerCase();
            if (name.contains("dorsal") || name.startsWith("d_") || name.startsWith("d.")) {
                group1.add(i);
            } else if (name.contains("ventral") || name.startsWith("v_") || name.startsWith("v.")) {
                group2.add(i);
            }
        }

        // If no D/V groups, try first half vs second half of ROIs
        if (group1.isEmpty() || group2.isEmpty()) {
            group1.clear();
            group2.clear();
            int half = roiNames.length / 2;
            for (int i = 0; i < half; i++) group1.add(i);
            for (int i = half; i < roiNames.length; i++) group2.add(i);
        }

        if (group1.size() < 2 || group2.size() < 2) {
            IJ.log("    Not enough ROIs in each group for comparison.");
            return;
        }

        // Concatenate time-series for each group (all ROIs, all timepoints)
        double[] times1 = expandTimes(timesH, group1.size());
        double[] values1 = concatenateTraces(traces, group1);
        double[] times2 = expandTimes(timesH, group2.size());
        double[] values2 = concatenateTraces(traces, group2);

        // Use median period from cosinor fits
        double medianPeriod = 24.0;
        List<Double> periods = new ArrayList<Double>();
        for (RhythmResult rr : results) {
            if (rr.isRhythmic && !Double.isNaN(rr.period)) {
                periods.add(rr.period);
            }
        }
        if (!periods.isEmpty()) {
            java.util.Collections.sort(periods);
            medianPeriod = periods.get(periods.size() / 2);
        }

        CircaCompareResult cc = CircaCompare.compare(times1, values1, times2, values2, medianPeriod);

        IJ.log("    Group 1: " + group1.size() + " ROIs, Group 2: " + group2.size() + " ROIs");
        IJ.log("    Mesor diff: " + String.format("%.4f", cc.mesorDiff) +
                " (p=" + String.format("%.4f", cc.pMesor) + ")");
        IJ.log("    Amplitude diff: " + String.format("%.4f", cc.amplitudeDiff) +
                " (p=" + String.format("%.4f", cc.pAmplitude) + ")");
        IJ.log("    Phase diff: " + String.format("%.2f", cc.phaseDiffHours) +
                "h (p=" + String.format("%.4f", cc.pPhase) + ")");

        // Save CircaCompare results
        saveCircaCompare(rhythmDir, baseName, cc);
    }

    private double[] expandTimes(double[] timesH, int nRois) {
        double[] expanded = new double[timesH.length * nRois];
        for (int r = 0; r < nRois; r++) {
            System.arraycopy(timesH, 0, expanded, r * timesH.length, timesH.length);
        }
        return expanded;
    }

    private double[] concatenateTraces(double[][] traces, List<Integer> roiIndices) {
        int nFrames = traces[0].length;
        double[] concat = new double[nFrames * roiIndices.size()];
        for (int r = 0; r < roiIndices.size(); r++) {
            System.arraycopy(traces[roiIndices.get(r)], 0, concat, r * nFrames, nFrames);
        }
        return concat;
    }

    private void saveCircaCompare(String rhythmDir, String baseName, CircaCompareResult cc) {
        String path = rhythmDir + File.separator + "CircaCompare_" + baseName + ".csv";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("Parameter,Group1,Group2,Difference,p_value");
            pw.println("Mesor," + fmt(cc.mesor1) + "," + fmt(cc.mesor2) + "," +
                    fmt(cc.mesorDiff) + "," + fmt(cc.pMesor));
            pw.println("Amplitude," + fmt(cc.amplitude1) + "," + fmt(cc.amplitude2) + "," +
                    fmt(cc.amplitudeDiff) + "," + fmt(cc.pAmplitude));
            pw.println("Phase_h," + fmt(cc.phase1Hours) + "," + fmt(cc.phase2Hours) + "," +
                    fmt(cc.phaseDiffHours) + "," + fmt(cc.pPhase));
            pw.println("Group1_Rhythmic," + cc.group1Rhythmic + ",,,");
            pw.println("Group2_Rhythmic," + cc.group2Rhythmic + ",,,");
            IJ.log("  Saved: " + path);
        } catch (IOException e) {
            IJ.log("  ERROR saving CircaCompare results: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Formats a double for CSV output.
     */
    private static String fmt(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Inf" : "-Inf";
        return String.format("%.6g", value);
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setParallelThreads(int threads) {
        this.parallelThreads = threads;
    }

    @Override
    public String getName() {
        return "Rhythm Analysis";
    }

    @Override
    public int getIndex() {
        return 4;
    }
}
