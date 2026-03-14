package chronos.visualization;

import chronos.Analysis;
import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.io.CsvReader;
import chronos.io.RoiIO;
import chronos.rhythm.*;
import chronos.ui.PipelineDialog;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.plugin.ZProjector;
import ij.gui.Roi;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Module 5: Visualization.
 * <p>
 * Loads data from .circadian/traces/ and .circadian/rhythm/,
 * generates selected visualizations, and saves to .circadian/visualizations/.
 */
public class VisualizationAnalysis implements Analysis {

    private boolean headless = false;
    private int parallelThreads = 1;

    @Override
    public boolean execute(String directory) {
        IJ.log("===== CHRONOS - Visualization =====");

        // Load config
        SessionConfig config = SessionConfigIO.readFromDirectory(directory);

        // Show dialog unless headless
        if (!headless) {
            if (!showDialog(config)) {
                IJ.log("Visualization: Cancelled by user.");
                return false;
            }
            SessionConfigIO.writeToDirectory(directory, config);
        }

        // Setup directories
        String circadian = directory + File.separator + ".circadian";
        String tracesDir = circadian + File.separator + "traces";
        String rhythmDir = circadian + File.separator + "rhythm";
        String vizDir = circadian + File.separator + "visualizations";
        String roisDir = circadian + File.separator + "ROIs";
        String correctedDir = circadian + File.separator + "corrected";

        new File(vizDir).mkdirs();

        // Find trace files
        File traceFolder = new File(tracesDir);
        if (!traceFolder.exists()) {
            IJ.error("Visualization", "No traces directory found at: " + tracesDir);
            return false;
        }

        String[] traceFiles = traceFolder.list(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.startsWith("DeltaF_F_Traces_") && name.endsWith(".csv");
            }
        });

        if (traceFiles == null || traceFiles.length == 0) {
            IJ.error("Visualization", "No dF/F trace files found in: " + tracesDir);
            return false;
        }

        Arrays.sort(traceFiles);
        double frameIntervalHours = config.frameIntervalMin / 60.0;

        int processed = 0;
        for (int fi = 0; fi < traceFiles.length; fi++) {
            String filename = traceFiles[fi];
            IJ.log("");
            IJ.log("[" + (fi + 1) + "/" + traceFiles.length + "] " + filename);
            IJ.showProgress(fi, traceFiles.length);

            // Extract base name
            String baseName = filename;
            if (baseName.startsWith("DeltaF_F_Traces_")) {
                baseName = baseName.substring("DeltaF_F_Traces_".length());
            }
            if (baseName.endsWith(".csv")) {
                baseName = baseName.substring(0, baseName.length() - 4);
            }

            // Load dF/F traces
            String tracePath = tracesDir + File.separator + filename;
            String[][] headerHolder = new String[1][];
            double[][] traces = CsvReader.readTraces(tracePath, headerHolder);
            if (traces == null || traces.length == 0) {
                IJ.log("  WARNING: Could not read traces from " + tracePath);
                continue;
            }
            String[] roiNames = headerHolder[0];
            int nRois = traces.length;
            int nFrames = traces[0].length;

            // Build time array in hours
            double[] timesH = new double[nFrames];
            for (int i = 0; i < nFrames; i++) {
                timesH[i] = i * frameIntervalHours;
            }

            // Load rhythm results
            List<RhythmResult> rhythmResults = loadRhythmSummary(rhythmDir, baseName);
            boolean hasRhythmResults = !rhythmResults.isEmpty();

            // Load cosinor fits
            double[][] cosinorFits = null;
            String fitsPath = rhythmDir + File.separator + "Cosinor_Fits_" + baseName + ".csv";
            if (new File(fitsPath).exists()) {
                String[][] fitsHeaderHolder = new String[1][];
                cosinorFits = CsvReader.readTraces(fitsPath, fitsHeaderHolder);
            }

            // Build CosinorResult array from RhythmResults
            CosinorResult[] cosinorResults = new CosinorResult[nRois];
            for (int r = 0; r < nRois; r++) {
                RhythmResult rr = findResult(rhythmResults, roiNames[r]);
                if (rr != null) {
                    double[] fitCurve = (cosinorFits != null && r < cosinorFits.length)
                            ? cosinorFits[r] : null;
                    cosinorResults[r] = new CosinorResult(
                            rr.mesor, rr.amplitude, rr.period,
                            rr.phaseRad, rr.phaseHours, rr.dampingTau,
                            rr.rSquared, rr.pValue, fitCurve);
                }
            }

            // Rayleigh test on rhythmic phases
            RayleighResult rayleigh = null;
            if (hasRhythmResults) {
                List<Double> rhythmicPhases = new ArrayList<Double>();
                for (RhythmResult rr : rhythmResults) {
                    if (rr.isRhythmic && !Double.isNaN(rr.phaseRad)) {
                        rhythmicPhases.add(rr.phaseRad);
                    }
                }
                if (rhythmicPhases.size() > 1) {
                    double[] phasesArr = new double[rhythmicPhases.size()];
                    for (int i = 0; i < phasesArr.length; i++) {
                        phasesArr[i] = rhythmicPhases.get(i);
                    }
                    rayleigh = RayleighTest.test(phasesArr);
                }
            }

            // === Generate visualizations ===

            // 1. Time-Series Plots
            if (config.vizTimeSeries) {
                IJ.log("  Generating time-series plots...");
                ImagePlus tsPlot = TimeSeriesPlotter.plotAll(timesH, traces, cosinorFits,
                        roiNames, cosinorResults);
                if (tsPlot != null) {
                    saveImage(tsPlot, vizDir, "TimeSeries_" + baseName + ".png");
                }
            }

            // 2. Kymograph
            if (config.vizKymograph) {
                IJ.log("  Generating kymograph...");
                ImagePlus kymo = KymographGenerator.generateFromTraces(traces, roiNames,
                        config.frameIntervalMin);
                if (kymo != null) {
                    saveImage(kymo, vizDir, "Kymograph_" + baseName + ".tif");
                }
            }

            // 3. Phase Map (requires ROIs and a reference image)
            if (config.vizPhaseMap && hasRhythmResults) {
                IJ.log("  Generating phase map...");
                generateSpatialMap(directory, correctedDir, roisDir, baseName,
                        rhythmResults, roiNames, vizDir, "phase");
            }

            // 4. Period Map
            if (config.vizPeriodMap && hasRhythmResults) {
                IJ.log("  Generating period map...");
                generateSpatialMap(directory, correctedDir, roisDir, baseName,
                        rhythmResults, roiNames, vizDir, "period");
            }

            // 5. Amplitude Map
            if (config.vizAmplitudeMap && hasRhythmResults) {
                IJ.log("  Generating amplitude map...");
                generateSpatialMap(directory, correctedDir, roisDir, baseName,
                        rhythmResults, roiNames, vizDir, "amplitude");
            }

            // 6. Raster Plot
            if (config.vizRasterPlot && hasRhythmResults) {
                IJ.log("  Generating raster plot...");
                double[] phases = new double[nRois];
                for (int r = 0; r < nRois; r++) {
                    RhythmResult rr = findResult(rhythmResults, roiNames[r]);
                    phases[r] = (rr != null) ? rr.phaseHours : Double.NaN;
                }
                ImagePlus raster = RasterPlotGenerator.generate(traces, phases, roiNames,
                        config.frameIntervalMin);
                if (raster != null) {
                    saveImage(raster, vizDir, "RasterPlot_" + baseName + ".tif");
                }
            }

            // 7. Polar Phase Plot
            if (config.vizPolarPlot && hasRhythmResults) {
                IJ.log("  Generating polar phase plot...");
                List<Double> allPhases = new ArrayList<Double>();
                for (RhythmResult rr : rhythmResults) {
                    if (rr.isRhythmic && !Double.isNaN(rr.phaseRad)) {
                        allPhases.add(rr.phaseRad);
                    }
                }
                if (!allPhases.isEmpty()) {
                    double[] pArr = new double[allPhases.size()];
                    for (int i = 0; i < allPhases.size(); i++) pArr[i] = allPhases.get(i);
                    ImagePlus polar = PolarPlotGenerator.generate(pArr, rayleigh, 500);
                    if (polar != null) {
                        saveImage(polar, vizDir, "PolarPlot_" + baseName + ".png");
                    }
                }
            }

            // 8. Wavelet Scalograms
            if (config.vizScalogram) {
                IJ.log("  Generating scalograms...");
                generateScalograms(rhythmDir, vizDir, baseName, roiNames, timesH,
                        frameIntervalHours, config);
            }

            // 9. Summary Dashboard
            if (hasRhythmResults) {
                IJ.log("  Generating summary dashboard...");
                ImagePlus dashboard = SummaryDashboard.generate(timesH, traces,
                        rhythmResults, rayleigh);
                if (dashboard != null) {
                    saveImage(dashboard, vizDir, "Dashboard_" + baseName + ".png");
                }
            }

            processed++;
        }

        IJ.showProgress(1.0);
        IJ.log("");
        IJ.log("Visualization: Complete. Processed " + processed + "/" + traceFiles.length + " file(s).");
        return true;
    }

    /**
     * Shows the visualization configuration dialog.
     */
    private boolean showDialog(SessionConfig config) {
        PipelineDialog dlg = new PipelineDialog("CHRONOS - Visualization");

        dlg.addHeader("Select Visualizations");
        dlg.addToggle("Time-Series Plots", config.vizTimeSeries);
        dlg.addToggle("Kymograph", config.vizKymograph);
        dlg.addToggle("Phase Map", config.vizPhaseMap);
        dlg.addToggle("Period Map", config.vizPeriodMap);
        dlg.addToggle("Amplitude Map", config.vizAmplitudeMap);
        dlg.addToggle("Raster Plot", config.vizRasterPlot);
        dlg.addToggle("Polar Phase Plot", config.vizPolarPlot);
        dlg.addToggle("Wavelet Scalograms", config.vizScalogram);

        dlg.addSpacer(8);
        dlg.addHelpText("Wavelet scalograms are only generated if wavelet analysis was " +
                "run in the Rhythm Analysis module.");

        if (!dlg.showDialog()) {
            return false;
        }

        config.vizTimeSeries = dlg.getNextBoolean();
        config.vizKymograph = dlg.getNextBoolean();
        config.vizPhaseMap = dlg.getNextBoolean();
        config.vizPeriodMap = dlg.getNextBoolean();
        config.vizAmplitudeMap = dlg.getNextBoolean();
        config.vizRasterPlot = dlg.getNextBoolean();
        config.vizPolarPlot = dlg.getNextBoolean();
        config.vizScalogram = dlg.getNextBoolean();

        return true;
    }

    /**
     * Generates spatial maps (phase, period, or amplitude) for a given recording.
     */
    private void generateSpatialMap(String directory, String correctedDir, String roisDir,
                                     String baseName, List<RhythmResult> results,
                                     String[] roiNames, String vizDir, String mapType) {
        // Try to load a mean projection image
        ImagePlus meanProj = loadMeanProjection(directory, correctedDir, baseName);
        if (meanProj == null) {
            IJ.log("  WARNING: Could not load image for spatial map. Skipping " + mapType + " map.");
            return;
        }

        // Load ROIs
        Roi[] rois = loadRois(roisDir, baseName);
        if (rois == null || rois.length == 0) {
            IJ.log("  WARNING: No ROIs found for spatial map. Skipping " + mapType + " map.");
            meanProj.close();
            return;
        }

        // Build arrays aligned with ROIs
        int nRois = rois.length;
        double[] values = new double[nRois];
        for (int i = 0; i < nRois; i++) {
            String name = rois[i].getName();
            RhythmResult rr = findResult(results, name);
            if (rr == null) {
                values[i] = Double.NaN;
            } else if ("phase".equals(mapType)) {
                values[i] = rr.phaseHours;
            } else if ("period".equals(mapType)) {
                values[i] = rr.period;
            } else {
                values[i] = rr.amplitude;
            }
        }

        ImagePlus map = null;
        if ("phase".equals(mapType)) {
            map = SpatialMapGenerator.phaseMap(meanProj, rois, values, 24.0);
        } else if ("period".equals(mapType)) {
            double minP = 18, maxP = 30;
            for (int i = 0; i < nRois; i++) {
                if (!Double.isNaN(values[i])) {
                    if (values[i] < minP) minP = values[i];
                    if (values[i] > maxP) maxP = values[i];
                }
            }
            map = SpatialMapGenerator.periodMap(meanProj, rois, values, minP, maxP);
        } else {
            map = SpatialMapGenerator.amplitudeMap(meanProj, rois, values);
        }

        if (map != null) {
            String suffix = mapType.substring(0, 1).toUpperCase() + mapType.substring(1);
            saveImage(map, vizDir, suffix + "Map_" + baseName + ".png");
        }

        meanProj.close();
    }

    /**
     * Loads a mean projection for spatial map generation.
     * Tries corrected stack first, then raw TIF.
     */
    private ImagePlus loadMeanProjection(String directory, String correctedDir, String baseName) {
        // Try corrected
        String correctedPath = correctedDir + File.separator + baseName + "_corrected.tif";
        ImagePlus imp = null;
        if (new File(correctedPath).exists()) {
            imp = IJ.openImage(correctedPath);
        }
        // Try raw
        if (imp == null) {
            String rawPath = directory + File.separator + baseName + ".tif";
            if (new File(rawPath).exists()) {
                imp = IJ.openImage(rawPath);
            }
        }
        if (imp == null) return null;

        // Compute mean projection
        ZProjector zp = new ZProjector(imp);
        zp.setMethod(ZProjector.AVG_METHOD);
        zp.doProjection();
        ImagePlus proj = zp.getProjection();
        imp.close();
        return proj;
    }

    /**
     * Loads ROIs for a given recording.
     */
    private Roi[] loadRois(String roisDir, String baseName) {
        String roiPath = roisDir + File.separator + baseName + "_rois.zip";
        if (new File(roiPath).exists()) {
            return RoiIO.loadRoisFromZip(roiPath);
        }

        // Try any ROI zip in the directory
        File roiFolder = new File(roisDir);
        if (roiFolder.exists()) {
            String[] zips = roiFolder.list(new FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    return name.endsWith("_rois.zip");
                }
            });
            if (zips != null && zips.length > 0) {
                return RoiIO.loadRoisFromZip(roisDir + File.separator + zips[0]);
            }
        }
        return null;
    }

    /**
     * Generates wavelet scalograms from saved scalogram TIFFs.
     */
    private void generateScalograms(String rhythmDir, String vizDir, String baseName,
                                     String[] roiNames, double[] timesH,
                                     double frameIntervalHours, SessionConfig config) {
        // Look for Scalogram_*.tif files and wavelet ridge data
        String ridgePath = rhythmDir + File.separator + "Wavelet_Ridge_" + baseName + ".csv";
        if (!new File(ridgePath).exists()) {
            IJ.log("    No wavelet data found. Skipping scalograms.");
            return;
        }

        for (int r = 0; r < roiNames.length; r++) {
            String scaloPath = rhythmDir + File.separator + "Scalogram_" + roiNames[r] + ".tif";
            if (!new File(scaloPath).exists()) continue;

            // Load the raw scalogram TIFF (32-bit power matrix)
            ImagePlus scaloImp = IJ.openImage(scaloPath);
            if (scaloImp == null) continue;

            // Reconstruct a minimal WaveletResult from the TIFF
            int nScales = scaloImp.getHeight();
            int nTime = scaloImp.getWidth();

            double[][] power = new double[nScales][nTime];
            for (int j = 0; j < nScales; j++) {
                for (int t = 0; t < nTime; t++) {
                    // Row 0 in TIFF = longest period (stored inverted)
                    power[nScales - 1 - j][t] = scaloImp.getProcessor().getf(t, j);
                }
            }

            // Approximate periods (log-spaced between min and max)
            double[] periods = new double[nScales];
            double minP = config.periodMinHours;
            double maxP = config.periodMaxHours;
            for (int j = 0; j < nScales; j++) {
                periods[j] = minP * Math.pow(maxP / minP, (double) j / (nScales - 1));
            }

            double[] scales = new double[nScales]; // placeholder
            for (int j = 0; j < nScales; j++) scales[j] = j;

            WaveletResult wr = new WaveletResult(power, scales, periods, timesH, null, null);

            ImagePlus rendered = ScalogramRenderer.render(wr, roiNames[r]);
            if (rendered != null) {
                saveImage(rendered, vizDir, "Scalogram_" + roiNames[r] + "_" + baseName + ".png");
            }

            scaloImp.close();
        }
    }

    /**
     * Loads rhythm summary results from CSV.
     */
    private List<RhythmResult> loadRhythmSummary(String rhythmDir, String baseName) {
        List<RhythmResult> results = new ArrayList<RhythmResult>();
        String path = rhythmDir + File.separator + "Rhythm_Summary_" + baseName + ".csv";
        File f = new File(path);
        if (!f.exists()) return results;

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String header = br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 10) continue;

                String roiName = parts[0].trim();
                double period = parseDouble(parts[1]);
                double phaseRad = parseDouble(parts[2]);
                double phaseH = parseDouble(parts[3]);
                double amplitude = parseDouble(parts[4]);
                double mesor = parseDouble(parts[5]);
                double dampingTau = parseDouble(parts[6]);
                double rSquared = parseDouble(parts[7]);
                double pValue = parseDouble(parts[8]);
                boolean isRhythmic = "TRUE".equalsIgnoreCase(parts[9].trim());

                results.add(new RhythmResult(roiName, period, phaseRad, phaseH,
                        amplitude, mesor, dampingTau, rSquared, pValue, isRhythmic));
            }
        } catch (IOException e) {
            IJ.log("  WARNING: Error reading rhythm summary: " + e.getMessage());
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) {}
            }
        }

        return results;
    }

    /**
     * Finds a RhythmResult by ROI name.
     */
    private RhythmResult findResult(List<RhythmResult> results, String roiName) {
        if (roiName == null) return null;
        for (RhythmResult rr : results) {
            if (roiName.equals(rr.roiName)) return rr;
        }
        return null;
    }

    /**
     * Saves an ImagePlus to a file.
     */
    private void saveImage(ImagePlus imp, String dir, String filename) {
        String path = dir + File.separator + filename;
        FileSaver saver = new FileSaver(imp);
        if (filename.endsWith(".tif") || filename.endsWith(".tiff")) {
            saver.saveAsTiff(path);
        } else {
            saver.saveAsPng(path);
        }
        IJ.log("    Saved: " + path);
    }

    /**
     * Parses a double, handling NaN gracefully.
     */
    private static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        s = s.trim();
        if ("NaN".equalsIgnoreCase(s)) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
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
        return "Visualization";
    }

    @Override
    public int getIndex() {
        return 5;
    }
}
