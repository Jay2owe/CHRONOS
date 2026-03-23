package chronos;

import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.export.ExportAnalysis;
import chronos.extraction.SignalExtractionAnalysis;
import chronos.io.IncucyteImporter;
import chronos.preprocessing.PreprocessingAnalysis;
import chronos.rhythm.RhythmAnalysis;
import chronos.roi.RoiDefinitionAnalysis;
import chronos.tracking.TrackingAnalysis;
import chronos.ui.PipelineDialog;
import chronos.visualization.VisualizationAnalysis;

import chronos.ui.ToggleSwitch;

import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main entry point for the CHRONOS circadian rhythm analysis plugin.
 * Implements {@link ij.plugin.PlugIn} and is registered via plugins.config.
 */
public class ChronosPipeline implements PlugIn {

    /** Module names in execution order. */
    private static final String[] MODULE_NAMES = {
        "Pre-processing",
        "ROI Definition",
        "Signal Extraction",
        "Rhythm Analysis",
        "Visualization",
        "Export",
        "Cell Tracking"
    };

    /** Descriptions shown under each module toggle in the pipeline dialog. */
    private static final String[] MODULE_DESCRIPTIONS = {
        "Crop, frame binning, motion correction (SIFT/cross-correlation), background subtraction, bleach/decay correction, spatial and temporal filtering. Saves corrected stacks to .circadian/corrected/.",
        "Interactively draw regions of interest on mean/max projections for each image. Supports whole SCN outline, dorsal/ventral split, grid overlay, individual cells, custom regions, and auto-boundary detection.",
        "Extracts mean intensity per ROI per frame from corrected stacks. Computes dF/F and optional Z-score traces using configurable baseline (F0) methods. Outputs CSV trace files to .circadian/traces/.",
        "Estimates circadian period via FFT, autocorrelation, Lomb-Scargle, wavelet CWT, or JTK_CYCLE. Fits cosinor model. CircaCompare compares groups. Outputs to .circadian/rhythm/.",
        "Generates time-series plots with cosinor overlays, kymographs, phase/period/amplitude spatial maps, raster plots, polar phase plots, wavelet scalograms, and a summary dashboard.",
        "Consolidates all CSVs and visualizations. Exports a formatted Excel workbook (.xlsx) with experiment parameters, traces, rhythm summary, and grouped statistics.",
        "Uses TrackMate + StarDist AI to detect and track cells (e.g. microglia) across time. Computes speed, area, displacement time-series for rhythm analysis. Requires TrackMate + StarDist update sites."
    };

    @Override
    public void run(String arg) {
        IJ.log("=============================================================");
        IJ.log("  CHRONOS — Circadian Rhythm Analyzer");
        IJ.log("=============================================================");

        // Parse command-line arguments for batch/headless mode
        // Usage: run("CHRONOS", "dir=/path/to/experiment mode=guided")
        //   or:  run("CHRONOS", "dir=/path mode=advanced modules=1,2,3,4,5")
        Map<String, String> params = parseArgs(arg);
        boolean batchMode = params.containsKey("dir");

        // 1. Choose experiment directory
        String directory;
        if (batchMode) {
            directory = params.get("dir");
            if (!directory.endsWith(File.separator)) directory += File.separator;
            if (!new File(directory).exists()) {
                IJ.error("CHRONOS", "Directory not found: " + directory);
                return;
            }
            IJ.log("Batch mode: " + directory);
        } else {
            directory = IJ.getDirectory("Select experiment folder containing TIF files");
            if (directory == null) {
                IJ.log("CHRONOS: Cancelled — no directory selected.");
                return;
            }
        }

        // Guard: if user selected a subfolder inside .circadian/, walk up to experiment root
        File dir = new File(directory);
        while (dir != null) {
            String name = dir.getName();
            if (name.equals(".circadian") || (dir.getParentFile() != null
                    && dir.getParentFile().getName().equals(".circadian"))) {
                // Go up: .circadian/assembled -> .circadian -> experiment root
                File candidate = dir;
                while (candidate != null && !candidate.getName().equals(".circadian")) {
                    candidate = candidate.getParentFile();
                }
                if (candidate != null && candidate.getParentFile() != null) {
                    dir = candidate.getParentFile();
                    directory = dir.getAbsolutePath() + File.separator;
                    IJ.log("Note: Selected folder was inside .circadian/ — using experiment root: " + directory);
                }
                break;
            }
            break;
        }

        // 1b. Mode selection: Guided vs Advanced
        String selectedMode;
        if (batchMode) {
            selectedMode = params.containsKey("mode") ? params.get("mode") : "advanced";
            if ("guided".equalsIgnoreCase(selectedMode)) {
                selectedMode = "Guided Pipeline";
            } else {
                selectedMode = "Advanced (Module-by-Module)";
            }
            IJ.log("Batch mode: " + selectedMode);
        } else {
            PipelineDialog modeDlg = new PipelineDialog("CHRONOS — Pipeline Mode");
            modeDlg.addHeader("Choose Pipeline Mode");
            modeDlg.addSpacer(4);
            String[] modes = {"Guided Pipeline", "Advanced (Module-by-Module)"};
            modeDlg.addChoice("Mode", modes, "Guided Pipeline");
            modeDlg.addSpacer(4);
            modeDlg.addHelpText("<b>Guided Pipeline</b> — walks you through the entire workflow step-by-step: image discovery, registration with interactive approval, ROI drawing, signal extraction, optional signal isolation, and cell tracking.");
            modeDlg.addSpacer(2);
            modeDlg.addHelpText("<b>Advanced</b> — select individual modules to run independently. Use this to re-run specific analysis steps without redoing the entire pipeline.");

            if (!modeDlg.showDialog()) {
                IJ.log("CHRONOS: Cancelled.");
                return;
            }

            selectedMode = modeDlg.getNextChoice();
        }

        if ("Guided Pipeline".equals(selectedMode)) {
            // Create session directories
            createSessionDirectories(directory);
            // Load config
            SessionConfig guidedConfig = SessionConfigIO.readFromDirectory(directory);
            // Auto-detect frame interval
            File intervalsFileGuided = new File(directory, ".circadian" + File.separator + "frame_intervals.txt");
            if (intervalsFileGuided.exists()) {
                Map<String, Double> savedIntervals = loadFrameIntervals(intervalsFileGuided.getAbsolutePath());
                if (!savedIntervals.isEmpty()) {
                    guidedConfig.frameIntervalMin = savedIntervals.values().iterator().next();
                }
            }
            // Run guided pipeline
            GuidedPipeline guided = new GuidedPipeline(directory, guidedConfig);
            guided.run();
            // Save config after guided run
            SessionConfigIO.writeToDirectory(directory, guidedConfig);
            return;
        }

        // --- Advanced mode continues below ---

        // 2. Scan for TIF files and detect previous session
        dir = new File(directory);
        File circadianDir = new File(directory, ".circadian");
        File assembledDir = new File(circadianDir, "assembled");
        File correctedDir = new File(circadianDir, "corrected");

        String[] tifFiles = listTifs(dir);
        String[] assembledFiles = listTifs(assembledDir);
        String[] correctedFiles = listTifs(correctedDir);

        // Detect previous session
        if (circadianDir.exists()) {
            IJ.log("Previous CHRONOS session detected in .circadian/");
            if (correctedFiles.length > 0) {
                IJ.log("  " + correctedFiles.length + " corrected stack(s) found — can skip pre-processing");
            }
            if (assembledFiles.length > 0) {
                IJ.log("  " + assembledFiles.length + " assembled stack(s) found — can resume from pre-processing");
            }
            // Check for existing traces, ROIs, etc.
            File tracesDir = new File(circadianDir, "traces");
            File roisDir = new File(circadianDir, "ROIs");
            String[] traceFiles = listTifs(tracesDir); // won't find CSVs, check separately
            String[] roiFiles = roisDir.exists() ? roisDir.list() : null;
            if (roiFiles != null && roiFiles.length > 0) {
                IJ.log("  " + roiFiles.length + " ROI file(s) found");
            }
        }

        // We need at least some images to work with
        boolean hasImages = (tifFiles.length > 0) || (assembledFiles.length > 0) || (correctedFiles.length > 0);
        if (!hasImages) {
            IJ.error("CHRONOS", "No TIF files found in:\n" + directory +
                    "\n\nAlso checked .circadian/assembled/ and .circadian/corrected/");
            return;
        }

        // Report what we found
        if (IncucyteImporter.isIncucyteDirectory(dir)) {
            Map<String, List<IncucyteImporter.IncucyteFrame>> groups =
                    IncucyteImporter.groupAndSort(dir);
            int totalFrames = 0;
            for (List<IncucyteImporter.IncucyteFrame> frames : groups.values()) {
                totalFrames += frames.size();
            }
            IJ.log("Incucyte image sequence detected in " + directory);
            IJ.log("  " + totalFrames + " individual frames across " + groups.size() + " series");
            for (Map.Entry<String, List<IncucyteImporter.IncucyteFrame>> entry : groups.entrySet()) {
                IJ.log("    " + entry.getKey() + ": " + entry.getValue().size() + " frames");
            }
            IJ.log("  Stacks will be assembled during pre-processing.");
        } else if (tifFiles.length > 0) {
            IJ.log("Found " + tifFiles.length + " TIF file(s) in " + directory);
            for (String f : tifFiles) {
                IJ.log("  - " + f);
            }
        } else {
            IJ.log("No raw TIF files in experiment folder (using previously processed data).");
        }

        // 3. Load existing config or create default
        final SessionConfig config = SessionConfigIO.readFromDirectory(directory);

        // Try to auto-detect frame interval from saved intervals
        File intervalsFile = new File(directory, ".circadian" + File.separator + "frame_intervals.txt");
        if (intervalsFile.exists()) {
            Map<String, Double> savedIntervals = loadFrameIntervals(intervalsFile.getAbsolutePath());
            if (!savedIntervals.isEmpty()) {
                config.frameIntervalMin = savedIntervals.values().iterator().next();
            }
        }

        // 4. Select modules
        boolean[] moduleEnabled = new boolean[MODULE_NAMES.length];

        if (batchMode) {
            // Parse "modules=1,2,3,4,5" or default to all
            String modulesParam = params.get("modules");
            if (modulesParam != null) {
                for (String m : modulesParam.split(",")) {
                    try {
                        int idx = Integer.parseInt(m.trim()) - 1;
                        if (idx >= 0 && idx < MODULE_NAMES.length) {
                            moduleEnabled[idx] = true;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                // Default: all modules except cell tracking
                for (int i = 0; i < MODULE_NAMES.length - 1; i++) moduleEnabled[i] = true;
            }
            StringBuilder sel = new StringBuilder("  Modules: ");
            for (int i = 0; i < MODULE_NAMES.length; i++) {
                if (moduleEnabled[i]) sel.append((i + 1) + "=" + MODULE_NAMES[i] + " ");
            }
            IJ.log(sel.toString().trim());
        } else {
            final PipelineDialog dlg = new PipelineDialog("CHRONOS — Circadian Rhythm Analyzer");

            dlg.addHeader("Pipeline Modules");
            dlg.addHelpText("Select which modules to run. Modules execute in order.");
            boolean[] defaultModuleStates = {true, true, true, true, true, true, false};
            for (int i = 0; i < MODULE_NAMES.length; i++) {
                dlg.addToggle((i + 1) + ". " + MODULE_NAMES[i], defaultModuleStates[i]);
                dlg.addHelpText(MODULE_DESCRIPTIONS[i]);
            }

            // Settings button in footer — opens the global settings dialog
            JButton settingsBtn = dlg.addFooterButton("Settings...");
            settingsBtn.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    showSettingsDialog(config);
                }
            });

            if (!dlg.showDialog()) {
                IJ.log("CHRONOS: Cancelled by user.");
                return;
            }

            for (int i = 0; i < MODULE_NAMES.length; i++) {
                moduleEnabled[i] = dlg.getNextBoolean();
            }
        }

        // 6. In batch mode, force headless
        if (batchMode) {
            config.hideImageWindows = true;
        }

        // 7. Create .circadian/ directory structure
        createSessionDirectories(directory);

        // 8. Save config
        SessionConfigIO.writeToDirectory(directory, config);

        // 8. Build analysis list
        List<Analysis> analyses = buildAnalysisList();

        // 9. Run selected modules in order
        long startTime = System.currentTimeMillis();
        int completedCount = 0;

        for (int i = 0; i < MODULE_NAMES.length; i++) {
            if (!moduleEnabled[i]) {
                IJ.log("Skipping Module " + (i + 1) + ": " + MODULE_NAMES[i]);
                continue;
            }

            Analysis analysis = analyses.get(i);
            // ROI Definition (index 1) is always interactive — never set headless
            boolean isRoiModule = (i == 1);
            analysis.setHeadless(!isRoiModule && config.hideImageWindows);
            analysis.setParallelThreads(config.parallelProcessing ? config.parallelThreads : 1);

            IJ.log("");
            IJ.log("=== CHRONOS === Module " + (i + 1) + ": " + analysis.getName() + " ===");

            try {
                boolean success = analysis.execute(directory);
                if (!success) {
                    IJ.log("Module " + (i + 1) + " (" + analysis.getName() + ") was cancelled or failed.");
                    IJ.log("CHRONOS: Continuing to next module...");
                    continue;
                }
                completedCount++;
            } catch (Exception e) {
                IJ.log("ERROR in Module " + (i + 1) + " (" + analysis.getName() + "): "
                        + e.getClass().getName() + " — " + e.getMessage());
                IJ.log("CHRONOS: Continuing to next module...");
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        IJ.log("");
        IJ.log("=============================================================");
        IJ.log("  CHRONOS complete — " + completedCount + " module(s) in " +
               formatDuration(elapsed));
        IJ.log("=============================================================");
    }

    /**
     * Creates the .circadian session directory and all subdirectories.
     */
    private void createSessionDirectories(String directory) {
        String[] subdirs = {"corrected", "projections", "ROIs", "traces", "rhythm", "visualizations", "exports", "tracking"};
        for (String sub : subdirs) {
            File d = new File(directory, ".circadian" + File.separator + sub);
            if (!d.exists()) {
                d.mkdirs();
            }
        }
    }

    /**
     * Builds the list of Analysis implementations in module order.
     */
    private List<Analysis> buildAnalysisList() {
        List<Analysis> list = new ArrayList<Analysis>();
        list.add(new PreprocessingAnalysis());      // 1
        list.add(new RoiDefinitionAnalysis());      // 2
        list.add(new SignalExtractionAnalysis());    // 3
        list.add(new RhythmAnalysis());             // 4
        list.add(new VisualizationAnalysis());      // 5
        list.add(new ExportAnalysis());             // 6
        list.add(new TrackingAnalysis());           // 7
        return list;
    }

    /**
     * Shows the global settings dialog.
     * Settings here apply across all modules.
     */
    private void showSettingsDialog(SessionConfig config) {
        PipelineDialog dlg = new PipelineDialog("CHRONOS — Settings");

        // --- Experiment Setup ---
        dlg.addHeader("Experiment Setup");
        dlg.addHelpText("Fundamental recording parameters shared across all modules.");
        String[] reporterTypes = {"Fluorescent", "Bioluminescence", "Calcium"};
        dlg.addChoice("Reporter Type", reporterTypes, config.reporterType);
        dlg.addHelpText("Determines default bleach correction method. Bioluminescence uses Sliding Percentile, Fluorescent/Calcium use Bi-exponential.");
        dlg.addNumericField("Frame Interval (minutes)", config.frameIntervalMin, 1);
        dlg.addHelpText("Time between consecutive frames. Auto-detected from Incucyte timestamps when available.");

        // --- Circadian Analysis Parameters ---
        dlg.addSpacer(8);
        dlg.addHeader("Circadian Analysis Parameters");
        dlg.addHelpText("Period search range and statistical thresholds used by rhythm analysis methods.");
        dlg.addNumericField("Min Period (hours)", config.periodMinHours, 1);
        dlg.addNumericField("Max Period (hours)", config.periodMaxHours, 1);
        dlg.addNumericField("Significance Threshold (p-value)", config.significanceThreshold, 3);

        // --- Processing ---
        dlg.addSpacer(8);
        dlg.addHeader("Processing");
        dlg.addToggle("Hide Image Windows", config.hideImageWindows);
        dlg.addHelpText("Suppress image display during automated processing steps. Interactive steps (crop, ROI drawing) always show images regardless of this setting.");
        final ToggleSwitch parallelToggle = dlg.addToggle("Parallel Processing", config.parallelProcessing);
        dlg.addHelpText("Process multiple images simultaneously. Disable if Fiji becomes unresponsive during processing.");
        int maxThreads = Runtime.getRuntime().availableProcessors();
        final JTextField threadField = dlg.addNumericField("Threads", config.parallelThreads, 0);
        dlg.addHelpText("Number of parallel worker threads (max " + maxThreads + " on this machine). Recommended: " + Math.max(1, maxThreads / 2) + ".");
        threadField.setEnabled(config.parallelProcessing);
        parallelToggle.addChangeListener(new Runnable() {
            public void run() {
                threadField.setEnabled(parallelToggle.isSelected());
            }
        });

        // --- Output ---
        dlg.addSpacer(8);
        dlg.addHeader("Output");
        String[] imageFormats = {"PNG", "TIFF"};
        dlg.addChoice("Image Format", imageFormats, config.exportImageFormat);
        dlg.addHelpText("Format for saved visualization images. PNG for smaller files, TIFF for lossless quality.");

        if (!dlg.showDialog()) {
            return; // cancelled — don't change anything
        }

        // Read values
        config.reporterType = dlg.getNextChoice();
        config.frameIntervalMin = dlg.getNextNumber();
        config.periodMinHours = dlg.getNextNumber();
        config.periodMaxHours = dlg.getNextNumber();
        config.significanceThreshold = dlg.getNextNumber();
        config.hideImageWindows = dlg.getNextBoolean();
        config.parallelProcessing = dlg.getNextBoolean();
        config.parallelThreads = Math.max(1, Math.min(maxThreads, (int) dlg.getNextNumber()));
        config.exportImageFormat = dlg.getNextChoice();

        // Apply reporter defaults
        config.applyReporterDefaults();

        IJ.log("Settings updated.");
    }

    /**
     * Loads per-file frame intervals from frame_intervals.txt.
     */
    private static Map<String, Double> loadFrameIntervals(String path) {
        Map<String, Double> intervals = new LinkedHashMap<String, Double>();
        File f = new File(path);
        if (!f.exists()) return intervals;
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
                try {
                    intervals.put(key, Double.parseDouble(val));
                } catch (NumberFormatException e) { /* skip */ }
            }
        } catch (IOException e) {
            // ignore
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
        }
        return intervals;
    }

    /**
     * Parse ImageJ-style argument string into a key-value map.
     * Supports: "dir=/path mode=guided modules=1,2,3"
     */
    private static Map<String, String> parseArgs(String arg) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        if (arg == null || arg.trim().isEmpty()) return params;
        String[] tokens = arg.split("\\s+");
        for (String token : tokens) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                params.put(token.substring(0, eq).trim().toLowerCase(),
                        token.substring(eq + 1).trim());
            }
        }
        return params;
    }

    private static String[] listTifs(File dir) {
        if (dir == null || !dir.exists()) return new String[0];
        String[] files = dir.list(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                String lower = name.toLowerCase();
                return lower.endsWith(".tif") || lower.endsWith(".tiff");
            }
        });
        return files != null ? files : new String[0];
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
