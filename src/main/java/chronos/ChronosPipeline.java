package chronos;

import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.export.ExportAnalysis;
import chronos.extraction.SignalExtractionAnalysis;
import chronos.preprocessing.PreprocessingAnalysis;
import chronos.rhythm.RhythmAnalysis;
import chronos.roi.RoiDefinitionAnalysis;
import chronos.ui.PipelineDialog;
import chronos.visualization.VisualizationAnalysis;

import ij.IJ;
import ij.plugin.PlugIn;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;

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
        "Export"
    };

    @Override
    public void run(String arg) {
        IJ.log("=============================================================");
        IJ.log("  CHRONOS — Circadian Rhythm Analyzer");
        IJ.log("=============================================================");

        // 1. Choose experiment directory
        String directory = IJ.getDirectory("Select experiment folder containing TIF files");
        if (directory == null) {
            IJ.log("CHRONOS: Cancelled — no directory selected.");
            return;
        }

        // 2. Scan for TIF files
        File dir = new File(directory);
        String[] tifFiles = dir.list(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.toLowerCase().endsWith(".tif") || name.toLowerCase().endsWith(".tiff");
            }
        });

        if (tifFiles == null || tifFiles.length == 0) {
            IJ.error("CHRONOS", "No TIF files found in:\n" + directory);
            return;
        }

        IJ.log("Found " + tifFiles.length + " TIF file(s) in " + directory);
        for (String f : tifFiles) {
            IJ.log("  - " + f);
        }

        // 3. Load existing config or create default
        SessionConfig config = SessionConfigIO.readFromDirectory(directory);

        // 4. Show main pipeline dialog
        PipelineDialog dlg = new PipelineDialog("CHRONOS — Circadian Rhythm Analyzer");

        dlg.addHeader("Pipeline Modules");
        dlg.addHelpText("Select which modules to run. Modules execute in order.");
        boolean[] defaultModuleStates = {true, true, true, true, true, true};
        for (int i = 0; i < MODULE_NAMES.length; i++) {
            dlg.addToggle((i + 1) + ". " + MODULE_NAMES[i], defaultModuleStates[i]);
        }

        dlg.addSpacer(8);
        dlg.addHeader("Pipeline Options");
        dlg.addToggle("Hide Image Windows", config.hideImageWindows);
        dlg.addToggle("Parallel Processing", config.parallelProcessing);
        dlg.addNumericField("Threads", config.parallelThreads, 0);

        if (!dlg.showDialog()) {
            IJ.log("CHRONOS: Cancelled by user.");
            return;
        }

        // 5. Read module toggles
        boolean[] moduleEnabled = new boolean[MODULE_NAMES.length];
        for (int i = 0; i < MODULE_NAMES.length; i++) {
            moduleEnabled[i] = dlg.getNextBoolean();
        }

        config.hideImageWindows = dlg.getNextBoolean();
        config.parallelProcessing = dlg.getNextBoolean();
        config.parallelThreads = Math.max(1, Math.min(
            Runtime.getRuntime().availableProcessors(),
            (int) dlg.getNextNumber()));

        // 6. Create .circadian/ directory structure
        createSessionDirectories(directory);

        // 7. Save config
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
        String[] subdirs = {"corrected", "ROIs", "traces", "rhythm", "visualizations", "exports"};
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
        return list;
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
