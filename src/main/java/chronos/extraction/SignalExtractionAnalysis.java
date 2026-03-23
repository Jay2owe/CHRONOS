package chronos.extraction;

import chronos.Analysis;
import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.io.CsvWriter;
import chronos.io.RoiIO;
import chronos.ui.PipelineDialog;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;

/**
 * Module 3: Signal Extraction.
 * <p>
 * Loads corrected stacks and ROIs, extracts mean intensity per ROI per frame,
 * computes dF/F and optional Z-score traces, and saves CSVs to .circadian/traces/.
 */
public class SignalExtractionAnalysis implements Analysis {

    private boolean headless = false;
    private int parallelThreads = 1;

    private static final String[] F0_METHODS = {
            "Sliding Percentile",
            "First N Frames",
            "Whole-Trace Mean",
            "Whole-Trace Median",
            "Exponential Fit"
    };

    @Override
    public boolean execute(String directory) {
        IJ.log("Signal Extraction: Starting...");

        // Load config
        SessionConfig config = SessionConfigIO.readFromDirectory(directory);

        // Show dialog unless headless
        if (!headless) {
            if (!showDialog(config)) {
                IJ.log("Signal Extraction: Cancelled by user.");
                return false;
            }
            // Save updated config
            SessionConfigIO.writeToDirectory(directory, config);
        }

        // Find corrected stacks (or fall back to raw TIFs)
        String correctedDir = directory + File.separator + ".circadian" + File.separator + "corrected";
        String roiDir = directory + File.separator + ".circadian" + File.separator + "ROIs";
        String tracesDir = directory + File.separator + ".circadian" + File.separator + "traces";

        // Ensure traces output dir exists
        new File(tracesDir).mkdirs();

        // Find corrected stacks
        File corrDir = new File(correctedDir);
        String[] correctedFiles = null;
        if (corrDir.exists()) {
            correctedFiles = corrDir.list(new FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    String lower = name.toLowerCase();
                    return lower.endsWith(".tif") || lower.endsWith(".tiff");
                }
            });
        }

        // Find raw TIFs as fallback
        String[] rawFiles = new File(directory).list(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                String lower = name.toLowerCase();
                return lower.endsWith(".tif") || lower.endsWith(".tiff");
            }
        });

        boolean useCorrected = correctedFiles != null && correctedFiles.length > 0;
        String[] filesToProcess = useCorrected ? correctedFiles : rawFiles;
        String baseDir = useCorrected ? correctedDir : directory;

        if (filesToProcess == null || filesToProcess.length == 0) {
            IJ.error("Signal Extraction", "No TIF files found.");
            return false;
        }

        Arrays.sort(filesToProcess);
        IJ.log("Signal Extraction: Processing " + filesToProcess.length + " file(s) from " +
                (useCorrected ? "corrected/" : "raw directory"));

        int processed = 0;
        for (int fi = 0; fi < filesToProcess.length; fi++) {
            String filename = filesToProcess[fi];
            IJ.log("");
            IJ.log("[" + (fi + 1) + "/" + filesToProcess.length + "] " + filename);
            IJ.showProgress(fi, filesToProcess.length);

            // Load image
            String imagePath = baseDir + File.separator + filename;
            ImagePlus imp = IJ.openImage(imagePath);
            if (imp == null) {
                IJ.log("  WARNING: Could not open " + imagePath);
                continue;
            }

            // Determine base name for ROI lookup
            String baseName = getBaseName(filename);

            // Load ROIs — try with full name first (including _corrected), then stripped
            Roi[] rois = new Roi[0];
            String roiPath;

            // Try full name with _rois suffix (e.g. VID22_D2_1_stack_corrected_rois.zip)
            roiPath = roiDir + File.separator + baseName + "_rois.zip";
            rois = RoiIO.loadRoisFromZip(roiPath);

            // Try full name without _rois suffix
            if (rois.length == 0) {
                roiPath = roiDir + File.separator + baseName + ".zip";
                rois = RoiIO.loadRoisFromZip(roiPath);
            }

            // Strip _corrected and/or _stack and retry
            if (rois.length == 0) {
                String strippedName = baseName;
                if (strippedName.endsWith("_corrected")) {
                    strippedName = strippedName.substring(0, strippedName.length() - "_corrected".length());
                }
                if (strippedName.endsWith("_stack")) {
                    strippedName = strippedName.substring(0, strippedName.length() - "_stack".length());
                }
                if (!strippedName.equals(baseName)) {
                    roiPath = roiDir + File.separator + strippedName + "_rois.zip";
                    rois = RoiIO.loadRoisFromZip(roiPath);
                    if (rois.length == 0) {
                        roiPath = roiDir + File.separator + strippedName + ".zip";
                        rois = RoiIO.loadRoisFromZip(roiPath);
                    }
                }
            }
            if (rois.length == 0) {
                IJ.log("  WARNING: No ROIs found for " + baseName + " in " + roiDir);
                imp.close();
                continue;
            }

            IJ.log("  Loaded " + rois.length + " ROI(s)");

            // Extract raw traces
            double[][] rawTraces = TraceExtractor.extractTraces(imp, rois);
            int nRois = rawTraces.length;
            int nFrames = rawTraces[0].length;
            IJ.log("  Extracted traces: " + nRois + " ROIs x " + nFrames + " frames");

            // Apply temporal crop
            int startFrame = Math.max(0, config.cropStartFrame - 1); // Convert 1-based to 0-based
            int endFrame = config.cropEndFrame > 0 ? Math.min(config.cropEndFrame, nFrames) : nFrames;
            if (startFrame > 0 || endFrame < nFrames) {
                rawTraces = cropTraces(rawTraces, startFrame, endFrame);
                nFrames = rawTraces[0].length;
                IJ.log("  Temporal crop: frames " + (startFrame + 1) + " to " + endFrame +
                        " (" + nFrames + " frames)");
            }

            // Build ROI name headers
            String[] roiHeaders = new String[nRois];
            for (int r = 0; r < nRois; r++) {
                String roiName = rois[r].getName();
                if (roiName == null || roiName.isEmpty()) {
                    roiName = "ROI_" + (r + 1);
                }
                roiHeaders[r] = roiName;
            }

            // Save raw traces
            String rawPath = tracesDir + File.separator + "Raw_Traces_" + baseName + ".csv";
            CsvWriter.writeTraces(rawPath, roiHeaders, rawTraces, config.frameIntervalMin);

            // Compute dF/F if enabled
            if (config.outputDeltaFF) {
                double[][] deltaFF = computeDeltaFF(rawTraces, config);
                String deltaPath = tracesDir + File.separator + "DeltaF_F_Traces_" + baseName + ".csv";
                CsvWriter.writeTraces(deltaPath, roiHeaders, deltaFF, config.frameIntervalMin);
                IJ.log("  Saved dF/F traces");
            }

            // Compute Z-score if enabled
            if (config.outputZscore) {
                double[][] zscore = computeZscore(rawTraces);
                String zPath = tracesDir + File.separator + "Zscore_Traces_" + baseName + ".csv";
                CsvWriter.writeTraces(zPath, roiHeaders, zscore, config.frameIntervalMin);
                IJ.log("  Saved Z-score traces");
            }

            imp.close();
            processed++;
        }

        IJ.showProgress(1.0);
        IJ.log("");
        IJ.log("Signal Extraction: Complete. Processed " + processed + "/" + filesToProcess.length + " file(s).");
        return true;
    }

    /**
     * Shows the Signal Extraction configuration dialog.
     *
     * @param config the config to populate with user choices
     * @return true if OK pressed, false if cancelled
     */
    private boolean showDialog(SessionConfig config) {
        PipelineDialog dlg = new PipelineDialog("CHRONOS - Signal Extraction");

        dlg.addHeader("Baseline (F0) Calculation");

        final JComboBox<String> f0Combo = dlg.addChoice("F0 Method:", F0_METHODS, config.f0Method);

        // Conditional fields — we create them all but toggle visibility
        final JTextField windowField = dlg.addNumericField("F0 Window Size (frames):", config.f0WindowSize, 0);
        final JTextField percentileField = dlg.addNumericField("F0 Percentile (%):", config.f0Percentile, 1);
        final JTextField nFramesField = dlg.addNumericField("F0 N Frames:", config.f0NFrames, 0);

        // Get parent rows for visibility toggling
        final Container windowRow = windowField.getParent();
        final Container percentileRow = percentileField.getParent();
        final Container nFramesRow = nFramesField.getParent();

        // Set initial visibility based on default method
        updateF0FieldVisibility(config.f0Method, windowRow, percentileRow, nFramesRow);

        // Add listener for dynamic visibility
        f0Combo.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                String selected = (String) f0Combo.getSelectedItem();
                updateF0FieldVisibility(selected, windowRow, percentileRow, nFramesRow);
            }
        });

        dlg.addSpacer(8);
        dlg.addHeader("Temporal Crop");
        final JTextField startFrameField = dlg.addNumericField("Start Frame:", config.cropStartFrame, 0);
        final JTextField endFrameField = dlg.addNumericField("End Frame (0 = all):", config.cropEndFrame, 0);

        dlg.addSpacer(8);
        dlg.addHeader("Output Options");
        dlg.addToggle("dF/F Traces", config.outputDeltaFF);
        dlg.addToggle("Z-score Traces", config.outputZscore);

        if (!dlg.showDialog()) {
            return false;
        }

        // Read values back
        config.f0Method = dlg.getNextChoice();
        config.f0WindowSize = (int) dlg.getNextNumber();
        config.f0Percentile = dlg.getNextNumber();
        config.f0NFrames = (int) dlg.getNextNumber();
        config.cropStartFrame = (int) dlg.getNextNumber();
        config.cropEndFrame = (int) dlg.getNextNumber();
        config.outputDeltaFF = dlg.getNextBoolean();
        config.outputZscore = dlg.getNextBoolean();

        return true;
    }

    /**
     * Toggles visibility of F0 parameter fields based on the selected method.
     */
    private void updateF0FieldVisibility(String method,
                                         Container windowRow,
                                         Container percentileRow,
                                         Container nFramesRow) {
        boolean isSlidingPercentile = "Sliding Percentile".equals(method);
        boolean isFirstN = "First N Frames".equals(method);

        windowRow.setVisible(isSlidingPercentile);
        percentileRow.setVisible(isSlidingPercentile);
        nFramesRow.setVisible(isFirstN);

        // Revalidate the dialog
        Container top = windowRow;
        while (top.getParent() != null) {
            top = top.getParent();
        }
        top.revalidate();
        top.repaint();
    }

    /**
     * Computes dF/F traces using the configured F0 method.
     */
    private double[][] computeDeltaFF(double[][] rawTraces, SessionConfig config) {
        int nRois = rawTraces.length;
        int nFrames = rawTraces[0].length;
        double[][] deltaFF = new double[nRois][nFrames];

        for (int r = 0; r < nRois; r++) {
            double[] trace = rawTraces[r];

            if ("Sliding Percentile".equals(config.f0Method)) {
                double[] f0 = BaselineCalculator.slidingPercentile(
                        trace, config.f0WindowSize, config.f0Percentile);
                for (int f = 0; f < nFrames; f++) {
                    deltaFF[r][f] = (f0[f] != 0) ? (trace[f] - f0[f]) / f0[f] : 0;
                }
            } else if ("First N Frames".equals(config.f0Method)) {
                double f0 = BaselineCalculator.firstNFramesMean(trace, config.f0NFrames);
                for (int f = 0; f < nFrames; f++) {
                    deltaFF[r][f] = (f0 != 0) ? (trace[f] - f0) / f0 : 0;
                }
            } else if ("Whole-Trace Mean".equals(config.f0Method)) {
                double f0 = BaselineCalculator.wholeTraceMean(trace);
                for (int f = 0; f < nFrames; f++) {
                    deltaFF[r][f] = (f0 != 0) ? (trace[f] - f0) / f0 : 0;
                }
            } else if ("Whole-Trace Median".equals(config.f0Method)) {
                double f0 = BaselineCalculator.wholeTraceMedian(trace);
                for (int f = 0; f < nFrames; f++) {
                    deltaFF[r][f] = (f0 != 0) ? (trace[f] - f0) / f0 : 0;
                }
            } else if ("Exponential Fit".equals(config.f0Method)) {
                double[] f0 = BaselineCalculator.exponentialFit(trace);
                for (int f = 0; f < nFrames; f++) {
                    deltaFF[r][f] = (f0[f] != 0) ? (trace[f] - f0[f]) / f0[f] : 0;
                }
            } else {
                // Default: whole-trace mean
                double f0 = BaselineCalculator.wholeTraceMean(trace);
                for (int f = 0; f < nFrames; f++) {
                    deltaFF[r][f] = (f0 != 0) ? (trace[f] - f0) / f0 : 0;
                }
            }
        }

        return deltaFF;
    }

    /**
     * Computes Z-score traces: (F - mean) / std for each ROI.
     */
    private double[][] computeZscore(double[][] rawTraces) {
        int nRois = rawTraces.length;
        int nFrames = rawTraces[0].length;
        double[][] zscore = new double[nRois][nFrames];

        for (int r = 0; r < nRois; r++) {
            double[] trace = rawTraces[r];

            // Compute mean
            double sum = 0;
            for (int f = 0; f < nFrames; f++) {
                sum += trace[f];
            }
            double mean = sum / nFrames;

            // Compute std
            double sumSq = 0;
            for (int f = 0; f < nFrames; f++) {
                double diff = trace[f] - mean;
                sumSq += diff * diff;
            }
            double std = Math.sqrt(sumSq / nFrames);

            // Compute Z-score
            for (int f = 0; f < nFrames; f++) {
                zscore[r][f] = (std > 0) ? (trace[f] - mean) / std : 0;
            }
        }

        return zscore;
    }

    /**
     * Crops traces to [startFrame, endFrame) range.
     */
    private double[][] cropTraces(double[][] traces, int startFrame, int endFrame) {
        int nRois = traces.length;
        int newLength = endFrame - startFrame;
        double[][] cropped = new double[nRois][newLength];
        for (int r = 0; r < nRois; r++) {
            System.arraycopy(traces[r], startFrame, cropped[r], 0, newLength);
        }
        return cropped;
    }

    /**
     * Extracts the base name from a filename (without extension).
     */
    private String getBaseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
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
        return "Signal Extraction";
    }

    @Override
    public int getIndex() {
        return 3;
    }
}
