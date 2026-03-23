package chronos.config;

import ij.IJ;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes {@link SessionConfig} to {@code .circadian/config.txt}
 * using a simple key=value text format.
 */
public class SessionConfigIO {

    private static final String CONFIG_DIR = ".circadian";
    private static final String CONFIG_FILE = "config.txt";

    /**
     * Read a SessionConfig from the given experiment directory.
     * Returns a default config if the file does not exist.
     */
    public static SessionConfig readFromDirectory(String dir) {
        SessionConfig cfg = new SessionConfig();
        File f = new File(dir, CONFIG_DIR + File.separator + CONFIG_FILE);
        if (!f.exists()) {
            return cfg;
        }

        Map<String, String> props = new LinkedHashMap<String, String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                props.put(key, val);
            }
        } catch (IOException e) {
            IJ.log("Warning: could not read config: " + e.getMessage());
            return cfg;
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) {}
            }
        }

        // Recording Setup
        cfg.reporterType = getString(props, "reporterType", cfg.reporterType);
        cfg.frameIntervalMin = getDouble(props, "frameIntervalMin", cfg.frameIntervalMin);

        // Crop
        cfg.cropEnabled = getBool(props, "cropEnabled", cfg.cropEnabled);
        cfg.tightCropEnabled = getBool(props, "tightCropEnabled", cfg.tightCropEnabled);
        cfg.cropX = getInt(props, "cropX", cfg.cropX);
        cfg.cropY = getInt(props, "cropY", cfg.cropY);
        cfg.cropWidth = getInt(props, "cropWidth", cfg.cropWidth);
        cfg.cropHeight = getInt(props, "cropHeight", cfg.cropHeight);

        // Alignment
        cfg.alignEnabled = getBool(props, "alignEnabled", cfg.alignEnabled);

        // Frame Binning
        cfg.binningEnabled = getBool(props, "binningEnabled", cfg.binningEnabled);
        cfg.binFactor = getInt(props, "binFactor", cfg.binFactor);
        cfg.binMethod = getString(props, "binMethod", cfg.binMethod);

        // Motion Correction
        cfg.motionCorrectionEnabled = getBool(props, "motionCorrectionEnabled", cfg.motionCorrectionEnabled);
        cfg.motionCorrectionMethod = getString(props, "motionCorrectionMethod", cfg.motionCorrectionMethod);
        cfg.motionCorrectionReference = getString(props, "motionCorrectionReference", cfg.motionCorrectionReference);
        cfg.motionCorrectionCacheEnabled = getBool(props, "motionCorrectionCacheEnabled", cfg.motionCorrectionCacheEnabled);

        // Background Subtraction
        cfg.backgroundMethod = getString(props, "backgroundMethod", cfg.backgroundMethod);
        cfg.backgroundRadius = getDouble(props, "backgroundRadius", cfg.backgroundRadius);

        // Bleach / Decay Correction
        cfg.bleachMethod = getString(props, "bleachMethod", cfg.bleachMethod);
        cfg.bleachPercentileWindow = getInt(props, "bleachPercentileWindow", cfg.bleachPercentileWindow);
        cfg.bleachPercentile = getDouble(props, "bleachPercentile", cfg.bleachPercentile);

        // Spatial Filter
        cfg.spatialFilterType = getString(props, "spatialFilterType", cfg.spatialFilterType);
        cfg.spatialFilterRadius = getDouble(props, "spatialFilterRadius", cfg.spatialFilterRadius);

        // Temporal Filter
        cfg.temporalFilterType = getString(props, "temporalFilterType", cfg.temporalFilterType);
        cfg.temporalFilterWindow = getInt(props, "temporalFilterWindow", cfg.temporalFilterWindow);

        // Signal Extraction
        cfg.f0Method = getString(props, "f0Method", cfg.f0Method);
        cfg.f0WindowSize = getInt(props, "f0WindowSize", cfg.f0WindowSize);
        cfg.f0Percentile = getDouble(props, "f0Percentile", cfg.f0Percentile);
        cfg.f0NFrames = getInt(props, "f0NFrames", cfg.f0NFrames);
        cfg.cropStartFrame = getInt(props, "cropStartFrame", cfg.cropStartFrame);
        cfg.cropEndFrame = getInt(props, "cropEndFrame", cfg.cropEndFrame);
        cfg.outputDeltaFF = getBool(props, "outputDeltaFF", cfg.outputDeltaFF);
        cfg.outputZscore = getBool(props, "outputZscore", cfg.outputZscore);

        // Rhythm Analysis
        cfg.periodMinHours = getDouble(props, "periodMinHours", cfg.periodMinHours);
        cfg.periodMaxHours = getDouble(props, "periodMaxHours", cfg.periodMaxHours);
        cfg.detrendingMethod = getString(props, "detrendingMethod", cfg.detrendingMethod);
        cfg.runFFT = getBool(props, "runFFT", cfg.runFFT);
        cfg.runAutocorrelation = getBool(props, "runAutocorrelation", cfg.runAutocorrelation);
        cfg.runLombScargle = getBool(props, "runLombScargle", cfg.runLombScargle);
        cfg.runWavelet = getBool(props, "runWavelet", cfg.runWavelet);
        cfg.runJTKCycle = getBool(props, "runJTKCycle", cfg.runJTKCycle);
        cfg.runRAIN = getBool(props, "runRAIN", cfg.runRAIN);
        cfg.cosinorModel = getString(props, "cosinorModel", cfg.cosinorModel);
        cfg.significanceThreshold = getDouble(props, "significanceThreshold", cfg.significanceThreshold);
        cfg.runCircaCompare = getBool(props, "runCircaCompare", cfg.runCircaCompare);

        // Cell Tracking
        cfg.trackingEnabled = getBool(props, "trackingEnabled", cfg.trackingEnabled);
        cfg.trackMaxLinkDistance = getDouble(props, "trackMaxLinkDistance", cfg.trackMaxLinkDistance);
        cfg.trackMaxGapFrames = getInt(props, "trackMaxGapFrames", cfg.trackMaxGapFrames);
        cfg.trackGapClosingDistance = getDouble(props, "trackGapClosingDistance", cfg.trackGapClosingDistance);
        cfg.trackMinDurationFrames = getInt(props, "trackMinDurationFrames", cfg.trackMinDurationFrames);

        // Visualization
        cfg.vizTimeSeries = getBool(props, "vizTimeSeries", cfg.vizTimeSeries);
        cfg.vizKymograph = getBool(props, "vizKymograph", cfg.vizKymograph);
        cfg.vizPhaseMap = getBool(props, "vizPhaseMap", cfg.vizPhaseMap);
        cfg.vizPeriodMap = getBool(props, "vizPeriodMap", cfg.vizPeriodMap);
        cfg.vizAmplitudeMap = getBool(props, "vizAmplitudeMap", cfg.vizAmplitudeMap);
        cfg.vizRasterPlot = getBool(props, "vizRasterPlot", cfg.vizRasterPlot);
        cfg.vizPolarPlot = getBool(props, "vizPolarPlot", cfg.vizPolarPlot);
        cfg.vizScalogram = getBool(props, "vizScalogram", cfg.vizScalogram);
        cfg.vizPixelMaps = getBool(props, "vizPixelMaps", cfg.vizPixelMaps);

        // Export
        cfg.exportImageFormat = getString(props, "exportImageFormat", cfg.exportImageFormat);
        cfg.exportIncludeRawTraces = getBool(props, "exportIncludeRawTraces", cfg.exportIncludeRawTraces);

        // Pipeline
        cfg.hideImageWindows = getBool(props, "hideImageWindows", cfg.hideImageWindows);
        cfg.parallelProcessing = getBool(props, "parallelProcessing", cfg.parallelProcessing);
        cfg.parallelThreads = getInt(props, "parallelThreads", cfg.parallelThreads);

        return cfg;
    }

    /**
     * Write the given SessionConfig to the experiment directory.
     * Creates the .circadian directory if needed.
     */
    public static void writeToDirectory(String dir, SessionConfig cfg) {
        File configDir = new File(dir, CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }

        File f = new File(configDir, CONFIG_FILE);
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(f)));
            pw.println("# CHRONOS Session Configuration");
            pw.println("# Auto-generated - do not edit manually");
            pw.println();

            pw.println("# Recording Setup");
            pw.println("reporterType=" + cfg.reporterType);
            pw.println("frameIntervalMin=" + cfg.frameIntervalMin);
            pw.println();

            pw.println("# Crop");
            pw.println("cropEnabled=" + cfg.cropEnabled);
            pw.println("tightCropEnabled=" + cfg.tightCropEnabled);
            pw.println("cropX=" + cfg.cropX);
            pw.println("cropY=" + cfg.cropY);
            pw.println("cropWidth=" + cfg.cropWidth);
            pw.println("cropHeight=" + cfg.cropHeight);
            pw.println();

            pw.println("# Alignment");
            pw.println("alignEnabled=" + cfg.alignEnabled);
            pw.println();

            pw.println("# Frame Binning");
            pw.println("binningEnabled=" + cfg.binningEnabled);
            pw.println("binFactor=" + cfg.binFactor);
            pw.println("binMethod=" + cfg.binMethod);
            pw.println();

            pw.println("# Motion Correction");
            pw.println("motionCorrectionEnabled=" + cfg.motionCorrectionEnabled);
            pw.println("motionCorrectionMethod=" + cfg.motionCorrectionMethod);
            pw.println("motionCorrectionReference=" + cfg.motionCorrectionReference);
            pw.println("motionCorrectionCacheEnabled=" + cfg.motionCorrectionCacheEnabled);
            pw.println();

            pw.println("# Background Subtraction");
            pw.println("backgroundMethod=" + cfg.backgroundMethod);
            pw.println("backgroundRadius=" + cfg.backgroundRadius);
            pw.println();

            pw.println("# Bleach / Decay Correction");
            pw.println("bleachMethod=" + cfg.bleachMethod);
            pw.println("bleachPercentileWindow=" + cfg.bleachPercentileWindow);
            pw.println("bleachPercentile=" + cfg.bleachPercentile);
            pw.println();

            pw.println("# Spatial Filter");
            pw.println("spatialFilterType=" + cfg.spatialFilterType);
            pw.println("spatialFilterRadius=" + cfg.spatialFilterRadius);
            pw.println();

            pw.println("# Temporal Filter");
            pw.println("temporalFilterType=" + cfg.temporalFilterType);
            pw.println("temporalFilterWindow=" + cfg.temporalFilterWindow);
            pw.println();

            pw.println("# Signal Extraction");
            pw.println("f0Method=" + cfg.f0Method);
            pw.println("f0WindowSize=" + cfg.f0WindowSize);
            pw.println("f0Percentile=" + cfg.f0Percentile);
            pw.println("f0NFrames=" + cfg.f0NFrames);
            pw.println("cropStartFrame=" + cfg.cropStartFrame);
            pw.println("cropEndFrame=" + cfg.cropEndFrame);
            pw.println("outputDeltaFF=" + cfg.outputDeltaFF);
            pw.println("outputZscore=" + cfg.outputZscore);
            pw.println();

            pw.println("# Rhythm Analysis");
            pw.println("periodMinHours=" + cfg.periodMinHours);
            pw.println("periodMaxHours=" + cfg.periodMaxHours);
            pw.println("detrendingMethod=" + cfg.detrendingMethod);
            pw.println("runFFT=" + cfg.runFFT);
            pw.println("runAutocorrelation=" + cfg.runAutocorrelation);
            pw.println("runLombScargle=" + cfg.runLombScargle);
            pw.println("runWavelet=" + cfg.runWavelet);
            pw.println("runJTKCycle=" + cfg.runJTKCycle);
            pw.println("runRAIN=" + cfg.runRAIN);
            pw.println("cosinorModel=" + cfg.cosinorModel);
            pw.println("significanceThreshold=" + cfg.significanceThreshold);
            pw.println("runCircaCompare=" + cfg.runCircaCompare);
            pw.println();

            pw.println("# Cell Tracking");
            pw.println("trackingEnabled=" + cfg.trackingEnabled);
            pw.println("trackMaxLinkDistance=" + cfg.trackMaxLinkDistance);
            pw.println("trackMaxGapFrames=" + cfg.trackMaxGapFrames);
            pw.println("trackGapClosingDistance=" + cfg.trackGapClosingDistance);
            pw.println("trackMinDurationFrames=" + cfg.trackMinDurationFrames);
            pw.println();

            pw.println("# Visualization");
            pw.println("vizTimeSeries=" + cfg.vizTimeSeries);
            pw.println("vizKymograph=" + cfg.vizKymograph);
            pw.println("vizPhaseMap=" + cfg.vizPhaseMap);
            pw.println("vizPeriodMap=" + cfg.vizPeriodMap);
            pw.println("vizAmplitudeMap=" + cfg.vizAmplitudeMap);
            pw.println("vizRasterPlot=" + cfg.vizRasterPlot);
            pw.println("vizPolarPlot=" + cfg.vizPolarPlot);
            pw.println("vizScalogram=" + cfg.vizScalogram);
            pw.println("vizPixelMaps=" + cfg.vizPixelMaps);
            pw.println();

            pw.println("# Export");
            pw.println("exportImageFormat=" + cfg.exportImageFormat);
            pw.println("exportIncludeRawTraces=" + cfg.exportIncludeRawTraces);
            pw.println();

            pw.println("# Pipeline");
            pw.println("hideImageWindows=" + cfg.hideImageWindows);
            pw.println("parallelProcessing=" + cfg.parallelProcessing);
            pw.println("parallelThreads=" + cfg.parallelThreads);

        } catch (IOException e) {
            IJ.log("Error writing config: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    // --- Helpers ---

    private static String getString(Map<String, String> props, String key, String def) {
        String v = props.get(key);
        return v != null ? v : def;
    }

    private static int getInt(Map<String, String> props, String key, int def) {
        String v = props.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static double getDouble(Map<String, String> props, String key, double def) {
        String v = props.get(key);
        if (v == null) return def;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return def; }
    }

    private static boolean getBool(Map<String, String> props, String key, boolean def) {
        String v = props.get(key);
        if (v == null) return def;
        return Boolean.parseBoolean(v);
    }
}
