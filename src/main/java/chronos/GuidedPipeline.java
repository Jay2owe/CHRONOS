package chronos;

import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.extraction.SignalExtractionAnalysis;
import chronos.extraction.TraceExtractor;
import chronos.io.CsvWriter;
import chronos.io.IncucyteImporter;
import chronos.io.RoiIO;
import chronos.preprocessing.*;
import chronos.roi.RoiDefinitionAnalysis;
import chronos.tracking.*;
import chronos.visualization.VisualizationAnalysis;
import chronos.ui.PipelineDialog;
import chronos.ui.ToggleSwitch;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.FileSaver;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * Guided Pipeline: walks the user through the entire CHRONOS workflow
 * in a single interactive session.
 *
 * Flow: Image Discovery -> Assembly -> Registration (with approval) ->
 *       ROI Definition -> Signal Extraction -> Signal Isolation -> Cell Tracking
 */
public class GuidedPipeline {

    private final String directory;
    private final SessionConfig config;

    // Paths
    private final String circadianDir;
    private final String correctedDir;
    private final String assembledDir;
    private final String tracesDir;
    private final String trackingDir;
    private final String vizDir;

    // State
    private int stacksProcessed = 0;
    private int roisDefined = 0;
    private int tracesExtracted = 0;
    private boolean isolationApplied = false;
    private boolean trackingRan = false;
    private String registrationMethodUsed = "";

    public GuidedPipeline(String directory, SessionConfig config) {
        this.directory = directory;
        this.config = config;
        this.circadianDir = directory + ".circadian" + File.separator;
        this.correctedDir = circadianDir + "corrected" + File.separator;
        this.assembledDir = circadianDir + "assembled" + File.separator;
        this.tracesDir = circadianDir + "traces" + File.separator;
        this.trackingDir = circadianDir + "tracking" + File.separator;
        this.vizDir = circadianDir + "visualizations" + File.separator;
    }

    /**
     * Run the full guided pipeline.
     * @return true if completed (even partially), false if cancelled at start
     */
    public boolean run() {
        long totalStart = System.currentTimeMillis();

        IJ.log("");
        IJ.log("=== CHRONOS Guided Pipeline ===");

        // Stage 0: Settings
        if (!showSettingsDialog()) {
            IJ.log("Guided Pipeline: Cancelled.");
            return false;
        }
        SessionConfigIO.writeToDirectory(directory, config);

        // Stage 1: Image Discovery
        String[] correctedFiles = listTifs(new File(correctedDir));
        String[] assembledFiles = listTifs(new File(assembledDir));
        String[] rawFiles = listTifs(new File(directory));

        if (!showImageStatusDialog(correctedFiles, assembledFiles, rawFiles)) {
            return false;
        }

        // Stage 2: Assembly
        assembledFiles = doAssembly(rawFiles, assembledFiles);

        // Stage 3: Registration
        correctedFiles = listTifs(new File(correctedDir));
        if (!doRegistration(assembledFiles, correctedFiles)) {
            return false; // user cancelled
        }

        // Stage 4: ROI Definition
        IJ.log("");
        IJ.log("=== Stage 4: ROI Definition ===");
        RoiDefinitionAnalysis roiModule = new RoiDefinitionAnalysis();
        roiModule.setHeadless(false);
        roiModule.setParallelThreads(1);
        try {
            roiModule.execute(directory);
            // Count ROI files
            File roisDir = new File(circadianDir + "ROIs");
            String[] roiFiles = roisDir.exists() ? roisDir.list() : null;
            roisDefined = (roiFiles != null) ? roiFiles.length : 0;
        } catch (Exception e) {
            IJ.log("ROI Definition error: " + e.getMessage());
        }

        // Stage 5: Raw Signal Extraction
        IJ.log("");
        IJ.log("=== Stage 5: Signal Extraction ===");
        doSignalExtraction();

        // Stage 6: Signal Isolation (Optional)
        IJ.log("");
        IJ.log("=== Stage 6: Signal Isolation ===");
        doSignalIsolation();

        // Stage 7: Cell Tracking (Optional)
        IJ.log("");
        IJ.log("=== Stage 7: Cell Tracking ===");
        doCellTracking();

        // Stage 8: Completion
        long totalElapsed = System.currentTimeMillis() - totalStart;
        showCompletionDialog(totalElapsed);

        return true;
    }

    // =========================================================================
    // Stage 0: Settings
    // =========================================================================

    private boolean showSettingsDialog() {
        PipelineDialog dlg = new PipelineDialog("CHRONOS — Guided Pipeline Settings");

        dlg.addHeader("Experiment Setup");
        String[] reporterTypes = {"Fluorescent", "Bioluminescence", "Calcium"};
        dlg.addChoice("Reporter Type", reporterTypes, config.reporterType);
        dlg.addNumericField("Frame Interval (minutes)", config.frameIntervalMin, 1);

        dlg.addSpacer(4);
        dlg.addHeader("Processing");
        dlg.addToggle("Hide image windows", config.hideImageWindows);

        dlg.addSpacer(4);
        dlg.addHeader("Background Subtraction");
        boolean bgOn = !"None".equalsIgnoreCase(config.backgroundMethod);
        dlg.addToggle("Enable", bgOn);
        String[] bgMethods = {"Rolling Ball", "Minimum Projection"};
        dlg.addChoice("Method", bgMethods, bgOn ? config.backgroundMethod : "Rolling Ball");
        dlg.addNumericField("Radius (pixels)", config.backgroundRadius, 0);

        dlg.addSpacer(4);
        dlg.addHeader("Bleach / Decay Correction");
        boolean bleachOn = !"None".equalsIgnoreCase(config.bleachMethod);
        dlg.addToggle("Enable", bleachOn);
        String[] bleachMethods = {"Mono-exponential", "Bi-exponential",
                "Sliding Percentile", "Simple Ratio"};
        dlg.addChoice("Method", bleachMethods, bleachOn ? config.bleachMethod : "Bi-exponential");
        dlg.addNumericField("Percentile Window (frames)", config.bleachPercentileWindow, 0);
        dlg.addNumericField("Percentile (%)", config.bleachPercentile, 1);

        dlg.addSpacer(4);
        dlg.addHeader("Spatial Filter");
        boolean spatOn = !"None".equalsIgnoreCase(config.spatialFilterType);
        dlg.addToggle("Enable", spatOn);
        String[] spatTypes = {"Gaussian", "Median"};
        dlg.addChoice("Type", spatTypes, spatOn ? config.spatialFilterType : "Gaussian");
        dlg.addNumericField("Sigma / Radius (pixels)", config.spatialFilterRadius, 1);

        dlg.addSpacer(4);
        dlg.addHeader("Temporal Filter");
        boolean tempOn = !"None".equalsIgnoreCase(config.temporalFilterType);
        dlg.addToggle("Enable", tempOn);
        String[] tempTypes = {"Moving Average", "Savitzky-Golay"};
        dlg.addChoice("Type", tempTypes, tempOn ? config.temporalFilterType : "Moving Average");
        dlg.addNumericField("Window (frames)", config.temporalFilterWindow, 0);

        dlg.addSpacer(4);
        dlg.addHeader("Frame Binning");
        dlg.addToggle("Enable", config.binningEnabled);
        dlg.addNumericField("Bin Factor", config.binFactor, 0);
        String[] binMethods = {"Mean", "Sum"};
        dlg.addChoice("Method", binMethods, config.binMethod);

        if (!dlg.showDialog()) return false;

        // Read values
        config.reporterType = dlg.getNextChoice();
        config.frameIntervalMin = dlg.getNextNumber();
        config.hideImageWindows = dlg.getNextBoolean();

        boolean bgEnabled = dlg.getNextBoolean();
        config.backgroundMethod = bgEnabled ? dlg.getNextChoice() : "None";
        config.backgroundRadius = dlg.getNextNumber();

        boolean bleachEnabled = dlg.getNextBoolean();
        config.bleachMethod = bleachEnabled ? dlg.getNextChoice() : "None";
        config.bleachPercentileWindow = Math.max(1, (int) dlg.getNextNumber());
        config.bleachPercentile = dlg.getNextNumber();

        boolean spatialEnabled = dlg.getNextBoolean();
        config.spatialFilterType = spatialEnabled ? dlg.getNextChoice() : "None";
        config.spatialFilterRadius = dlg.getNextNumber();

        boolean temporalEnabled = dlg.getNextBoolean();
        config.temporalFilterType = temporalEnabled ? dlg.getNextChoice() : "None";
        config.temporalFilterWindow = Math.max(1, (int) dlg.getNextNumber());

        config.binningEnabled = dlg.getNextBoolean();
        config.binFactor = Math.max(1, (int) dlg.getNextNumber());
        config.binMethod = dlg.getNextChoice();

        return true;
    }

    // =========================================================================
    // Stage 1: Image Discovery
    // =========================================================================

    private boolean showImageStatusDialog(String[] correctedFiles, String[] assembledFiles,
                                          String[] rawFiles) {
        // Build lookup sets — strip both "_corrected" and "_stack" to get canonical base
        Set<String> correctedBases = new HashSet<String>();
        for (String f : correctedFiles) {
            String base = stripSuffix(stripExtension(f), "_corrected");
            base = stripSuffix(base, "_stack");
            correctedBases.add(base);
        }
        Set<String> assembledBases = new HashSet<String>();
        for (String f : assembledFiles) {
            assembledBases.add(stripSuffix(stripExtension(f), "_stack"));
        }

        PipelineDialog dlg = new PipelineDialog("CHRONOS — Image Status");
        dlg.addHeader("Image Discovery");

        int corrCount = 0, needsCount = 0, rawCount = 0;

        // Corrected images
        if (correctedFiles.length > 0) {
            dlg.addMessage("<b>Corrected images:</b>");
            for (String f : correctedFiles) {
                dlg.addMessage("  \u2713 " + f);
                corrCount++;
            }
        }

        // Assembled but not corrected
        if (assembledFiles.length > 0) {
            boolean anyMissing = false;
            for (String f : assembledFiles) {
                String base = stripSuffix(stripExtension(f), "_stack");
                if (!correctedBases.contains(base)) {
                    if (!anyMissing) {
                        dlg.addMessage("<b>Awaiting correction:</b>");
                        anyMissing = true;
                    }
                    dlg.addMessage("  \u25CB " + f);
                    needsCount++;
                }
            }
        }

        // Raw / unassembled
        boolean isIncucyte = IncucyteImporter.isIncucyteDirectory(new File(directory));
        if (isIncucyte) {
            Map<String, List<IncucyteImporter.IncucyteFrame>> groups =
                    IncucyteImporter.groupAndSort(new File(directory));
            if (!groups.isEmpty()) {
                dlg.addMessage("<b>Unassembled Incucyte frames:</b>");
                for (Map.Entry<String, List<IncucyteImporter.IncucyteFrame>> entry : groups.entrySet()) {
                    dlg.addMessage("  \u2295 " + entry.getKey() + ": " + entry.getValue().size() + " frames");
                    rawCount += entry.getValue().size();
                }
            }
        } else if (rawFiles.length > 0 && assembledFiles.length == 0) {
            dlg.addMessage("<b>Raw TIF files:</b>");
            for (String f : rawFiles) {
                dlg.addMessage("  \u2295 " + f);
                rawCount++;
            }
        }

        dlg.addSpacer(8);
        dlg.addMessage("<b>Summary:</b> " + corrCount + " corrected, "
                + needsCount + " awaiting correction"
                + (rawCount > 0 ? ", " + rawCount + " to assemble" : ""));

        return dlg.showDialog();
    }

    // =========================================================================
    // Stage 2: Assembly
    // =========================================================================

    private String[] doAssembly(String[] rawFiles, String[] assembledFiles) {
        File dir = new File(directory);
        boolean isIncucyte = IncucyteImporter.isIncucyteDirectory(dir);

        // Check for new frames to append to existing stacks
        if (isIncucyte && assembledFiles.length > 0) {
            Map<String, List<IncucyteImporter.IncucyteFrame>> newGroups =
                    IncucyteImporter.groupAndSort(dir);
            int newFrameCount = 0;
            for (List<IncucyteImporter.IncucyteFrame> frames : newGroups.values()) {
                newFrameCount += frames.size();
            }
            if (newFrameCount > 0) {
                IJ.log("Appending " + newFrameCount + " new Incucyte frame(s) to existing stacks...");
                IncucyteImporter.updateStacks(dir, assembledDir, config.frameIntervalMin);
            }
        }

        // Assemble if no assembled stacks and Incucyte frames exist
        if (isIncucyte && assembledFiles.length == 0) {
            IJ.log("Assembling Incucyte frames into stacks...");
            new File(assembledDir).mkdirs();
            List<String> assembled = IncucyteImporter.assembleStacks(
                    dir, assembledDir, config.frameIntervalMin);
            IJ.log("Assembly complete: " + assembled.size() + " stack(s) created.");
        }

        return listTifs(new File(assembledDir));
    }

    // =========================================================================
    // Stage 3: Registration
    // =========================================================================

    private boolean doRegistration(String[] assembledFiles, String[] correctedFiles) {
        // Determine what needs processing
        // Build a set of canonical base names from corrected files by stripping
        // both "_corrected" and "_stack" suffixes (handles naming from both
        // Guided and Advanced pipelines)
        Set<String> correctedBases = new HashSet<String>();
        for (String f : correctedFiles) {
            String base = stripSuffix(stripExtension(f), "_corrected");
            base = stripSuffix(base, "_stack");
            correctedBases.add(base);
        }

        // If no assembled files, use raw TIFs
        String processDir;
        String[] processFiles;
        if (assembledFiles.length > 0) {
            processDir = assembledDir;
            processFiles = assembledFiles;
        } else {
            processDir = directory;
            processFiles = listTifs(new File(directory));
        }

        if (processFiles.length == 0) {
            IJ.log("No images to process.");
            return true;
        }

        // Check which need correction
        List<String> needsCorrection = new ArrayList<String>();
        for (String f : processFiles) {
            String base = stripSuffix(stripExtension(f), "_stack");
            if (!correctedBases.contains(base)) {
                needsCorrection.add(f);
            }
        }

        // If corrected images exist, ask what to do
        String correctionMode = "missing"; // "all", "missing", "skip"
        if (correctedFiles.length > 0) {
            PipelineDialog dlg = new PipelineDialog("CHRONOS — Registration");
            dlg.addHeader("Corrected Images Found");
            dlg.addMessage(correctedFiles.length + " corrected image(s) already exist.");
            if (!needsCorrection.isEmpty()) {
                dlg.addMessage(needsCorrection.size() + " image(s) still need correction.");
            }
            dlg.addSpacer(4);
            String[] options = {"Use existing corrections", "Re-correct all", "Only correct missing"};
            dlg.addChoice("Action", options, needsCorrection.isEmpty()
                    ? "Use existing corrections" : "Only correct missing");

            if (!dlg.showDialog()) return false;
            String choice = dlg.getNextChoice();

            if ("Use existing corrections".equals(choice)) {
                correctionMode = "skip";
            } else if ("Re-correct all".equals(choice)) {
                correctionMode = "all";
            } else {
                correctionMode = "missing";
            }
        }

        if ("skip".equals(correctionMode)) {
            IJ.log("Using existing corrected images.");
            return true;
        }

        // Build list of files to process
        List<String> filesToProcess;
        if ("all".equals(correctionMode)) {
            filesToProcess = new ArrayList<String>(Arrays.asList(processFiles));
        } else {
            filesToProcess = needsCorrection;
        }

        if (filesToProcess.isEmpty()) {
            IJ.log("All images already corrected.");
            return true;
        }

        // Stage 3a: Quick drift scan on first stack
        IJ.log("Running drift analysis on first stack...");
        ImagePlus firstImp = IJ.openImage(processDir + filesToProcess.get(0));
        DriftAnalysisResult driftResult = null;
        if (firstImp != null) {
            driftResult = DriftAnalyzer.analyze(firstImp, config.frameIntervalMin);
            firstImp.close();
        }

        // Stage 3b: Method selection dialog
        String selectedMethod;
        boolean broadCropEnabled;
        boolean tightCropEnabled;
        boolean alignEnabled;

        while (true) { // Loop for "restart all with new method"
            PipelineDialog mcDlg = new PipelineDialog("CHRONOS — Motion Correction");
            mcDlg.addHeader("Drift Analysis Results");
            if (driftResult != null) {
                mcDlg.addMessage("Pattern: <b>" + driftResult.driftPattern + "</b>");
                double maxDrift = 0;
                for (int i = 0; i < driftResult.dx.length; i++) {
                    double m = Math.sqrt(driftResult.dx[i] * driftResult.dx[i]
                            + driftResult.dy[i] * driftResult.dy[i]);
                    if (m > maxDrift) maxDrift = m;
                }
                mcDlg.addMessage("Max drift: <b>" + IJ.d2s(maxDrift, 1) + " px</b>");
                mcDlg.addMessage("Transitions: <b>" + driftResult.nTransitions + "</b>");
                mcDlg.addMessage("Rotation likely: <b>" + driftResult.rotationLikely + "</b>");
                mcDlg.addSpacer(4);
                mcDlg.addMessage("Recommended: <b>" + driftResult.recommendedMethod + "</b>");
                mcDlg.addHelpText(driftResult.recommendationReason);
            }

            mcDlg.addSpacer(4);
            mcDlg.addHeader("Method Selection");
            String[] mcMethods = {"Automatic", "Phase Correlation",
                    "Phase Correlation + Epoch Detection", "Anchor-Patch Tracking",
                    "Cross-Correlation", "SIFT", "Descriptor-Based",
                    "Correct 3D Drift", "Correct 3D Drift (Manual Landmarks)"};
            String defaultMethod = (driftResult != null) ? driftResult.recommendedMethod : "Automatic";
            mcDlg.addChoice("Method", mcMethods, defaultMethod);
            String[] refMethods = {"Mean Projection", "Median Projection", "First Frame"};
            mcDlg.addChoice("Reference", refMethods, "Mean Projection");

            mcDlg.addSpacer(4);
            mcDlg.addHeader("Crop & Alignment");
            mcDlg.addToggle("Broad crop before registration", config.cropEnabled);
            mcDlg.addToggle("Tight crop after registration", config.tightCropEnabled);
            mcDlg.addToggle("Alignment rotation", config.alignEnabled);

            if (!mcDlg.showDialog()) return false;

            selectedMethod = mcDlg.getNextChoice();
            String refDisplay = mcDlg.getNextChoice();
            config.motionCorrectionReference = displayToRef(refDisplay);
            broadCropEnabled = mcDlg.getNextBoolean();
            tightCropEnabled = mcDlg.getNextBoolean();
            alignEnabled = mcDlg.getNextBoolean();
            config.cropEnabled = broadCropEnabled;
            config.tightCropEnabled = tightCropEnabled;
            config.alignEnabled = alignEnabled;

            if ("Automatic".equalsIgnoreCase(selectedMethod) && driftResult != null) {
                selectedMethod = driftResult.recommendedMethod;
            }
            config.motionCorrectionMethod = selectedMethod;
            registrationMethodUsed = selectedMethod;

            // Stage 3c: Interactive pre-registration setup (alignment + broad crop)
            Map<String, Double> perFileAngles = new LinkedHashMap<String, Double>();
            Map<String, Rectangle> perFileBroadCrops = new LinkedHashMap<String, Rectangle>();
            Map<String, Rectangle> perFileTightCrops = new LinkedHashMap<String, Rectangle>();

            // Load saved values
            String anglesPath = circadianDir + "alignment_angles.txt";
            String broadCropPath = circadianDir + "crop_regions.txt";
            String tightCropPath = circadianDir + "tight_crop_regions.txt";

            if (alignEnabled) {
                perFileAngles = loadKeyValues(anglesPath);
                if (!perFileAngles.isEmpty()) {
                    if (!askReuse("Alignment Angles", perFileAngles.size() + " saved angle(s)")) {
                        perFileAngles.clear();
                    }
                }
            }
            if (broadCropEnabled) {
                perFileBroadCrops = loadCropRegions(broadCropPath);
                if (!perFileBroadCrops.isEmpty()) {
                    if (!askReuse("Broad Crop Regions", perFileBroadCrops.size() + " saved region(s)")) {
                        perFileBroadCrops.clear();
                    }
                }
            }
            if (tightCropEnabled) {
                perFileTightCrops = loadCropRegions(tightCropPath);
                if (!perFileTightCrops.isEmpty()) {
                    if (!askReuse("Tight Crop Regions", perFileTightCrops.size() + " saved region(s)")) {
                        perFileTightCrops.clear();
                    }
                }
            }

            // Interactive alignment + broad crop (on mean projections before processing)
            if (alignEnabled || broadCropEnabled) {
                for (int i = 0; i < filesToProcess.size(); i++) {
                    String fileName = filesToProcess.get(i);
                    String baseName = stripSuffix(stripExtension(fileName), "_stack");

                    boolean needAlign = alignEnabled && !perFileAngles.containsKey(baseName);
                    boolean needBroadCrop = broadCropEnabled && !perFileBroadCrops.containsKey(baseName);
                    if (!needAlign && !needBroadCrop) continue;

                    ImagePlus imp = IJ.openImage(processDir + fileName);
                    if (imp == null) continue;

                    ImagePlus proj = computeMeanProjection(imp);
                    IJ.run(proj, "Enhance Contrast", "saturated=0.35");
                    imp.close();

                    if (needAlign) {
                        proj.setTitle("ALIGN [" + (i + 1) + "/" + filesToProcess.size() + "] — " + baseName);
                        proj.show();
                        WaitForUserDialog aw = new WaitForUserDialog(
                                "Align [" + (i + 1) + "/" + filesToProcess.size() + "]",
                                "Draw a LINE through the midline,\nthen press OK.\nESC to skip.");
                        aw.show();
                        if (!aw.escPressed()) {
                            Roi lineRoi = proj.getRoi();
                            if (lineRoi != null && lineRoi.isLine()) {
                                java.awt.Polygon poly = lineRoi.getPolygon();
                                if (poly != null && poly.npoints >= 2) {
                                    double dx = poly.xpoints[1] - poly.xpoints[0];
                                    double dy = poly.ypoints[1] - poly.ypoints[0];
                                    double angle = Math.toDegrees(Math.atan2(dx, dy));
                                    if (angle > 90) angle -= 180;
                                    if (angle < -90) angle += 180;
                                    perFileAngles.put(baseName, angle);

                                    // Rotate projection for crop drawing
                                    if (needBroadCrop && Math.abs(angle) > 0.1) {
                                        proj.deleteRoi();
                                        rotateProcessor(proj, angle);
                                    }
                                }
                            }
                        }
                        proj.deleteRoi();
                    }

                    if (needBroadCrop) {
                        proj.setTitle("BROAD CROP [" + (i + 1) + "/" + filesToProcess.size() + "] — " + baseName);
                        proj.show();
                        WaitForUserDialog cw = new WaitForUserDialog(
                                "Broad Crop [" + (i + 1) + "/" + filesToProcess.size() + "]",
                                "Draw a LOOSE rectangle around the sample.\n"
                                + "A precise crop will be offered after registration.\nESC to skip.");
                        cw.show();
                        if (!cw.escPressed()) {
                            Roi cropRoi = proj.getRoi();
                            if (cropRoi != null && cropRoi.getType() == Roi.RECTANGLE) {
                                perFileBroadCrops.put(baseName, cropRoi.getBounds());
                            }
                        }
                        proj.deleteRoi();
                    }

                    proj.close();
                }

                if (!perFileAngles.isEmpty()) saveKeyValues(anglesPath, perFileAngles, "alignment angles");
                if (!perFileBroadCrops.isEmpty()) saveCropRegions(broadCropPath, perFileBroadCrops);
            }

            // Stage 3d: Process each stack
            boolean restartRequested = false;
            RegistrationResult applyAllResult = null; // for "same transforms"
            String applyAllMethod = null; // for "same method"

            new File(correctedDir).mkdirs();

            for (int fi = 0; fi < filesToProcess.size(); fi++) {
                String fileName = filesToProcess.get(fi);
                String baseName = stripSuffix(stripExtension(fileName), "_stack");

                IJ.log("");
                IJ.log("[" + (fi + 1) + "/" + filesToProcess.size() + "] " + fileName);

                ImagePlus imp = IJ.openImage(processDir + fileName);
                if (imp == null) {
                    IJ.log("  ERROR: Could not open " + fileName);
                    continue;
                }

                // Align
                Double angle = perFileAngles.get(baseName);
                if (alignEnabled && angle != null && Math.abs(angle) > 0.1) {
                    imp = rotateStack(imp, angle);
                }

                // Broad crop
                Rectangle broadCrop = perFileBroadCrops.get(baseName);
                if (broadCropEnabled && broadCrop != null) {
                    imp = cropStack(imp, broadCrop);
                }

                // Frame binning
                if (config.binningEnabled && config.binFactor > 1) {
                    boolean useMean = "Mean".equalsIgnoreCase(config.binMethod);
                    ImagePlus binned = FrameBinner.bin(imp, config.binFactor, useMean);
                    if (binned != imp) { imp.close(); imp = binned; }
                }

                // Register
                String methodToUse = (applyAllMethod != null) ? applyAllMethod : selectedMethod;
                RegistrationResult regResult;

                if (applyAllResult != null) {
                    // Apply same transforms
                    IJ.log("  Applying saved transforms...");
                    regResult = applyAllResult;
                } else {
                    regResult = computeRegistration(imp, methodToUse, driftResult);
                }

                // Apply registration
                ImagePlus registered;
                if (regResult != null && !"SIFT".equalsIgnoreCase(methodToUse)
                        && !"Cross-Correlation".equalsIgnoreCase(methodToUse)
                        && !"Descriptor-Based".equalsIgnoreCase(methodToUse)) {
                    registered = MotionCorrector.applyRegistration(imp, regResult);
                    if (registered != imp) { imp.close(); imp = registered; }
                } else if (regResult != null) {
                    // Plugin-based methods already applied the correction to imp
                    registered = imp;
                } else {
                    registered = imp;
                }

                // Show for approval
                boolean accepted = false;
                while (!accepted) {
                    registered.show();
                    PipelineDialog approvalDlg = new PipelineDialog("CHRONOS — Registration Result", false);
                    approvalDlg.addHeader("Registration Result — " + baseName);
                    approvalDlg.addMessage("Method: <b>" + methodToUse + "</b>");
                    if (regResult != null) {
                        approvalDlg.addMessage("Max shift: <b>" + IJ.d2s(regResult.maxShift, 2) + " px</b>");
                    }
                    approvalDlg.addMessage("Scroll through the stack to verify registration quality.");
                    approvalDlg.addSpacer(4);

                    String[] actions = {"Accept", "Try different method", "Restart all with new method"};
                    approvalDlg.addChoice("Action", actions, "Accept");

                    int remaining = filesToProcess.size() - fi - 1;
                    if (remaining > 0) {
                        approvalDlg.addSpacer(4);
                        approvalDlg.addToggle("Apply to all " + remaining + " remaining", false);
                        String[] applyModes = {"Same transforms", "Same method"};
                        approvalDlg.addChoice("Apply mode", applyModes, "Same method");
                    }

                    approvalDlg.showDialog();
                    registered.hide();

                    String action = approvalDlg.getNextChoice();

                    boolean applyAll = false;
                    String applyMode = "Same method";
                    if (remaining > 0) {
                        applyAll = approvalDlg.getNextBoolean();
                        applyMode = approvalDlg.getNextChoice();
                    }

                    if ("Accept".equals(action)) {
                        accepted = true;
                        if (applyAll && regResult != null) {
                            if ("Same transforms".equals(applyMode)) {
                                applyAllResult = regResult;
                                applyAllMethod = null;
                            } else {
                                applyAllMethod = methodToUse;
                                applyAllResult = null;
                            }
                        }
                    } else if ("Try different method".equals(action)) {
                        // Show method picker, re-register this stack
                        PipelineDialog retryDlg = new PipelineDialog("CHRONOS — Choose Method");
                        retryDlg.addHeader("Select Registration Method");
                        String[] mcMethods2 = {"Phase Correlation",
                                "Phase Correlation + Epoch Detection", "Anchor-Patch Tracking",
                                "Cross-Correlation", "SIFT", "Descriptor-Based"};
                        retryDlg.addChoice("Method", mcMethods2, methodToUse);
                        if (!retryDlg.showDialog()) continue;
                        methodToUse = retryDlg.getNextChoice();

                        // Re-open original and re-process
                        registered.close();
                        imp = IJ.openImage(processDir + fileName);
                        if (imp == null) break;
                        if (alignEnabled && angle != null && Math.abs(angle) > 0.1) {
                            imp = rotateStack(imp, angle);
                        }
                        if (broadCropEnabled && broadCrop != null) {
                            imp = cropStack(imp, broadCrop);
                        }
                        if (config.binningEnabled && config.binFactor > 1) {
                            boolean useMean = "Mean".equalsIgnoreCase(config.binMethod);
                            ImagePlus binned = FrameBinner.bin(imp, config.binFactor, useMean);
                            if (binned != imp) { imp.close(); imp = binned; }
                        }

                        regResult = computeRegistration(imp, methodToUse, driftResult);
                        if (regResult != null && !"SIFT".equalsIgnoreCase(methodToUse)
                                && !"Cross-Correlation".equalsIgnoreCase(methodToUse)
                                && !"Descriptor-Based".equalsIgnoreCase(methodToUse)) {
                            registered = MotionCorrector.applyRegistration(imp, regResult);
                            if (registered != imp) { imp.close(); imp = registered; }
                        } else {
                            registered = imp;
                        }
                    } else {
                        // Restart all with new method
                        registered.close();
                        restartRequested = true;
                        accepted = true; // break inner loop
                    }
                }

                if (restartRequested) break;

                imp = registered;

                // Tight crop
                if (tightCropEnabled) {
                    Rectangle tightCrop = perFileTightCrops.get(baseName);
                    if (tightCrop == null) {
                        // Interactive tight crop on registered mean projection
                        ImagePlus tightProj = computeMeanProjection(imp);
                        IJ.run(tightProj, "Enhance Contrast", "saturated=0.35");
                        tightProj.setTitle("TIGHT CROP (registered) — " + baseName);
                        tightProj.show();
                        WaitForUserDialog tw = new WaitForUserDialog(
                                "Tight Crop — " + baseName,
                                "Draw a TIGHT rectangle around the sample.\nESC to skip.");
                        tw.show();
                        if (!tw.escPressed()) {
                            Roi tightRoi = tightProj.getRoi();
                            if (tightRoi != null && tightRoi.getType() == Roi.RECTANGLE) {
                                tightCrop = tightRoi.getBounds();
                                perFileTightCrops.put(baseName, tightCrop);
                            }
                        }
                        tightProj.close();
                    }
                    if (tightCrop != null) {
                        imp = cropStack(imp, tightCrop);
                    }
                }

                // Background subtraction
                if (!"None".equalsIgnoreCase(config.backgroundMethod)) {
                    if ("Rolling Ball".equalsIgnoreCase(config.backgroundMethod)) {
                        ImagePlus bg = BackgroundCorrector.rollingBall(imp, config.backgroundRadius);
                        if (bg != imp) { imp.close(); imp = bg; }
                    } else if ("Minimum Projection".equalsIgnoreCase(config.backgroundMethod)) {
                        ImagePlus bg = BackgroundCorrector.minimumProjection(imp);
                        if (bg != imp) { imp.close(); imp = bg; }
                    }
                }

                // Bleach correction
                if (!"None".equalsIgnoreCase(config.bleachMethod)) {
                    double[] meanTrace = BleachCorrector.extractMeanTrace(imp);
                    double[] factors = null;
                    if ("Mono-exponential".equalsIgnoreCase(config.bleachMethod)) {
                        factors = BleachCorrector.monoExponentialFit(meanTrace);
                    } else if ("Bi-exponential".equalsIgnoreCase(config.bleachMethod)) {
                        factors = BleachCorrector.biExponentialFit(meanTrace);
                    } else if ("Sliding Percentile".equalsIgnoreCase(config.bleachMethod)) {
                        double[] baseline = BleachCorrector.slidingPercentile(
                                meanTrace, config.bleachPercentileWindow, config.bleachPercentile);
                        factors = new double[baseline.length];
                        double b0 = baseline[0] > 0 ? baseline[0] : 1.0;
                        for (int k = 0; k < baseline.length; k++) factors[k] = baseline[k] / b0;
                    } else if ("Simple Ratio".equalsIgnoreCase(config.bleachMethod)) {
                        factors = BleachCorrector.simpleRatio(meanTrace);
                    }
                    if (factors != null) {
                        ImagePlus bc = BleachCorrector.correctStack(imp, factors);
                        if (bc != imp) { imp.close(); imp = bc; }
                    }
                }

                // Spatial filter
                if (!"None".equalsIgnoreCase(config.spatialFilterType)) {
                    if ("Gaussian".equalsIgnoreCase(config.spatialFilterType)) {
                        ImagePlus sf = SpatialFilter.gaussian(imp, config.spatialFilterRadius);
                        if (sf != imp) { imp.close(); imp = sf; }
                    } else if ("Median".equalsIgnoreCase(config.spatialFilterType)) {
                        ImagePlus sf = SpatialFilter.median(imp, config.spatialFilterRadius);
                        if (sf != imp) { imp.close(); imp = sf; }
                    }
                }

                // Temporal filter
                if (!"None".equalsIgnoreCase(config.temporalFilterType)) {
                    ImagePlus tf = TemporalFilter.movingAverage(imp, config.temporalFilterWindow);
                    if (tf != imp) { imp.close(); imp = tf; }
                }

                // Save corrected stack
                String outputPath = correctedDir + baseName + "_corrected.tif";
                FileSaver saver = new FileSaver(imp);
                if (imp.getStackSize() > 1) {
                    saver.saveAsTiffStack(outputPath);
                } else {
                    saver.saveAsTiff(outputPath);
                }
                imp.close();

                // Save registration transforms
                if (regResult != null) {
                    String txPath = correctedDir + "registration_transforms_" + baseName + ".csv";
                    saveRegistrationTransforms(txPath, regResult);
                }

                stacksProcessed++;
                IJ.log("  Saved: " + baseName + "_corrected.tif");
            }

            // Save tight crops
            if (!perFileTightCrops.isEmpty()) {
                saveCropRegions(tightCropPath, perFileTightCrops);
            }

            if (restartRequested) {
                IJ.log("Restarting registration with new method...");
                continue; // go back to method selection dialog
            }

            break; // done with registration
        }

        return true;
    }

    /**
     * Compute registration using the specified method.
     */
    private RegistrationResult computeRegistration(ImagePlus imp, String method,
                                                    DriftAnalysisResult driftResult) {
        if ("SIFT".equalsIgnoreCase(method)) {
            IJ.log("  Registering with SIFT...");
            ImagePlus result = MotionCorrector.correctWithSIFT(imp);
            // SIFT modifies in-place essentially (returns new or same)
            if (result != imp) {
                // Copy result stack back to imp
                imp.setStack(result.getStack());
                result.close();
            }
            double[] zeros = new double[imp.getStackSize()];
            double[] ones = new double[imp.getStackSize()];
            Arrays.fill(ones, 1.0);
            return new RegistrationResult(zeros, zeros, ones, "SIFT", "plugin");
        } else if ("Cross-Correlation".equalsIgnoreCase(method)) {
            IJ.log("  Registering with Cross-Correlation...");
            ImagePlus result = MotionCorrector.correct(imp, config.motionCorrectionReference);
            if (result != imp) {
                imp.setStack(result.getStack());
                result.close();
            }
            double[] zeros = new double[imp.getStackSize()];
            double[] ones = new double[imp.getStackSize()];
            Arrays.fill(ones, 1.0);
            return new RegistrationResult(zeros, zeros, ones, "Cross-Correlation",
                    config.motionCorrectionReference);
        } else if ("Descriptor-Based".equalsIgnoreCase(method)) {
            IJ.log("  Registering with Descriptor-Based...");
            return MotionCorrector.computeDescriptorBased(imp);
        } else if ("Phase Correlation".equalsIgnoreCase(method)) {
            IJ.log("  Registering with Phase Correlation...");
            return MotionCorrector.computePhaseCorrelation(imp, config.motionCorrectionReference);
        } else if ("Phase Correlation + Epoch Detection".equalsIgnoreCase(method)) {
            IJ.log("  Registering with Phase Correlation + Epoch Detection...");
            int[] transitions = (driftResult != null) ? driftResult.transitionFrames : new int[0];
            return MotionCorrector.computeEpochRegistration(imp,
                    config.motionCorrectionReference, transitions);
        } else if ("Anchor-Patch Tracking".equalsIgnoreCase(method)) {
            IJ.log("  Registering with Anchor-Patch Tracking...");
            return AnchorPatchTracker.track(imp, config.motionCorrectionReference);
        } else if ("Correct 3D Drift".equalsIgnoreCase(method)) {
            IJ.log("  Registering with Correct 3D Drift...");
            RegistrationResult result = MotionCorrector.correctWith3DDrift(imp);
            if (result == null) {
                IJ.log("  Correct 3D Drift failed, falling back to Phase Correlation...");
                return MotionCorrector.computePhaseCorrelation(imp, config.motionCorrectionReference);
            }
            return result;
        } else if ("Correct 3D Drift (Manual Landmarks)".equalsIgnoreCase(method)) {
            IJ.log("  Registering with Correct 3D Drift (Manual Landmarks)...");
            IJ.log("  Draw a rectangle around stable landmarks, then press OK.");
            imp.show();
            ij.gui.WaitForUserDialog wait = new ij.gui.WaitForUserDialog(
                    "CHRONOS — Manual Landmarks",
                    "Draw a rectangle around stable landmarks\n(scratch marks, tissue edges), then click OK.");
            wait.show();
            java.awt.Rectangle roi = (imp.getRoi() != null) ? imp.getRoi().getBounds() : null;
            imp.hide();
            if (roi == null || roi.width < 10 || roi.height < 10) {
                IJ.log("  No valid ROI drawn, falling back to automatic Correct 3D Drift...");
                RegistrationResult result = MotionCorrector.correctWith3DDrift(imp);
                if (result == null) {
                    return MotionCorrector.computePhaseCorrelation(imp, config.motionCorrectionReference);
                }
                return result;
            }
            RegistrationResult result = MotionCorrector.correctWith3DDriftManual(imp, roi);
            if (result == null) {
                IJ.log("  Correct 3D Drift (Manual) failed, falling back to Phase Correlation...");
                return MotionCorrector.computePhaseCorrelation(imp, config.motionCorrectionReference);
            }
            return result;
        } else {
            IJ.log("  Registering with Phase Correlation (fallback)...");
            return MotionCorrector.computePhaseCorrelation(imp, config.motionCorrectionReference);
        }
    }

    // =========================================================================
    // Stage 5: Signal Extraction
    // =========================================================================

    private void doSignalExtraction() {
        // Run the existing module
        SignalExtractionAnalysis extModule = new SignalExtractionAnalysis();
        extModule.setHeadless(config.hideImageWindows);
        extModule.setParallelThreads(1);
        try {
            extModule.execute(directory);
        } catch (Exception e) {
            IJ.log("Signal extraction error: " + e.getMessage());
        }

        // Also offer whole-image extraction
        PipelineDialog wholeImgDlg = new PipelineDialog("CHRONOS — Whole Image Extraction");
        wholeImgDlg.addHeader("Full-Image Trace");
        wholeImgDlg.addMessage("Extract a single mean-intensity trace from the entire image?");
        wholeImgDlg.addHelpText("Useful if you skipped ROI drawing or want an overview trace.");
        wholeImgDlg.addToggle("Extract whole-image trace", false);
        if (wholeImgDlg.showDialog() && wholeImgDlg.getNextBoolean()) {
            extractWholeImageTraces();
        }

        // Count traces
        File tracesFile = new File(tracesDir);
        String[] traceFiles = tracesFile.exists() ? tracesFile.list() : null;
        tracesExtracted = (traceFiles != null) ? traceFiles.length : 0;
    }

    private void extractWholeImageTraces() {
        new File(tracesDir).mkdirs();
        String[] corrected = listTifs(new File(correctedDir));
        for (String f : corrected) {
            String baseName = stripSuffix(stripExtension(f), "_corrected");
            ImagePlus imp = IJ.openImage(correctedDir + f);
            if (imp == null) continue;

            int nFrames = imp.getStackSize();
            double[] trace = new double[nFrames];
            ImageStack stack = imp.getStack();
            for (int i = 1; i <= nFrames; i++) {
                double sum = 0;
                float[] pixels = (float[]) stack.getProcessor(i).convertToFloatProcessor().getPixels();
                for (float p : pixels) sum += p;
                trace[i - 1] = sum / pixels.length;
            }
            imp.close();

            // Save as CSV
            String path = tracesDir + baseName + "_whole_image_trace.csv";
            PrintWriter pw = null;
            try {
                pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
                pw.println("Frame,Time_hours,MeanIntensity");
                for (int i = 0; i < nFrames; i++) {
                    double timeH = i * config.frameIntervalMin / 60.0;
                    pw.println(i + "," + IJ.d2s(timeH, 4) + "," + IJ.d2s(trace[i], 6));
                }
            } catch (IOException e) {
                IJ.log("Error saving whole-image trace: " + e.getMessage());
            } finally {
                if (pw != null) pw.close();
            }
            IJ.log("  Saved whole-image trace: " + baseName);
        }
    }

    // =========================================================================
    // Stage 6: Signal Isolation
    // =========================================================================

    private void doSignalIsolation() {
        PipelineDialog dlg = new PipelineDialog("CHRONOS — Signal Isolation");
        dlg.addHeader("Signal Isolation (Optional)");
        dlg.addMessage("Apply an ImageJ macro to isolate specific signal?");
        dlg.addHelpText("Select a preset or choose Custom to enter your own macro.");
        dlg.addToggle("Enable signal isolation", false);
        dlg.addSpacer(4);
        dlg.addChoice("Filter preset", NamedFilterLoader.FILTER_NAMES,
                NamedFilterLoader.FILTER_NAMES[0]);
        dlg.addHelpText("Commands run on each corrected stack. The active image after execution is used for extraction.");
        dlg.addSpacer(4);
        dlg.addMessage("Custom macro (only used when preset is 'Custom'):");
        dlg.addStringField("Macro", "", 40);

        dlg.addSpacer(8);
        dlg.addHeader("Video Export");
        dlg.addToggle("Save as AVI video", false);
        String[] lutOptions = {"None", "Green", "Fire", "Cyan Hot", "Grays", "Magenta", "Red", "Blue"};
        dlg.addChoice("LUT", lutOptions, "None");
        dlg.addNumericField("Frames per second", 7, 0);

        if (!dlg.showDialog()) return;

        boolean enabled = dlg.getNextBoolean();
        String selectedPreset = dlg.getNextChoice();
        String customMacro = dlg.getNextString();
        boolean saveAvi = dlg.getNextBoolean();
        String aviLut = dlg.getNextChoice();
        int aviFps = Math.max(1, (int) dlg.getNextNumber());

        if (!enabled) {
            IJ.log("  Signal isolation: skipped");
            return;
        }

        String macroCommands;
        if ("Custom".equals(selectedPreset)) {
            macroCommands = customMacro;
        } else {
            macroCommands = NamedFilterLoader.loadFilterContent(selectedPreset);
            if (macroCommands == null) {
                IJ.log("  WARNING: Could not load preset '" + selectedPreset + "'. Skipping.");
                return;
            }
            IJ.log("  Using preset: " + selectedPreset);
        }

        if (macroCommands.trim().isEmpty()) {
            IJ.log("  Signal isolation: skipped (empty macro)");
            return;
        }

        isolationApplied = true;
        IJ.log("  Running signal isolation macro on corrected stacks...");

        // Create output directories
        String filteredDir = circadianDir + "filtered" + File.separator;
        new File(filteredDir).mkdirs();
        String videosDir = circadianDir + "videos" + File.separator;
        if (saveAvi) new File(videosDir).mkdirs();

        // Load ROIs if available
        String roisDir = circadianDir + "ROIs" + File.separator;
        String[] corrected = listTifs(new File(correctedDir));

        boolean previewShown = false;

        for (int fi = 0; fi < corrected.length; fi++) {
            String f = corrected[fi];
            String baseName = stripSuffix(stripExtension(f), "_corrected");
            baseName = stripSuffix(baseName, "_stack");
            IJ.log("  Isolating: " + baseName);

            ImagePlus imp = IJ.openImage(correctedDir + f);
            if (imp == null) continue;

            ImagePlus isolated = SignalIsolator.isolate(imp, macroCommands);
            imp.close();

            if (isolated == null) continue;

            // Apply LUT if selected
            if (!"None".equalsIgnoreCase(aviLut)) {
                IJ.run(isolated, aviLut, "");
            }

            // Save filtered stack to .circadian/filtered/
            String filteredPath = filteredDir + baseName + "_filtered.tif";
            FileSaver fs = new FileSaver(isolated);
            if (isolated.getStackSize() > 1) {
                fs.saveAsTiffStack(filteredPath);
            } else {
                fs.saveAsTiff(filteredPath);
            }
            IJ.log("    Saved filtered stack: " + baseName + "_filtered.tif");

            // AVI export with preview on first file
            if (saveAvi) {
                if (!previewShown) {
                    // Show preview of first file so user can verify speed
                    isolated.show();
                    IJ.run(isolated, "Animation Options...", "speed=" + aviFps);
                    IJ.doCommand("Start Animation [\\]");

                    PipelineDialog previewDlg = new PipelineDialog("CHRONOS — AVI Preview", false);
                    previewDlg.addHeader("Video Preview — " + baseName);
                    previewDlg.addMessage("Playing at <b>" + aviFps + " fps</b>.");
                    previewDlg.addMessage("Check the playback speed, then click OK to continue.");
                    previewDlg.addSpacer(4);
                    previewDlg.addNumericField("Adjust FPS", aviFps, 0);

                    previewDlg.showDialog();

                    IJ.doCommand("Stop Animation");
                    isolated.hide();

                    int newFps = Math.max(1, (int) previewDlg.getNextNumber());
                    if (newFps != aviFps) {
                        aviFps = newFps;
                        IJ.log("    FPS adjusted to " + aviFps);
                    }
                    previewShown = true;
                }

                String aviPath = videosDir + baseName + ".avi";
                isolated.show();
                IJ.run(isolated, "AVI... ",
                        "compression=JPEG frame=" + aviFps + " save=[" + aviPath + "]");
                isolated.hide();
                IJ.log("    Saved AVI: " + baseName + ".avi (" + aviFps + " fps)");
            }

            // Extract traces from isolated image
            Roi[] rois = loadRoisForFile(roisDir, baseName);
            if (rois != null && rois.length > 0) {
                double[][] traces = TraceExtractor.extractTraces(isolated, rois);
                String[] headers = new String[rois.length];
                for (int r = 0; r < rois.length; r++) {
                    String name = rois[r].getName();
                    headers[r] = (name != null && !name.isEmpty()) ? name : "ROI_" + (r + 1);
                }
                String outPath = tracesDir + "Isolated_Traces_" + baseName + ".csv";
                CsvWriter.writeTraces(outPath, headers, traces, config.frameIntervalMin);
                IJ.log("    Saved isolated traces (" + rois.length + " ROIs)");
            } else {
                // Whole-image extraction from isolated
                int nFrames = isolated.getStackSize();
                double[] trace = new double[nFrames];
                ImageStack stack = isolated.getStack();
                for (int i = 1; i <= nFrames; i++) {
                    float[] pixels = (float[]) stack.getProcessor(i).convertToFloatProcessor().getPixels();
                    double sum = 0;
                    for (float p : pixels) sum += p;
                    trace[i - 1] = sum / pixels.length;
                }
                String path = tracesDir + "Isolated_WholeImage_" + baseName + ".csv";
                PrintWriter pw = null;
                try {
                    pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
                    pw.println("Frame,Time_hours,MeanIntensity");
                    for (int i = 0; i < nFrames; i++) {
                        double timeH = i * config.frameIntervalMin / 60.0;
                        pw.println(i + "," + IJ.d2s(timeH, 4) + "," + IJ.d2s(trace[i], 6));
                    }
                } catch (IOException e) {
                    IJ.log("Error saving isolated trace: " + e.getMessage());
                } finally {
                    if (pw != null) pw.close();
                }
                IJ.log("    Saved isolated whole-image trace");
            }
            isolated.close();
        }

        // Run visualization on the isolated traces
        if (isolationApplied) {
            IJ.log("  Running visualization on isolated traces...");
            VisualizationAnalysis vizModule = new VisualizationAnalysis();
            vizModule.setHeadless(false);
            vizModule.setParallelThreads(1);
            try {
                vizModule.execute(directory);
            } catch (Exception e) {
                IJ.log("  Visualization error: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Stage 7: Cell Tracking
    // =========================================================================

    private void doCellTracking() {
        // Check availability
        if (!StarDistDetector.isAvailable()) {
            IJ.log("  TrackMate + StarDist not available. Skipping cell tracking.");
            IJ.log("  " + StarDistDetector.getAvailabilityMessage());
            return;
        }

        PipelineDialog dlg = new PipelineDialog("CHRONOS — Cell Tracking");
        dlg.addHeader("Cell Tracking (Optional)");
        dlg.addMessage("Track individual cells using TrackMate + StarDist?");
        dlg.addToggle("Enable cell tracking", false);
        dlg.addSpacer(4);
        dlg.addNumericField("Max linking distance (px)", config.trackMaxLinkDistance, 1);
        dlg.addNumericField("Max gap (frames)", config.trackMaxGapFrames, 0);
        dlg.addNumericField("Gap closing distance (px)", config.trackGapClosingDistance, 1);
        dlg.addNumericField("Min track duration (frames)", config.trackMinDurationFrames, 0);

        if (!dlg.showDialog()) return;

        boolean enabled = dlg.getNextBoolean();
        config.trackMaxLinkDistance = dlg.getNextNumber();
        config.trackMaxGapFrames = (int) dlg.getNextNumber();
        config.trackGapClosingDistance = dlg.getNextNumber();
        config.trackMinDurationFrames = (int) dlg.getNextNumber();

        if (!enabled) {
            IJ.log("  Cell tracking: skipped");
            return;
        }

        trackingRan = true;
        new File(trackingDir).mkdirs();

        String[] corrected = listTifs(new File(correctedDir));
        for (String f : corrected) {
            String baseName = stripSuffix(stripExtension(f), "_corrected");
            IJ.log("  Tracking: " + baseName);

            ImagePlus imp = IJ.openImage(correctedDir + f);
            if (imp == null) continue;

            try {
                TrackingResult result = TrackMateRunner.run(imp, 1,
                        config.trackMaxLinkDistance, config.trackMaxGapFrames,
                        config.trackGapClosingDistance);

                // Filter by min duration
                List<CellTrack> longTracks = new ArrayList<CellTrack>();
                for (CellTrack track : result.tracks) {
                    if (track.duration() >= config.trackMinDurationFrames) {
                        longTracks.add(track);
                    }
                }

                IJ.log("    Found " + longTracks.size() + " tracks (filtered from " + result.tracks.size() + ")");

                // Save comprehensive per-object-per-frame CSV
                saveDetailedTracks(baseName, longTracks, imp, config.frameIntervalMin);

            } catch (Exception e) {
                IJ.log("    Tracking failed: " + e.getMessage());
            }

            imp.close();
        }
    }

    /**
     * Save detailed per-object-per-frame tracking CSV with all requested metrics.
     */
    private void saveDetailedTracks(String baseName, List<CellTrack> tracks,
                                    ImagePlus imp, double frameIntervalMin) {
        String path = trackingDir + baseName + "_tracks.csv";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("TrackID,Frame,Time_hours,CentroidX,CentroidY,MeanIntensity,TotalIntensity,Area,Perimeter");

            ImageStack stack = imp.getStack();
            int width = imp.getWidth();

            for (CellTrack track : tracks) {
                for (int i = 0; i < track.length(); i++) {
                    int frame = track.frames[i];
                    double timeH = frame * frameIntervalMin / 60.0;
                    double cx = track.x[i];
                    double cy = track.y[i];
                    double area = track.area[i];

                    // Compute radius from area (area = pi*r^2)
                    double radius = Math.sqrt(area / Math.PI);
                    double perimeter = 2.0 * Math.PI * radius;

                    // Measure mean and total intensity in a circular region
                    double meanIntensity = 0;
                    double totalIntensity = 0;
                    if (frame + 1 <= stack.getSize()) {
                        float[] pixels = (float[]) stack.getProcessor(frame + 1)
                                .convertToFloatProcessor().getPixels();
                        int intRadius = Math.max(1, (int) Math.ceil(radius));
                        double sum = 0;
                        int count = 0;
                        int icx = (int) Math.round(cx);
                        int icy = (int) Math.round(cy);
                        for (int dy = -intRadius; dy <= intRadius; dy++) {
                            for (int dx = -intRadius; dx <= intRadius; dx++) {
                                if (dx * dx + dy * dy <= intRadius * intRadius) {
                                    int px = icx + dx;
                                    int py = icy + dy;
                                    if (px >= 0 && px < width && py >= 0 && py < imp.getHeight()) {
                                        sum += pixels[py * width + px];
                                        count++;
                                    }
                                }
                            }
                        }
                        meanIntensity = (count > 0) ? sum / count : 0;
                        totalIntensity = sum;
                    }

                    pw.println(track.trackID + "," + frame + ","
                            + IJ.d2s(timeH, 4) + ","
                            + IJ.d2s(cx, 2) + "," + IJ.d2s(cy, 2) + ","
                            + IJ.d2s(meanIntensity, 4) + ","
                            + IJ.d2s(totalIntensity, 4) + ","
                            + IJ.d2s(area, 2) + ","
                            + IJ.d2s(perimeter, 2));
                }
            }
        } catch (IOException e) {
            IJ.log("    Error saving tracks: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
        IJ.log("    Saved: " + baseName + "_tracks.csv");
    }

    // =========================================================================
    // Stage 8: Completion
    // =========================================================================

    private void showCompletionDialog(long totalElapsed) {
        String duration = formatDuration(totalElapsed);

        IJ.log("");
        IJ.log("=== CHRONOS Guided Pipeline Complete ===");
        IJ.log("  Stacks processed: " + stacksProcessed);
        IJ.log("  Registration method: " + registrationMethodUsed);
        IJ.log("  ROI files: " + roisDefined);
        IJ.log("  Trace files: " + tracesExtracted);
        IJ.log("  Signal isolation: " + (isolationApplied ? "Yes" : "No"));
        IJ.log("  Cell tracking: " + (trackingRan ? "Yes" : "No"));
        IJ.log("  Total time: " + duration);

        PipelineDialog dlg = new PipelineDialog("CHRONOS — Complete");
        dlg.addHeader("Pipeline Complete");
        dlg.addMessage("Stacks processed: <b>" + stacksProcessed + "</b>");
        dlg.addMessage("Registration method: <b>" + registrationMethodUsed + "</b>");
        dlg.addMessage("ROI files: <b>" + roisDefined + "</b>");
        dlg.addMessage("Trace files: <b>" + tracesExtracted + "</b>");
        dlg.addMessage("Signal isolation: <b>" + (isolationApplied ? "Yes" : "No") + "</b>");
        dlg.addMessage("Cell tracking: <b>" + (trackingRan ? "Yes" : "No") + "</b>");
        dlg.addSpacer(4);
        dlg.addMessage("Total time: <b>" + duration + "</b>");
        dlg.showDialog();
    }

    // =========================================================================
    // Utility methods
    // =========================================================================

    private static String[] listTifs(File dir) {
        if (dir == null || !dir.exists()) return new String[0];
        String[] files = dir.list(new FilenameFilter() {
            public boolean accept(File d, String name) {
                String lower = name.toLowerCase();
                return lower.endsWith(".tif") || lower.endsWith(".tiff");
            }
        });
        if (files != null) Arrays.sort(files);
        return files != null ? files : new String[0];
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String stripSuffix(String name, String suffix) {
        if (name.endsWith(suffix)) {
            return name.substring(0, name.length() - suffix.length());
        }
        return name;
    }

    private static String displayToRef(String display) {
        if ("First Frame".equals(display)) return "first";
        if ("Median Projection".equals(display)) return "median";
        return "mean";
    }

    private static ImagePlus computeMeanProjection(ImagePlus imp) {
        if (imp.getStackSize() <= 1) return imp.duplicate();
        ZProjector zp = new ZProjector(imp);
        zp.setMethod(ZProjector.AVG_METHOD);
        zp.doProjection();
        return zp.getProjection();
    }

    private boolean askReuse(String what, String details) {
        PipelineDialog dlg = new PipelineDialog("CHRONOS — Reuse " + what);
        dlg.addHeader("Existing " + what + " Found");
        dlg.addMessage(details);
        dlg.addSpacer(4);
        dlg.addToggle("Use existing", true);
        if (dlg.showDialog()) {
            return dlg.getNextBoolean();
        }
        return true;
    }

    private static ImagePlus rotateStack(ImagePlus imp, double angle) {
        double rad = Math.toRadians(Math.abs(angle));
        int origW = imp.getWidth();
        int origH = imp.getHeight();
        int newW = (int) Math.ceil(origW * Math.abs(Math.cos(rad)) + origH * Math.abs(Math.sin(rad)));
        int newH = (int) Math.ceil(origW * Math.abs(Math.sin(rad)) + origH * Math.abs(Math.cos(rad)));
        int padX = (newW - origW) / 2;
        int padY = (newH - origH) / 2;

        ImageStack rotatedStack = new ImageStack(newW, newH);
        ImageStack srcStack = imp.getStack();
        for (int s = 1; s <= srcStack.getSize(); s++) {
            ImageProcessor ip = srcStack.getProcessor(s);
            ImageProcessor enlarged = ip.createProcessor(newW, newH);
            enlarged.insert(ip, padX, padY);
            enlarged.setInterpolationMethod(ImageProcessor.BILINEAR);
            enlarged.setBackgroundValue(0);
            enlarged.rotate(-angle);
            rotatedStack.addSlice(srcStack.getSliceLabel(s), enlarged);
        }
        ImagePlus result = new ImagePlus(imp.getTitle(), rotatedStack);
        result.setCalibration(imp.getCalibration().copy());
        imp.close();
        return result;
    }

    private static void rotateProcessor(ImagePlus proj, double angle) {
        ImageProcessor ip = proj.getProcessor();
        double rad = Math.toRadians(Math.abs(angle));
        int newW = (int) Math.ceil(proj.getWidth() * Math.abs(Math.cos(rad))
                + proj.getHeight() * Math.abs(Math.sin(rad)));
        int newH = (int) Math.ceil(proj.getWidth() * Math.abs(Math.sin(rad))
                + proj.getHeight() * Math.abs(Math.cos(rad)));
        ImageProcessor enlarged = ip.createProcessor(newW, newH);
        enlarged.insert(ip, (newW - proj.getWidth()) / 2, (newH - proj.getHeight()) / 2);
        enlarged.setInterpolationMethod(ImageProcessor.BILINEAR);
        enlarged.setBackgroundValue(0);
        enlarged.rotate(-angle);
        proj.setProcessor(enlarged);
    }

    private static ImagePlus cropStack(ImagePlus imp, Rectangle crop) {
        ImageStack croppedStack = new ImageStack(crop.width, crop.height);
        ImageStack src = imp.getStack();
        for (int s = 1; s <= src.getSize(); s++) {
            ImageProcessor slice = src.getProcessor(s);
            slice.setRoi(crop.x, crop.y, crop.width, crop.height);
            croppedStack.addSlice(src.getSliceLabel(s), slice.crop());
        }
        ImagePlus result = new ImagePlus(imp.getTitle(), croppedStack);
        result.setCalibration(imp.getCalibration().copy());
        imp.close();
        return result;
    }

    private static Roi[] loadRoisForFile(String roisDir, String baseName) {
        // Try various naming conventions
        String[] candidates = {
            baseName + "_corrected_rois.zip",
            baseName + "_rois.zip",
            baseName + "_corrected.zip",
            baseName + ".zip"
        };
        for (String c : candidates) {
            File f = new File(roisDir + c);
            if (f.exists()) {
                try {
                    return RoiIO.loadRoisFromZip(f.getAbsolutePath());
                } catch (Exception e) {
                    // try next
                }
            }
        }
        return null;
    }

    // --- CSV helpers ---

    private static Map<String, Double> loadKeyValues(String path) {
        Map<String, Double> map = new LinkedHashMap<String, Double>();
        File f = new File(path);
        if (!f.exists()) return map;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                try {
                    map.put(line.substring(0, eq).trim(),
                            Double.parseDouble(line.substring(eq + 1).trim()));
                } catch (NumberFormatException e) { /* skip */ }
            }
        } catch (IOException e) { /* ignore */ }
        finally { if (br != null) { try { br.close(); } catch (IOException ignored) {} } }
        return map;
    }

    private static void saveKeyValues(String path, Map<String, Double> map, String header) {
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("# CHRONOS " + header);
            for (Map.Entry<String, Double> entry : map.entrySet()) {
                pw.println(entry.getKey() + "=" + entry.getValue());
            }
        } catch (IOException e) { /* ignore */ }
        finally { if (pw != null) pw.close(); }
    }

    private static Map<String, Rectangle> loadCropRegions(String path) {
        Map<String, Rectangle> crops = new LinkedHashMap<String, Rectangle>();
        File f = new File(path);
        if (!f.exists()) return crops;
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
                String[] parts = line.substring(eq + 1).trim().split(",");
                if (parts.length == 4) {
                    try {
                        crops.put(key, new Rectangle(
                                Integer.parseInt(parts[0].trim()),
                                Integer.parseInt(parts[1].trim()),
                                Integer.parseInt(parts[2].trim()),
                                Integer.parseInt(parts[3].trim())));
                    } catch (NumberFormatException e) { /* skip */ }
                }
            }
        } catch (IOException e) { /* ignore */ }
        finally { if (br != null) { try { br.close(); } catch (IOException ignored) {} } }
        return crops;
    }

    private static void saveCropRegions(String path, Map<String, Rectangle> crops) {
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("# CHRONOS crop regions");
            for (Map.Entry<String, Rectangle> entry : crops.entrySet()) {
                Rectangle r = entry.getValue();
                pw.println(entry.getKey() + "=" + r.x + "," + r.y + "," + r.width + "," + r.height);
            }
        } catch (IOException e) { /* ignore */ }
        finally { if (pw != null) pw.close(); }
    }

    private static void saveRegistrationTransforms(String path, RegistrationResult result) {
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("# CHRONOS Registration Transforms");
            pw.println("# Method: " + result.method);
            pw.println("# Reference: " + result.reference);
            pw.println("# MaxShift: " + IJ.d2s(result.maxShift, 4));
            pw.println("Frame,ShiftX_px,ShiftY_px,Quality");
            for (int i = 0; i < result.nFrames; i++) {
                pw.println(i + "," + IJ.d2s(result.shiftX[i], 4) + ","
                        + IJ.d2s(result.shiftY[i], 4) + ","
                        + IJ.d2s(result.quality[i], 4));
            }
        } catch (IOException e) { /* ignore */ }
        finally { if (pw != null) pw.close(); }
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }
}
