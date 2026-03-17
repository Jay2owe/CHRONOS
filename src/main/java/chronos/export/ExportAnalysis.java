package chronos.export;

import chronos.Analysis;
import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.io.CsvReader;
import chronos.rhythm.RhythmResult;
import chronos.ui.PipelineDialog;

import ij.IJ;

import javax.swing.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Module 6: Export.
 * <p>
 * Consolidates all analysis outputs (CSVs, visualizations) into an export
 * directory, generates an Experiment_Parameters.csv, computes summary
 * statistics across all recordings, and produces an Excel workbook.
 */
public class ExportAnalysis implements Analysis {

    private boolean headless = false;
    private int parallelThreads = 1;

    @Override
    public boolean execute(String directory) {
        IJ.log("===== CHRONOS - Export =====");

        SessionConfig config = SessionConfigIO.readFromDirectory(directory);

        // --- Dialog ---
        String outputDir;
        String imageFormat;
        boolean includeRawTraces;

        if (!headless) {
            PipelineDialog dlg = new PipelineDialog("CHRONOS -- Export");
            dlg.addHeader("Export Settings");

            String defaultExportDir = directory + File.separator
                    + ".circadian" + File.separator + "exports";
            JTextField dirField = dlg.addStringField("Output Directory:", defaultExportDir, 30);
            JButton browseBtn = dlg.addButton("Browse...");
            browseBtn.addActionListener(e -> {
                JFileChooser chooser = new JFileChooser(dirField.getText());
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    dirField.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            });

            dlg.addChoice("Image Format:", new String[]{"PNG", "TIFF"}, config.exportImageFormat);
            dlg.addToggle("Include Raw Traces in Excel:", config.exportIncludeRawTraces);

            if (!dlg.showDialog()) {
                IJ.log("Export cancelled.");
                return false;
            }

            outputDir = dlg.getNextString();
            imageFormat = dlg.getNextChoice();
            includeRawTraces = dlg.getNextBoolean();
        } else {
            outputDir = directory + File.separator + ".circadian" + File.separator + "exports";
            imageFormat = config.exportImageFormat;
            includeRawTraces = config.exportIncludeRawTraces;
        }

        // Save export settings back to config
        config.exportImageFormat = imageFormat;
        config.exportIncludeRawTraces = includeRawTraces;
        SessionConfigIO.writeToDirectory(directory, config);

        // Ensure output dir exists
        File outDir = new File(outputDir);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        String circadianDir = directory + File.separator + ".circadian";

        // --- Step A: Copy/consolidate CSVs ---
        IJ.log("Consolidating CSVs...");
        copyDirectoryContents(circadianDir + File.separator + "traces", outputDir, ".csv");
        copyDirectoryContents(circadianDir + File.separator + "rhythm", outputDir, ".csv");

        // --- Step B: Copy visualization images ---
        IJ.log("Copying visualization images...");
        String vizDir = circadianDir + File.separator + "visualizations";
        String imgExt = "PNG".equalsIgnoreCase(imageFormat) ? ".png" : ".tif";
        copyDirectoryContents(vizDir, outputDir, imgExt);
        // Also copy .tif visualizations regardless (spatial maps are often TIFF)
        if (".png".equals(imgExt)) {
            copyDirectoryContents(vizDir, outputDir, ".tif");
        }

        // --- Step C: Generate Experiment_Parameters.csv ---
        IJ.log("Writing Experiment_Parameters.csv...");
        writeParametersCsv(outputDir + File.separator + "Experiment_Parameters.csv", config);

        // --- Step D: Load all trace and rhythm data ---
        IJ.log("Loading trace and rhythm data...");

        Map<String, double[][]> rawTraces = new LinkedHashMap<String, double[][]>();
        Map<String, String[]> rawHeaders = new LinkedHashMap<String, String[]>();
        Map<String, double[][]> deltafTraces = new LinkedHashMap<String, double[][]>();
        Map<String, String[]> deltafHeaders = new LinkedHashMap<String, String[]>();
        Map<String, List<RhythmResult>> rhythmResults = new LinkedHashMap<String, List<RhythmResult>>();

        String tracesDir = circadianDir + File.separator + "traces";
        String rhythmDir = circadianDir + File.separator + "rhythm";

        // Discover files from traces directory
        File tracesDirFile = new File(tracesDir);
        if (tracesDirFile.exists()) {
            File[] traceFiles = tracesDirFile.listFiles();
            if (traceFiles != null) {
                for (File tf : traceFiles) {
                    String name = tf.getName();
                    if (name.startsWith("DeltaF_F_Traces_") && name.endsWith(".csv")) {
                        String baseName = name.substring("DeltaF_F_Traces_".length(),
                                name.length() - ".csv".length());
                        String[][] hdrOut = new String[1][];
                        double[][] data = CsvReader.readTraces(tf.getAbsolutePath(), hdrOut);
                        if (data != null) {
                            deltafTraces.put(baseName, data);
                            if (hdrOut[0] != null) {
                                deltafHeaders.put(baseName, hdrOut[0]);
                            }
                        }
                    }
                    if (includeRawTraces && name.startsWith("Raw_Traces_") && name.endsWith(".csv")) {
                        String baseName = name.substring("Raw_Traces_".length(),
                                name.length() - ".csv".length());
                        String[][] hdrOut = new String[1][];
                        double[][] data = CsvReader.readTraces(tf.getAbsolutePath(), hdrOut);
                        if (data != null) {
                            rawTraces.put(baseName, data);
                            if (hdrOut[0] != null) {
                                rawHeaders.put(baseName, hdrOut[0]);
                            }
                        }
                    }
                }
            }
        }

        // Load rhythm summaries
        File rhythmDirFile = new File(rhythmDir);
        if (rhythmDirFile.exists()) {
            File[] rhythmFiles = rhythmDirFile.listFiles();
            if (rhythmFiles != null) {
                for (File rf : rhythmFiles) {
                    String name = rf.getName();
                    if (name.startsWith("Rhythm_Summary_") && name.endsWith(".csv")) {
                        String baseName = name.substring("Rhythm_Summary_".length(),
                                name.length() - ".csv".length());
                        List<RhythmResult> results = loadRhythmSummary(rf.getAbsolutePath());
                        if (!results.isEmpty()) {
                            rhythmResults.put(baseName, results);
                        }
                    }
                }
            }
        }

        // --- Step E: Generate Excel workbook ---
        IJ.log("Generating Excel workbook...");
        String xlsxPath = outputDir + File.separator + "CHRONOS_Summary.xlsx";
        ExcelExporter.export(xlsxPath, config,
                includeRawTraces ? rawTraces : null,
                includeRawTraces ? rawHeaders : null,
                deltafTraces, deltafHeaders,
                rhythmResults, includeRawTraces);

        IJ.log("===== Export complete =====");
        IJ.log("Output directory: " + outputDir);
        return true;
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
        return "Export";
    }

    @Override
    public int getIndex() {
        return 6;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Copies files with the given extension from srcDir to destDir.
     */
    private void copyDirectoryContents(String srcDir, String destDir, String extension) {
        File src = new File(srcDir);
        if (!src.exists() || !src.isDirectory()) return;

        File dest = new File(destDir);
        if (!dest.exists()) {
            dest.mkdirs();
        }

        File[] files = src.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(extension.toLowerCase())) {
                File target = new File(dest, f.getName());
                if (!target.exists() || f.lastModified() > target.lastModified()) {
                    copyFile(f, target);
                }
            }
        }
    }

    private void copyFile(File src, File dest) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(src);
            fos = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        } catch (IOException e) {
            IJ.log("Warning: Could not copy " + src.getName() + ": " + e.getMessage());
        } finally {
            if (fis != null) { try { fis.close(); } catch (IOException ignored) { } }
            if (fos != null) { try { fos.close(); } catch (IOException ignored) { } }
        }
    }

    /**
     * Writes all SessionConfig parameters to a CSV file.
     */
    private void writeParametersCsv(String path, SessionConfig cfg) {
        PrintWriter pw = null;
        try {
            File parent = new File(path).getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("Parameter,Value");
            pw.println("Date," + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            pw.println("Reporter Type," + cfg.reporterType);
            pw.println("Frame Interval (min)," + cfg.frameIntervalMin);
            pw.println("Binning Enabled," + cfg.binningEnabled);
            pw.println("Bin Factor," + cfg.binFactor);
            pw.println("Bin Method," + cfg.binMethod);
            pw.println("Motion Correction," + cfg.motionCorrectionEnabled);
            pw.println("Motion Correction Reference," + cfg.motionCorrectionReference);
            pw.println("Background Method," + cfg.backgroundMethod);
            pw.println("Background Radius," + cfg.backgroundRadius);
            pw.println("Bleach Method," + cfg.bleachMethod);
            pw.println("Bleach Percentile Window," + cfg.bleachPercentileWindow);
            pw.println("Bleach Percentile," + cfg.bleachPercentile);
            pw.println("Spatial Filter Type," + cfg.spatialFilterType);
            pw.println("Spatial Filter Radius," + cfg.spatialFilterRadius);
            pw.println("Temporal Filter Type," + cfg.temporalFilterType);
            pw.println("Temporal Filter Window," + cfg.temporalFilterWindow);
            pw.println("F0 Method," + cfg.f0Method);
            pw.println("F0 Window Size," + cfg.f0WindowSize);
            pw.println("F0 Percentile," + cfg.f0Percentile);
            pw.println("F0 N Frames," + cfg.f0NFrames);
            pw.println("Crop Start Frame," + cfg.cropStartFrame);
            pw.println("Crop End Frame," + cfg.cropEndFrame);
            pw.println("Output dF/F," + cfg.outputDeltaFF);
            pw.println("Output Z-score," + cfg.outputZscore);
            pw.println("Period Min (h)," + cfg.periodMinHours);
            pw.println("Period Max (h)," + cfg.periodMaxHours);
            pw.println("Detrending Method," + cfg.detrendingMethod);
            pw.println("Run FFT," + cfg.runFFT);
            pw.println("Run Autocorrelation," + cfg.runAutocorrelation);
            pw.println("Run Lomb-Scargle," + cfg.runLombScargle);
            pw.println("Run Wavelet," + cfg.runWavelet);
            pw.println("Cosinor Model," + cfg.cosinorModel);
            pw.println("Significance Threshold," + cfg.significanceThreshold);

            IJ.log("Wrote Experiment_Parameters.csv");
        } catch (IOException e) {
            IJ.log("Warning: Could not write parameters CSV: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Loads a Rhythm_Summary CSV file and parses it into RhythmResult objects.
     * Expected columns: ROI, Period, Phase_h, Amplitude, Mesor, Damping_tau,
     * R_squared, p_value, Is_Rhythmic
     */
    private List<RhythmResult> loadRhythmSummary(String path) {
        List<RhythmResult> results = new ArrayList<RhythmResult>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String header = br.readLine();
            if (header == null) return results;

            // Parse header to find column indices
            String[] cols = header.split(",");
            int iRoi = findCol(cols, "ROI");
            int iPeriod = findCol(cols, "Period");
            int iPhaseH = findCol(cols, "Phase_h");
            int iPhaseRad = findCol(cols, "Phase_rad");
            int iAmplitude = findCol(cols, "Amplitude");
            int iMesor = findCol(cols, "Mesor");
            int iDamping = findCol(cols, "Damping_tau");
            int iRSq = findCol(cols, "R_squared");
            int iPVal = findCol(cols, "p_value");
            int iRhythmic = findCol(cols, "Is_Rhythmic");

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");

                String roiName = getCol(parts, iRoi, "Unknown");
                double period = getColDouble(parts, iPeriod);
                double phaseH = getColDouble(parts, iPhaseH);
                double phaseRad = iPhaseRad >= 0 ? getColDouble(parts, iPhaseRad)
                        : (phaseH * 2.0 * Math.PI / 24.0);
                double amplitude = getColDouble(parts, iAmplitude);
                double mesor = getColDouble(parts, iMesor);
                double dampingTau = getColDouble(parts, iDamping);
                double rSquared = getColDouble(parts, iRSq);
                double pValue = getColDouble(parts, iPVal);

                boolean isRhythmic = false;
                if (iRhythmic >= 0 && iRhythmic < parts.length) {
                    String val = parts[iRhythmic].trim();
                    isRhythmic = "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val)
                            || "1".equals(val);
                }

                results.add(new RhythmResult(roiName, period, phaseRad, phaseH,
                        amplitude, mesor, dampingTau, rSquared, pValue, isRhythmic));
            }
        } catch (IOException e) {
            IJ.log("Warning: Could not read rhythm summary: " + e.getMessage());
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) { }
            }
        }
        return results;
    }

    private int findCol(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private String getCol(String[] parts, int idx, String def) {
        if (idx < 0 || idx >= parts.length) return def;
        String val = parts[idx].trim();
        return val.isEmpty() ? def : val;
    }

    private double getColDouble(String[] parts, int idx) {
        if (idx < 0 || idx >= parts.length) return Double.NaN;
        String val = parts[idx].trim();
        if (val.isEmpty() || "NaN".equalsIgnoreCase(val)) return Double.NaN;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
