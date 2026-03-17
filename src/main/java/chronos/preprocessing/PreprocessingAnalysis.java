package chronos.preprocessing;

import chronos.Analysis;
import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.io.IncucyteImporter;
import chronos.ui.PipelineDialog;
import chronos.ui.ToggleSwitch;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.FileSaver;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module 1: Pre-processing.
 * Applies 6 sequential steps to each TIF stack:
 * 1. Frame binning
 * 2. Motion correction
 * 3. Background subtraction
 * 4. Bleach/decay correction
 * 5. Spatial filtering
 * 6. Temporal filtering
 *
 * Saves corrected stacks to .circadian/corrected/
 */
public class PreprocessingAnalysis implements Analysis {

    private boolean headless = false;
    private int parallelThreads = 1;

    @Override
    public String getName() {
        return "Pre-processing";
    }

    @Override
    public int getIndex() {
        return 1;
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
    public boolean execute(String directory) {
        // Load config
        SessionConfig config = SessionConfigIO.readFromDirectory(directory);

        // Show dialog unless headless
        if (!headless) {
            if (!showDialog(config)) {
                return false;
            }
            // Save updated config
            SessionConfigIO.writeToDirectory(directory, config);
        }

        // --- Incucyte Detection & Assembly ---
        File dir = new File(directory);
        String assembledDir = directory + ".circadian" + File.separator + "assembled" + File.separator;
        boolean useAssembledDir = false;

        if (IncucyteImporter.isIncucyteDirectory(dir)) {
            Map<String, List<IncucyteImporter.IncucyteFrame>> groups =
                    IncucyteImporter.groupAndSort(dir);

            // Check if stacks have already been assembled
            File assembledFile = new File(assembledDir);
            boolean alreadyAssembled = false;
            if (assembledFile.exists()) {
                String[] existing = assembledFile.list();
                if (existing != null && existing.length > 0) {
                    alreadyAssembled = true;
                }
            }

            if (!alreadyAssembled) {
                IJ.log("");
                IJ.log("Incucyte image sequence detected!");
                IJ.log("  Found " + groups.size() + " series:");
                int totalFrames = 0;
                for (Map.Entry<String, List<IncucyteImporter.IncucyteFrame>> entry : groups.entrySet()) {
                    int nFrames = entry.getValue().size();
                    totalFrames += nFrames;
                    List<IncucyteImporter.IncucyteFrame> frames = entry.getValue();
                    double spanMinutes = frames.get(frames.size() - 1).totalMinutes - frames.get(0).totalMinutes;
                    double spanHours = spanMinutes / 60.0;
                    IJ.log("    " + entry.getKey() + ": " + nFrames + " frames, "
                            + String.format("%.1f", spanHours) + " hours");
                }

                if (!headless) {
                    PipelineDialog incuDlg = new PipelineDialog("CHRONOS — Incucyte Import");
                    incuDlg.addHeader("Incucyte Sequence Detected");
                    incuDlg.addMessage("Found <b>" + totalFrames + "</b> individual Incucyte frames "
                            + "across <b>" + groups.size() + "</b> series.");
                    incuDlg.addMessage("These will be assembled into time-ordered stacks "
                            + "before pre-processing.");
                    incuDlg.addSpacer(4);
                    for (Map.Entry<String, List<IncucyteImporter.IncucyteFrame>> entry : groups.entrySet()) {
                        List<IncucyteImporter.IncucyteFrame> frames = entry.getValue();
                        double spanHours = (frames.get(frames.size() - 1).totalMinutes
                                - frames.get(0).totalMinutes) / 60.0;
                        incuDlg.addMessage(entry.getKey() + ": " + frames.size() + " frames, "
                                + String.format("%.1f", spanHours) + "h span");
                    }
                    incuDlg.addSpacer(4);
                    incuDlg.addHeader("Options");
                    incuDlg.addToggle("Assemble stacks", true);

                    if (!incuDlg.showDialog()) {
                        return false;
                    }

                    boolean doAssemble = incuDlg.getNextBoolean();
                    if (!doAssemble) {
                        IJ.log("  Incucyte assembly skipped by user.");
                    } else {
                        IJ.log("");
                        IJ.log("Assembling Incucyte frames into stacks...");
                        List<String> assembled = IncucyteImporter.assembleStacks(
                                dir, assembledDir, config.frameIntervalMin);
                        IJ.log("Assembly complete: " + assembled.size() + " stack(s) created.");
                        useAssembledDir = true;
                    }
                } else {
                    // Headless mode: auto-assemble
                    IJ.log("Assembling Incucyte frames into stacks (headless)...");
                    List<String> assembled = IncucyteImporter.assembleStacks(
                            dir, assembledDir, config.frameIntervalMin);
                    IJ.log("Assembly complete: " + assembled.size() + " stack(s) created.");
                    useAssembledDir = true;
                }
            } else {
                IJ.log("Incucyte stacks already assembled in .circadian/assembled/");
                useAssembledDir = true;
            }
        }

        // Determine which directory to scan for TIF stacks
        final String processDir;
        if (useAssembledDir) {
            processDir = assembledDir;
        } else {
            processDir = directory;
        }

        // Scan for TIF files
        File scanDir = new File(processDir);
        String[] tifFiles = scanDir.list(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                String lower = name.toLowerCase();
                return lower.endsWith(".tif") || lower.endsWith(".tiff");
            }
        });

        if (tifFiles == null || tifFiles.length == 0) {
            IJ.log("Pre-processing: No TIF files found.");
            return false;
        }

        // Ensure output directory exists
        String correctedDir = directory + ".circadian" + File.separator + "corrected" + File.separator;
        new File(correctedDir).mkdirs();

        long totalStart = System.currentTimeMillis();
        IJ.log("Pre-processing " + tifFiles.length + " file(s)...");
        IJ.log("  Reporter type: " + config.reporterType);

        // Per-file crop regions: stored in .circadian/crop_regions.txt
        String cropRegionsPath = directory + ".circadian" + File.separator + "crop_regions.txt";
        Map<String, Rectangle> perFileCrops = loadCropRegions(cropRegionsPath);

        // Interactive crop: show each image sequentially for per-file crop drawing
        if (config.cropEnabled && !headless) {
            // Check if we already have saved crops for all files
            boolean allCropsDefined = true;
            for (String tf : tifFiles) {
                String base = stripExtension(tf);
                if (!perFileCrops.containsKey(base)) {
                    allCropsDefined = false;
                    break;
                }
            }

            if (!allCropsDefined) {
                IJ.log("");
                IJ.log("Crop: Drawing crop regions for each image...");

                String[] sortedTifs = tifFiles.clone();
                Arrays.sort(sortedTifs);

                for (int ti = 0; ti < sortedTifs.length; ti++) {
                    String tf = sortedTifs[ti];
                    String baseName = stripExtension(tf);

                    // Skip if crop already defined for this file
                    if (perFileCrops.containsKey(baseName)) {
                        IJ.log("  [" + (ti + 1) + "/" + sortedTifs.length + "] "
                                + baseName + ": using saved crop");
                        continue;
                    }

                    ImagePlus imp = IJ.openImage(processDir + tf);
                    if (imp == null) {
                        IJ.log("  [" + (ti + 1) + "/" + sortedTifs.length + "] "
                                + baseName + ": could not load, skipping");
                        continue;
                    }

                    ImagePlus proj;
                    if (imp.getStackSize() > 1) {
                        ZProjector zp = new ZProjector(imp);
                        zp.setMethod(ZProjector.AVG_METHOD);
                        zp.doProjection();
                        proj = zp.getProjection();
                    } else {
                        proj = imp.duplicate();
                    }
                    proj.setTitle("CROP [" + (ti + 1) + "/" + sortedTifs.length + "] — " + baseName);
                    IJ.run(proj, "Enhance Contrast", "saturated=0.35");
                    imp.close();

                    proj.show();

                    WaitForUserDialog cropWait = new WaitForUserDialog(
                            "CHRONOS — Crop [" + (ti + 1) + "/" + sortedTifs.length + "]",
                            "Image: " + baseName + "\n\n" +
                            "Draw a RECTANGLE around the sample area,\n" +
                            "then press OK.\n\n" +
                            "Press ESC to skip cropping for this image.");
                    cropWait.show();

                    if (!cropWait.escPressed()) {
                        Roi cropRoi = proj.getRoi();
                        if (cropRoi != null && cropRoi.getType() == Roi.RECTANGLE) {
                            Rectangle r = cropRoi.getBounds();
                            perFileCrops.put(baseName, r);
                            IJ.log("  [" + (ti + 1) + "/" + sortedTifs.length + "] "
                                    + baseName + ": crop " + r.width + "x" + r.height
                                    + " at (" + r.x + "," + r.y + ")");
                        } else {
                            IJ.log("  [" + (ti + 1) + "/" + sortedTifs.length + "] "
                                    + baseName + ": no rectangle drawn, skipping crop");
                        }
                    } else {
                        IJ.log("  [" + (ti + 1) + "/" + sortedTifs.length + "] "
                                + baseName + ": crop skipped");
                    }

                    proj.close();
                }

                // Save all per-file crops
                if (!perFileCrops.isEmpty()) {
                    saveCropRegions(cropRegionsPath, perFileCrops);
                }
            } else {
                IJ.log("  Using saved per-file crop regions (" + perFileCrops.size() + " files)");
            }
        }

        for (int f = 0; f < tifFiles.length; f++) {
            String fileName = tifFiles[f];
            String filePath = processDir + fileName;
            IJ.log("");
            IJ.log("[" + (f + 1) + "/" + tifFiles.length + "] " + fileName);

            long fileStart = System.currentTimeMillis();

            // Load the TIF stack
            ImagePlus imp = IJ.openImage(filePath);
            if (imp == null) {
                IJ.log("  ERROR: Could not open " + fileName);
                continue;
            }

            IJ.log("  Loaded: " + imp.getWidth() + "x" + imp.getHeight()
                    + " x " + imp.getStackSize() + " frames");

            // Step 0: Crop (per-file crop regions)
            String baseName0 = fileName;
            int dotIdx0 = baseName0.lastIndexOf('.');
            if (dotIdx0 > 0) baseName0 = baseName0.substring(0, dotIdx0);
            Rectangle fileCrop = perFileCrops.get(baseName0);

            if (config.cropEnabled && fileCrop != null) {
                IJ.log("  Step 0: Crop (" + fileCrop.width + "x" + fileCrop.height
                        + " at " + fileCrop.x + "," + fileCrop.y + ")");
                ImageStack croppedStack = new ImageStack(fileCrop.width, fileCrop.height);
                ImageStack srcStack = imp.getStack();
                for (int s = 1; s <= srcStack.getSize(); s++) {
                    ImageProcessor slice = srcStack.getProcessor(s);
                    slice.setRoi(fileCrop.x, fileCrop.y, fileCrop.width, fileCrop.height);
                    croppedStack.addSlice(srcStack.getSliceLabel(s), slice.crop());
                }
                ImagePlus cropped = new ImagePlus(imp.getTitle(), croppedStack);
                cropped.setCalibration(imp.getCalibration().copy());
                imp.close();
                imp = cropped;
            } else if (config.cropEnabled) {
                IJ.log("  Step 0: Crop — no crop region defined for this file, skipping");
            } else {
                IJ.log("  Step 0: Crop — skipped");
            }

            // Step 1: Frame Binning
            if (config.binningEnabled && config.binFactor > 1) {
                IJ.log("  Step 1: Frame binning (factor=" + config.binFactor
                        + ", method=" + config.binMethod + ")");
                boolean useMean = "Mean".equalsIgnoreCase(config.binMethod);
                ImagePlus binned = FrameBinner.bin(imp, config.binFactor, useMean);
                if (binned != imp) {
                    imp.close();
                    imp = binned;
                }
                IJ.log("    -> " + imp.getStackSize() + " frames after binning");
            } else {
                IJ.log("  Step 1: Frame binning — skipped");
            }

            // Step 2: Motion Correction
            if (config.motionCorrectionEnabled) {
                ImagePlus corrected;
                if ("SIFT".equalsIgnoreCase(config.motionCorrectionMethod)) {
                    IJ.log("  Step 2: Motion correction (SIFT)");
                    corrected = MotionCorrector.correctWithSIFT(imp);
                } else {
                    IJ.log("  Step 2: Motion correction (Cross-Correlation, ref=" + config.motionCorrectionReference + ")");
                    corrected = MotionCorrector.correct(imp, config.motionCorrectionReference);
                }
                if (corrected != imp) {
                    imp.close();
                    imp = corrected;
                }
            } else {
                IJ.log("  Step 2: Motion correction — skipped");
            }

            // Step 3: Background Subtraction
            if (!"None".equalsIgnoreCase(config.backgroundMethod)) {
                IJ.log("  Step 3: Background subtraction (" + config.backgroundMethod + ")");
                ImagePlus bgCorrected;
                if ("Rolling Ball".equalsIgnoreCase(config.backgroundMethod)) {
                    bgCorrected = BackgroundCorrector.rollingBall(imp, config.backgroundRadius);
                } else if ("Minimum Projection".equalsIgnoreCase(config.backgroundMethod)) {
                    bgCorrected = BackgroundCorrector.minimumProjection(imp);
                } else {
                    // Fixed ROI would require an ROI — skip if not provided
                    IJ.log("    Fixed ROI not available in batch mode, skipping");
                    bgCorrected = imp;
                }
                if (bgCorrected != imp) {
                    imp.close();
                    imp = bgCorrected;
                }
            } else {
                IJ.log("  Step 3: Background subtraction — skipped");
            }

            // Step 4: Bleach / Decay Correction
            if (!"None".equalsIgnoreCase(config.bleachMethod)) {
                IJ.log("  Step 4: Bleach correction (" + config.bleachMethod + ")");

                double[] meanTrace = BleachCorrector.extractMeanTrace(imp);
                double[] factors;

                if ("Mono-exponential".equalsIgnoreCase(config.bleachMethod)) {
                    factors = BleachCorrector.monoExponentialFit(meanTrace);
                } else if ("Bi-exponential".equalsIgnoreCase(config.bleachMethod)) {
                    factors = BleachCorrector.biExponentialFit(meanTrace);
                } else if ("Sliding Percentile".equalsIgnoreCase(config.bleachMethod)) {
                    double[] baseline = BleachCorrector.slidingPercentile(
                            meanTrace, config.bleachPercentileWindow, config.bleachPercentile);
                    // Convert baseline to correction factors (normalize to first value)
                    factors = new double[baseline.length];
                    double b0 = baseline[0];
                    if (b0 <= 0) b0 = 1.0;
                    for (int i = 0; i < baseline.length; i++) {
                        factors[i] = baseline[i] / b0;
                    }
                } else if ("Simple Ratio".equalsIgnoreCase(config.bleachMethod)) {
                    factors = BleachCorrector.simpleRatio(meanTrace);
                } else {
                    factors = null;
                }

                if (factors != null) {
                    ImagePlus bleachCorrected = BleachCorrector.correctStack(imp, factors);
                    if (bleachCorrected != imp) {
                        imp.close();
                        imp = bleachCorrected;
                    }
                }
            } else {
                IJ.log("  Step 4: Bleach correction — skipped");
            }

            // Step 5: Spatial Filter
            if (!"None".equalsIgnoreCase(config.spatialFilterType)) {
                IJ.log("  Step 5: Spatial filter (" + config.spatialFilterType
                        + ", radius=" + config.spatialFilterRadius + ")");
                ImagePlus filtered;
                if ("Gaussian".equalsIgnoreCase(config.spatialFilterType)) {
                    filtered = SpatialFilter.gaussian(imp, config.spatialFilterRadius);
                } else if ("Median".equalsIgnoreCase(config.spatialFilterType)) {
                    filtered = SpatialFilter.median(imp, config.spatialFilterRadius);
                } else {
                    filtered = imp;
                }
                if (filtered != imp) {
                    imp.close();
                    imp = filtered;
                }
            } else {
                IJ.log("  Step 5: Spatial filter — skipped");
            }

            // Step 6: Temporal Filter
            if (!"None".equalsIgnoreCase(config.temporalFilterType)) {
                IJ.log("  Step 6: Temporal filter (" + config.temporalFilterType
                        + ", window=" + config.temporalFilterWindow + ")");
                if ("Moving Average".equalsIgnoreCase(config.temporalFilterType)) {
                    ImagePlus tempFiltered = TemporalFilter.movingAverage(imp, config.temporalFilterWindow);
                    if (tempFiltered != imp) {
                        imp.close();
                        imp = tempFiltered;
                    }
                }
                // Savitzky-Golay on full stacks is too expensive; use only on traces (Module 3)
                if ("Savitzky-Golay".equalsIgnoreCase(config.temporalFilterType)) {
                    IJ.log("    Savitzky-Golay applied as Moving Average on image stacks");
                    ImagePlus tempFiltered = TemporalFilter.movingAverage(imp, config.temporalFilterWindow);
                    if (tempFiltered != imp) {
                        imp.close();
                        imp = tempFiltered;
                    }
                }
            } else {
                IJ.log("  Step 6: Temporal filter — skipped");
            }

            // Save corrected stack
            String baseName = fileName;
            int dotIdx = baseName.lastIndexOf('.');
            if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);
            String outputPath = correctedDir + baseName + "_corrected.tif";

            IJ.log("  Saving corrected stack...");
            FileSaver saver = new FileSaver(imp);
            if (imp.getStackSize() > 1) {
                saver.saveAsTiffStack(outputPath);
            } else {
                saver.saveAsTiff(outputPath);
            }

            imp.close();

            long fileElapsed = System.currentTimeMillis() - fileStart;
            IJ.log("  Done in " + formatDuration(fileElapsed));
            IJ.showProgress(f + 1, tifFiles.length);
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        IJ.log("");
        IJ.log("Pre-processing complete. Total time: " + formatDuration(totalElapsed));

        return true;
    }

    /**
     * Show the pre-processing settings dialog.
     * Updates the SessionConfig in place.
     *
     * @return true if the user pressed OK, false if cancelled
     */
    private boolean showDialog(SessionConfig config) {
        PipelineDialog dlg = new PipelineDialog("CHRONOS — Pre-processing");

        // --- Recording Setup ---
        dlg.addHeader("Recording Setup");
        String[] reporterTypes = {"Fluorescent", "Bioluminescence", "Calcium"};
        final JComboBox<String> reporterCombo = dlg.addChoice("Reporter Type", reporterTypes, config.reporterType);
        dlg.addNumericField("Frame Interval (minutes)", config.frameIntervalMin, 1);

        // --- Crop ---
        dlg.addSpacer(4);
        dlg.addHeader("Crop");
        dlg.addToggle("Crop images to sample region", config.cropEnabled);
        dlg.addHelpText("Draw a rectangle on each image to exclude empty well space. Each image gets its own crop region since slices may be positioned differently.");

        // --- Frame Binning ---
        dlg.addSpacer(4);
        dlg.addHeader("Frame Binning");
        final ToggleSwitch binToggle = dlg.addToggle("Enable", config.binningEnabled);
        dlg.addHelpText("Reduces temporal resolution by averaging or summing groups of consecutive frames. Useful for noisy recordings — increases SNR at the cost of time resolution.");
        dlg.addNumericField("Bin Factor", config.binFactor, 0);
        String[] binMethods = {"Mean", "Sum"};
        dlg.addChoice("Method", binMethods, config.binMethod);

        // --- Motion Correction ---
        dlg.addSpacer(4);
        dlg.addHeader("Motion Correction");
        dlg.addToggle("Enable", config.motionCorrectionEnabled);
        dlg.addHelpText("Corrects sample drift across frames. SIFT is recommended — handles rotation and is robust to intensity changes (e.g. circadian oscillations). Cross-Correlation is faster but translation-only.");
        String[] mcMethods = {"SIFT", "Cross-Correlation"};
        dlg.addChoice("Method", mcMethods, config.motionCorrectionMethod);
        String[] refMethods = {"Mean Projection", "Median Projection", "First Frame"};
        String currentRef = refToDisplay(config.motionCorrectionReference);
        dlg.addChoice("Reference (for Cross-Correlation)", refMethods, currentRef);

        // --- Background Subtraction ---
        dlg.addSpacer(4);
        dlg.addHeader("Background Subtraction");
        dlg.addHelpText("Removes non-uniform background illumination. Rolling Ball estimates background as a ball rolling under the intensity surface. Minimum Projection subtracts the minimum value at each pixel across all frames.");
        String[] bgMethods = {"None", "Rolling Ball", "Minimum Projection"};
        dlg.addChoice("Method", bgMethods, config.backgroundMethod);
        dlg.addNumericField("Radius (pixels)", config.backgroundRadius, 0);

        // --- Bleach / Decay Correction ---
        dlg.addSpacer(4);
        dlg.addHeader("Bleach / Decay Correction");
        dlg.addHelpText("Corrects for signal decay over time. Auto-selected based on reporter type: Sliding Percentile for bioluminescence (luciferin depletion), Bi-exponential for fluorescent/calcium reporters (photobleaching).");
        String[] bleachMethods = {"None", "Mono-exponential", "Bi-exponential",
                "Sliding Percentile", "Simple Ratio"};
        final JComboBox<String> bleachCombo = dlg.addChoice("Method", bleachMethods, config.bleachMethod);
        dlg.addNumericField("Percentile Window (frames)", config.bleachPercentileWindow, 0);
        dlg.addNumericField("Percentile (%)", config.bleachPercentile, 1);

        // Auto-select bleach method when reporter type changes
        reporterCombo.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                    String reporter = (String) reporterCombo.getSelectedItem();
                    if ("Bioluminescence".equals(reporter)) {
                        bleachCombo.setSelectedItem("Sliding Percentile");
                    } else {
                        bleachCombo.setSelectedItem("Bi-exponential");
                    }
                }
            }
        });

        // --- Spatial Filter ---
        dlg.addSpacer(4);
        dlg.addHeader("Spatial Filter");
        dlg.addHelpText("Smooths each frame to reduce pixel noise. Gaussian preserves edges better at low sigma. Median is better for salt-and-pepper noise but can blur fine features.");
        String[] spatialTypes = {"None", "Gaussian", "Median"};
        dlg.addChoice("Type", spatialTypes, config.spatialFilterType);
        dlg.addNumericField("Sigma / Radius (pixels)", config.spatialFilterRadius, 1);

        // --- Temporal Filter ---
        dlg.addSpacer(4);
        dlg.addHeader("Temporal Filter");
        dlg.addHelpText("Smooths intensity across time at each pixel. Reduces frame-to-frame noise while preserving slower circadian oscillations. Keep window small relative to period.");
        String[] temporalTypes = {"None", "Moving Average", "Savitzky-Golay"};
        dlg.addChoice("Type", temporalTypes, config.temporalFilterType);
        dlg.addNumericField("Window (frames)", config.temporalFilterWindow, 0);

        // Show dialog
        if (!dlg.showDialog()) {
            return false;
        }

        // Read values back into config
        config.reporterType = dlg.getNextChoice();         // Reporter Type
        config.frameIntervalMin = dlg.getNextNumber();     // Frame Interval

        config.cropEnabled = dlg.getNextBoolean();           // Crop Enable

        config.binningEnabled = dlg.getNextBoolean();      // Binning Enable
        config.binFactor = Math.max(1, (int) dlg.getNextNumber()); // Bin Factor
        config.binMethod = dlg.getNextChoice();            // Bin Method

        config.motionCorrectionEnabled = dlg.getNextBoolean(); // Motion Enable
        config.motionCorrectionMethod = dlg.getNextChoice();  // Motion Method (SIFT or Cross-Correlation)
        config.motionCorrectionReference = displayToRef(dlg.getNextChoice()); // Reference (for Cross-Correlation)

        config.backgroundMethod = dlg.getNextChoice();     // BG Method
        config.backgroundRadius = dlg.getNextNumber();     // BG Radius

        config.bleachMethod = dlg.getNextChoice();         // Bleach Method
        config.bleachPercentileWindow = Math.max(1, (int) dlg.getNextNumber()); // Percentile Window
        config.bleachPercentile = dlg.getNextNumber();     // Percentile

        config.spatialFilterType = dlg.getNextChoice();    // Spatial Type
        config.spatialFilterRadius = dlg.getNextNumber();  // Spatial Radius

        config.temporalFilterType = dlg.getNextChoice();   // Temporal Type
        config.temporalFilterWindow = Math.max(1, (int) dlg.getNextNumber()); // Temporal Window

        return true;
    }

    /** Convert internal ref name to display name. */
    private static String refToDisplay(String ref) {
        if ("first".equalsIgnoreCase(ref)) return "First Frame";
        if ("median".equalsIgnoreCase(ref)) return "Median Projection";
        return "Mean Projection";
    }

    /** Convert display name to internal ref name. */
    private static String displayToRef(String display) {
        if ("First Frame".equals(display)) return "first";
        if ("Median Projection".equals(display)) return "median";
        return "mean";
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * Loads per-file crop regions from crop_regions.txt.
     * Format: baseName=x,y,width,height
     */
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
                String val = line.substring(eq + 1).trim();
                String[] parts = val.split(",");
                if (parts.length == 4) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        int w = Integer.parseInt(parts[2].trim());
                        int h = Integer.parseInt(parts[3].trim());
                        crops.put(key, new Rectangle(x, y, w, h));
                    } catch (NumberFormatException e) {
                        // skip malformed line
                    }
                }
            }
        } catch (IOException e) {
            IJ.log("Warning: could not read crop regions: " + e.getMessage());
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) {}
            }
        }
        return crops;
    }

    /**
     * Saves per-file crop regions to crop_regions.txt.
     */
    private static void saveCropRegions(String path, Map<String, Rectangle> crops) {
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("# CHRONOS per-file crop regions");
            pw.println("# Format: baseName=x,y,width,height");
            for (Map.Entry<String, Rectangle> entry : crops.entrySet()) {
                Rectangle r = entry.getValue();
                pw.println(entry.getKey() + "=" + r.x + "," + r.y + "," + r.width + "," + r.height);
            }
        } catch (IOException e) {
            IJ.log("Error saving crop regions: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
