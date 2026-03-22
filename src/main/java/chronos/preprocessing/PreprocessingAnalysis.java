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
import ij.WindowManager;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.FileSaver;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.URI;
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
    /** Set before showDialog so it can find frame_intervals.txt */
    private String config_directory = "";

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

        // Always show the settings dialog (headless only suppresses image windows,
        // not the configuration dialog — user must always be able to choose methods)
        config_directory = directory;
        if (!showDialog(config)) {
            return false;
        }
        // Save updated config
        SessionConfigIO.writeToDirectory(directory, config);

        // --- Detect existing assembled stacks from a previous run ---
        File dir = new File(directory);
        String assembledDir = directory + ".circadian" + File.separator + "assembled" + File.separator;
        boolean useAssembledDir = false;

        // Check if assembled stacks already exist (from a previous run)
        File assembledFile = new File(assembledDir);
        if (assembledFile.exists()) {
            String[] existingAssembled = assembledFile.list(new FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    String lower = name.toLowerCase();
                    return lower.endsWith(".tif") || lower.endsWith(".tiff");
                }
            });
            if (existingAssembled != null && existingAssembled.length > 0) {
                IJ.log("");
                IJ.log("Found " + existingAssembled.length + " previously assembled stack(s) in .circadian/assembled/");
                for (String s : existingAssembled) {
                    IJ.log("  - " + s);
                }
                useAssembledDir = true;
            }
        }

        // --- Check for new Incucyte frames even if assembled stacks exist ---
        if (useAssembledDir && IncucyteImporter.isIncucyteDirectory(dir)) {
            Map<String, List<IncucyteImporter.IncucyteFrame>> newGroups =
                    IncucyteImporter.groupAndSort(dir);
            int newFrameCount = 0;
            for (List<IncucyteImporter.IncucyteFrame> frames : newGroups.values()) {
                newFrameCount += frames.size();
            }
            if (newFrameCount > 0) {
                IJ.log("");
                IJ.log("Found " + newFrameCount + " new Incucyte frame(s) to add:");
                for (Map.Entry<String, List<IncucyteImporter.IncucyteFrame>> entry : newGroups.entrySet()) {
                    String existsLabel = new File(assembledDir + File.separator
                            + entry.getKey() + "_stack.tif").exists() ? " (append)" : " (new series)";
                    IJ.log("    " + entry.getKey() + ": " + entry.getValue().size()
                            + " frames" + existsLabel);
                }
                IJ.log("Updating assembled stacks...");
                List<String> updated = IncucyteImporter.updateStacks(
                        dir, assembledDir, config.frameIntervalMin);
                IJ.log("Update complete: " + updated.size() + " stack(s) updated/created.");
                saveFrameIntervalsFromAssembled(directory, assembledDir);
            }
        }

        // --- Incucyte Detection & Assembly (only if not already assembled) ---
        if (!useAssembledDir && IncucyteImporter.isIncucyteDirectory(dir)) {
            Map<String, List<IncucyteImporter.IncucyteFrame>> groups =
                    IncucyteImporter.groupAndSort(dir);

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

                // Save auto-detected frame intervals for future runs
                saveFrameIntervalsFromAssembled(directory, assembledDir);
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

        // Per-file crop regions, tight crop regions, and alignment angles
        String cropRegionsPath = directory + ".circadian" + File.separator + "crop_regions.txt";
        Map<String, Rectangle> perFileCrops = loadCropRegions(cropRegionsPath);
        String tightCropRegionsPath = directory + ".circadian" + File.separator + "tight_crop_regions.txt";
        Map<String, Rectangle> perFileTightCrops = loadCropRegions(tightCropRegionsPath);
        String alignAnglesPath = directory + ".circadian" + File.separator + "alignment_angles.txt";
        Map<String, Double> perFileAngles = loadAlignmentAngles(alignAnglesPath);

        boolean needCropInteraction = false;
        boolean needAlignInteraction = false;
        boolean needTightCropInteraction = false;

        // --- Check existing broad crop regions ---
        if (config.cropEnabled) {
            // Check if we already have saved crops for any files
            boolean anyCropsDefined = false;
            boolean allCropsDefined = true;
            for (String tf : tifFiles) {
                String base = stripExtension(tf);
                if (perFileCrops.containsKey(base)) {
                    anyCropsDefined = true;
                } else {
                    allCropsDefined = false;
                }
            }

            // Also check for legacy single-crop values in config
            if (!anyCropsDefined && config.cropX >= 0) {
                anyCropsDefined = true;
                // Migrate legacy single crop to per-file (apply same crop to all)
                IJ.log("  Migrating legacy crop region to per-file format");
                for (String tf : tifFiles) {
                    String base = stripExtension(tf);
                    perFileCrops.put(base, new Rectangle(config.cropX, config.cropY,
                            config.cropWidth, config.cropHeight));
                }
                allCropsDefined = true;
            }

            // If existing crops found, ask user whether to reuse or redraw
            if (anyCropsDefined) {
                PipelineDialog cropReuseDlg = new PipelineDialog("CHRONOS — Broad Crop Regions");
                cropReuseDlg.addHeader("Existing Broad Crop Regions Found");
                cropReuseDlg.addMessage("Saved broad crop regions were found from a previous run.");
                for (Map.Entry<String, Rectangle> entry : perFileCrops.entrySet()) {
                    Rectangle r = entry.getValue();
                    cropReuseDlg.addMessage("  " + entry.getKey() + ": "
                            + r.width + "x" + r.height + " at (" + r.x + "," + r.y + ")");
                }
                cropReuseDlg.addSpacer(4);
                cropReuseDlg.addToggle("Use existing broad crop regions", true);

                if (cropReuseDlg.showDialog()) {
                    boolean reuse = cropReuseDlg.getNextBoolean();
                    if (!reuse) {
                        perFileCrops.clear();
                        allCropsDefined = false;
                        IJ.log("  User chose to redraw broad crop regions.");
                    } else {
                        IJ.log("  Using saved broad crop regions (" + perFileCrops.size() + " files).");
                    }
                }
            }
            needCropInteraction = !allCropsDefined;
        }

        // --- Check existing alignment angles ---
        if (config.alignEnabled) {
            boolean anyAnglesDefined2 = false;
            boolean allAnglesDefined2 = true;
            for (String tf : tifFiles) {
                String base = stripExtension(tf);
                if (perFileAngles.containsKey(base)) anyAnglesDefined2 = true;
                else allAnglesDefined2 = false;
            }
            if (anyAnglesDefined2) {
                PipelineDialog reuseDlg2 = new PipelineDialog("CHRONOS — Alignment");
                reuseDlg2.addHeader("Existing Alignment Angles Found");
                reuseDlg2.addMessage("Saved alignment angles were found from a previous run.");
                for (Map.Entry<String, Double> entry : perFileAngles.entrySet()) {
                    reuseDlg2.addMessage("  " + entry.getKey() + ": "
                            + String.format("%.1f", entry.getValue()) + "\u00B0");
                }
                reuseDlg2.addSpacer(4);
                reuseDlg2.addToggle("Use existing alignment angles", true);
                if (reuseDlg2.showDialog() && !reuseDlg2.getNextBoolean()) {
                    perFileAngles.clear();
                    allAnglesDefined2 = false;
                    IJ.log("  User chose to redraw alignment lines.");
                }
            }
            needAlignInteraction = !allAnglesDefined2;
        }

        // --- Check existing tight crop regions ---
        if (config.tightCropEnabled) {
            boolean anyTightCropsDefined = false;
            boolean allTightCropsDefined = true;
            for (String tf : tifFiles) {
                String base = stripExtension(tf);
                if (perFileTightCrops.containsKey(base)) {
                    anyTightCropsDefined = true;
                } else {
                    allTightCropsDefined = false;
                }
            }

            if (anyTightCropsDefined) {
                PipelineDialog tightReuseDlg = new PipelineDialog("CHRONOS — Tight Crop Regions");
                tightReuseDlg.addHeader("Existing Tight Crop Regions Found");
                tightReuseDlg.addMessage("Saved tight crop regions were found from a previous run.");
                for (Map.Entry<String, Rectangle> entry : perFileTightCrops.entrySet()) {
                    Rectangle r = entry.getValue();
                    tightReuseDlg.addMessage("  " + entry.getKey() + ": "
                            + r.width + "x" + r.height + " at (" + r.x + "," + r.y + ")");
                }
                tightReuseDlg.addSpacer(4);
                tightReuseDlg.addToggle("Use existing tight crop regions", true);

                if (tightReuseDlg.showDialog()) {
                    boolean reuse = tightReuseDlg.getNextBoolean();
                    if (!reuse) {
                        perFileTightCrops.clear();
                        allTightCropsDefined = false;
                        IJ.log("  User chose to redraw tight crop regions.");
                    } else {
                        IJ.log("  Using saved tight crop regions (" + perFileTightCrops.size() + " files).");
                    }
                }
            }
            needTightCropInteraction = !allTightCropsDefined;
        }

        // --- Combined interactive pass: crop + align per image in one go ---
        if (needCropInteraction || needAlignInteraction) {
            IJ.log("");
            IJ.log("Interactive setup for each image...");

            String[] sortedTifs = tifFiles.clone();
            Arrays.sort(sortedTifs);

            for (int ti = 0; ti < sortedTifs.length; ti++) {
                String tf = sortedTifs[ti];
                String baseName = stripExtension(tf);

                boolean doCrop = needCropInteraction && !perFileCrops.containsKey(baseName);
                boolean doAlign = needAlignInteraction && !perFileAngles.containsKey(baseName);
                if (!doCrop && !doAlign) continue;

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
                IJ.run(proj, "Enhance Contrast", "saturated=0.35");
                imp.close();

                // Align first, then crop on the rotated projection
                if (doAlign) {
                    proj.setTitle("ALIGN [" + (ti + 1) + "/" + sortedTifs.length + "] — " + baseName);
                    proj.show();
                    WaitForUserDialog alignWait = new WaitForUserDialog(
                            "CHRONOS — Align [" + (ti + 1) + "/" + sortedTifs.length + "]",
                            "Image: " + baseName + "\n\n" +
                            "Draw a LINE through the midline of the slice\n" +
                            "(the axis you want to be vertical),\n" +
                            "then press OK.\n\n" +
                            "Press ESC to skip alignment for this image.");
                    alignWait.show();
                    if (!alignWait.escPressed()) {
                        Roi lineRoi = proj.getRoi();
                        if (lineRoi != null && lineRoi.isLine()) {
                            java.awt.geom.Line2D.Double line = getLineCoords(lineRoi);
                            double dx = line.x2 - line.x1;
                            double dy = line.y2 - line.y1;
                            double angleDeg = Math.toDegrees(Math.atan2(dx, dy));
                            // Normalize to [-90, 90] — line direction shouldn't matter
                            if (angleDeg > 90) angleDeg -= 180;
                            if (angleDeg < -90) angleDeg += 180;
                            perFileAngles.put(baseName, angleDeg);
                            IJ.log("  [" + (ti + 1) + "/" + sortedTifs.length + "] "
                                    + baseName + ": rotation = " + String.format("%.1f", angleDeg) + "°");

                            // Rotate the projection so user can crop on the aligned image
                            if (doCrop && Math.abs(angleDeg) > 0.1) {
                                proj.deleteRoi();
                                ImageProcessor projIp = proj.getProcessor();
                                projIp.setInterpolationMethod(ImageProcessor.BILINEAR);
                                projIp.setBackgroundValue(0);
                                // Enlarge canvas to fit rotated image
                                double rad = Math.toRadians(Math.abs(angleDeg));
                                int newW = (int) Math.ceil(proj.getWidth() * Math.abs(Math.cos(rad))
                                        + proj.getHeight() * Math.abs(Math.sin(rad)));
                                int newH = (int) Math.ceil(proj.getWidth() * Math.abs(Math.sin(rad))
                                        + proj.getHeight() * Math.abs(Math.cos(rad)));
                                ImageProcessor enlarged = projIp.createProcessor(newW, newH);
                                enlarged.insert(projIp, (newW - proj.getWidth()) / 2,
                                        (newH - proj.getHeight()) / 2);
                                enlarged.setInterpolationMethod(ImageProcessor.BILINEAR);
                                enlarged.setBackgroundValue(0);
                                enlarged.rotate(-angleDeg);
                                proj.setProcessor(enlarged);
                            }
                        }
                    }
                    proj.deleteRoi();
                }

                if (doCrop) {
                    proj.setTitle("BROAD CROP [" + (ti + 1) + "/" + sortedTifs.length + "] — " + baseName);
                    proj.show();
                    WaitForUserDialog cropWait = new WaitForUserDialog(
                            "CHRONOS — Broad Crop [" + (ti + 1) + "/" + sortedTifs.length + "]",
                            "Image: " + baseName + "\n\n" +
                            "Draw a LOOSE rectangle around the sample.\n" +
                            "This does NOT need to be tight — just exclude\n" +
                            "empty well space to speed up registration.\n" +
                            "A precise crop will be offered after registration.\n\n" +
                            "Press ESC to skip cropping for this image.");
                    cropWait.show();
                    if (!cropWait.escPressed()) {
                        Roi cropRoi = proj.getRoi();
                        if (cropRoi != null && cropRoi.getType() == Roi.RECTANGLE) {
                            Rectangle r = cropRoi.getBounds();
                            perFileCrops.put(baseName, r);
                            IJ.log("  [" + (ti + 1) + "/" + sortedTifs.length + "] "
                                    + baseName + ": broad crop " + r.width + "x" + r.height
                                    + " at (" + r.x + "," + r.y + ")");
                        }
                    }
                    proj.deleteRoi();
                }

                proj.close();
            }

            if (!perFileCrops.isEmpty()) saveCropRegions(cropRegionsPath, perFileCrops);
            if (!perFileAngles.isEmpty()) saveAlignmentAngles(alignAnglesPath, perFileAngles);
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

            // Step 0a: Alignment rotation (before crop so crop is on aligned image)
            String baseName0 = fileName;
            int dotIdx0 = baseName0.lastIndexOf('.');
            if (dotIdx0 > 0) baseName0 = baseName0.substring(0, dotIdx0);
            Double fileAngle = perFileAngles.get(baseName0);
            if (config.alignEnabled && fileAngle != null && Math.abs(fileAngle) > 0.1) {
                IJ.log("  Step 0a: Align (rotate " + String.format("%.1f", fileAngle) + "°)");
                // Enlarge canvas to fit rotated image without clipping
                double rad = Math.toRadians(Math.abs(fileAngle));
                int origW = imp.getWidth();
                int origH = imp.getHeight();
                int newW = (int) Math.ceil(origW * Math.abs(Math.cos(rad))
                        + origH * Math.abs(Math.sin(rad)));
                int newH = (int) Math.ceil(origW * Math.abs(Math.sin(rad))
                        + origH * Math.abs(Math.cos(rad)));
                int padX = (newW - origW) / 2;
                int padY = (newH - origH) / 2;

                ImageStack rotatedStack = new ImageStack(newW, newH);
                ImageStack srcStack2 = imp.getStack();
                for (int s = 1; s <= srcStack2.getSize(); s++) {
                    ImageProcessor ip = srcStack2.getProcessor(s);
                    ImageProcessor enlarged = ip.createProcessor(newW, newH);
                    enlarged.insert(ip, padX, padY);
                    enlarged.setInterpolationMethod(ImageProcessor.BILINEAR);
                    enlarged.setBackgroundValue(0);
                    enlarged.rotate(-fileAngle);
                    rotatedStack.addSlice(srcStack2.getSliceLabel(s), enlarged);
                }
                ImagePlus rotated = new ImagePlus(imp.getTitle(), rotatedStack);
                rotated.setCalibration(imp.getCalibration().copy());
                imp.close();
                imp = rotated;
            } else if (config.alignEnabled) {
                IJ.log("  Step 0a: Align — no angle defined for this file, skipping");
            } else {
                IJ.log("  Step 0a: Align — skipped");
            }

            // Step 0b: Broad Crop (per-file crop regions, drawn on aligned image)
            Rectangle fileCrop = perFileCrops.get(baseName0);

            if (config.cropEnabled && fileCrop != null) {
                IJ.log("  Step 0b: Broad crop (" + fileCrop.width + "x" + fileCrop.height
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
                IJ.log("  Step 0b: Broad crop — no crop region defined for this file, skipping");
            } else {
                IJ.log("  Step 0b: Broad crop — skipped");
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
                String baseName2 = baseName0;

                // Step 2a: Check for cached transforms
                String transformsPath = correctedDir + "registration_transforms_" + baseName2 + ".csv";
                RegistrationResult cachedResult = null;
                if (config.motionCorrectionCacheEnabled) {
                    cachedResult = loadRegistrationTransforms(transformsPath, imp.getStackSize());
                }

                if (cachedResult != null) {
                    // Prompt user whether to reuse
                    boolean reuseTransforms = true;
                    PipelineDialog reuseDlg = new PipelineDialog("CHRONOS — Registration Cache");
                    reuseDlg.addHeader("Cached Registration Transforms Found");
                    reuseDlg.addMessage("Found saved transforms for <b>" + baseName2 + "</b> ("
                            + cachedResult.nFrames + " frames, method: " + cachedResult.method + ")");
                    reuseDlg.addMessage("Max shift: " + IJ.d2s(cachedResult.maxShift, 2) + " px");
                    reuseDlg.addSpacer(4);
                    reuseDlg.addToggle("Reuse cached transforms", true);
                    if (reuseDlg.showDialog()) {
                        reuseTransforms = reuseDlg.getNextBoolean();
                    }

                    if (reuseTransforms) {
                        IJ.log("  Step 2: Motion correction — reusing cached transforms ("
                                + cachedResult.method + ")");
                        ImagePlus corrected = MotionCorrector.applyRegistration(imp, cachedResult);
                        if (corrected != imp) {
                            imp.close();
                            imp = corrected;
                        }
                        // Skip to saving outputs (already have result)
                        saveDriftTrace(correctedDir, baseName2, cachedResult, config.frameIntervalMin);
                        generateDriftPlot(directory, baseName2, cachedResult, config.frameIntervalMin, null);
                    } else {
                        cachedResult = null; // Force re-computation
                    }
                }

                if (cachedResult == null) {
                    // Step 2b: Automatic drift analysis
                    IJ.log("  Step 2b: Drift analysis...");
                    DriftAnalysisResult driftResult = DriftAnalyzer.analyze(imp, config.frameIntervalMin);

                    // Save drift analysis
                    saveDriftAnalysis(correctedDir, baseName2, driftResult);

                    // Step 2c: Dispatch to method
                    String method = config.motionCorrectionMethod;
                    if ("Automatic".equalsIgnoreCase(method)) {
                        method = driftResult.recommendedMethod;
                        IJ.log("  Step 2c: Automatic -> " + method);
                    }

                    RegistrationResult regResult = null;
                    boolean pluginApplied = false;

                    if ("SIFT".equalsIgnoreCase(method)) {
                        IJ.log("  Step 2c: Motion correction (SIFT)");
                        ImagePlus corrected = MotionCorrector.correctWithSIFT(imp);
                        if (corrected != imp) {
                            imp.close();
                            imp = corrected;
                        }
                        // SIFT applies directly; create a dummy result for logging
                        double[] zeros = new double[imp.getStackSize()];
                        double[] ones = new double[imp.getStackSize()];
                        Arrays.fill(ones, 1.0);
                        regResult = new RegistrationResult(zeros, zeros, ones, "SIFT", "plugin");
                        pluginApplied = true;
                    } else if ("Cross-Correlation".equalsIgnoreCase(method)) {
                        IJ.log("  Step 2c: Motion correction (Cross-Correlation, ref="
                                + config.motionCorrectionReference + ")");
                        ImagePlus corrected = MotionCorrector.correct(imp, config.motionCorrectionReference);
                        if (corrected != imp) {
                            imp.close();
                            imp = corrected;
                        }
                        double[] zeros = new double[imp.getStackSize()];
                        double[] ones = new double[imp.getStackSize()];
                        Arrays.fill(ones, 1.0);
                        regResult = new RegistrationResult(zeros, zeros, ones, "Cross-Correlation",
                                config.motionCorrectionReference);
                        pluginApplied = true;
                    } else if ("Descriptor-Based".equalsIgnoreCase(method)) {
                        IJ.log("  Step 2c: Motion correction (Descriptor-Based)");
                        regResult = MotionCorrector.computeDescriptorBased(imp);
                        pluginApplied = true;
                    } else if ("Correct 3D Drift".equalsIgnoreCase(method)) {
                        IJ.log("  Step 2c: Motion correction (Correct 3D Drift)");
                        RegistrationResult driftResult3D = MotionCorrector.correctWith3DDrift(imp);
                        if (driftResult3D != null) {
                            ImagePlus corrected = MotionCorrector.applyRegistration(imp, driftResult3D);
                            if (corrected != imp) {
                                imp.close();
                                imp = corrected;
                            }
                            regResult = driftResult3D;
                        }
                        pluginApplied = true;
                    } else if ("Correct 3D Drift (Manual Landmarks)".equalsIgnoreCase(method)) {
                        IJ.log("  Step 2c: Motion correction (Correct 3D Drift — Manual Landmarks)");
                        // Show first frame for user to draw ROI around stable landmarks
                        ImagePlus roiProj;
                        if (imp.getStackSize() > 1) {
                            ZProjector zp2 = new ZProjector(imp);
                            zp2.setMethod(ZProjector.AVG_METHOD);
                            zp2.doProjection();
                            roiProj = zp2.getProjection();
                        } else {
                            roiProj = imp.duplicate();
                        }
                        IJ.run(roiProj, "Enhance Contrast", "saturated=0.35");
                        roiProj.setTitle("SELECT LANDMARKS — " + baseName0);
                        roiProj.show();
                        WaitForUserDialog roiWait = new WaitForUserDialog(
                                "CHRONOS — Select Tracking Landmarks",
                                "Draw a rectangle around STABLE features\n"
                                + "(scratch marks, tissue edges, well boundary).\n\n"
                                + "Avoid areas with moving cells.\n"
                                + "The drift will be computed from this region only.\n\n"
                                + "Press OK when done.");
                        roiWait.show();
                        Rectangle landmarkRoi = null;
                        if (!roiWait.escPressed()) {
                            Roi drawnRoi = roiProj.getRoi();
                            if (drawnRoi != null && drawnRoi.getType() == Roi.RECTANGLE) {
                                landmarkRoi = drawnRoi.getBounds();
                                IJ.log("    Landmark ROI: " + landmarkRoi.width + "x" + landmarkRoi.height
                                        + " at (" + landmarkRoi.x + "," + landmarkRoi.y + ")");
                            }
                        }
                        roiProj.close();

                        if (landmarkRoi != null) {
                            RegistrationResult driftResultManual =
                                    MotionCorrector.correctWith3DDriftManual(imp, landmarkRoi);
                            if (driftResultManual != null) {
                                ImagePlus corrected = MotionCorrector.applyRegistration(imp, driftResultManual);
                                if (corrected != imp) {
                                    imp.close();
                                    imp = corrected;
                                }
                                regResult = driftResultManual;
                            }
                        } else {
                            IJ.log("    No ROI drawn — falling back to automatic Correct 3D Drift");
                            RegistrationResult driftResult3DFb = MotionCorrector.correctWith3DDrift(imp);
                            if (driftResult3DFb != null) {
                                ImagePlus corrected = MotionCorrector.applyRegistration(imp, driftResult3DFb);
                                if (corrected != imp) {
                                    imp.close();
                                    imp = corrected;
                                }
                                regResult = driftResult3DFb;
                            }
                        }
                        pluginApplied = true;
                    } else if ("Phase Correlation".equalsIgnoreCase(method)) {
                        IJ.log("  Step 2c: Motion correction (Phase Correlation, ref="
                                + config.motionCorrectionReference + ")");
                        regResult = MotionCorrector.computePhaseCorrelation(imp,
                                config.motionCorrectionReference);
                    } else if ("Phase Correlation + Epoch Detection".equalsIgnoreCase(method)) {
                        IJ.log("  Step 2c: Motion correction (Phase Correlation + Epoch Detection)");
                        regResult = MotionCorrector.computeEpochRegistration(imp,
                                config.motionCorrectionReference, driftResult.transitionFrames);
                    } else if ("Anchor-Patch Tracking".equalsIgnoreCase(method)) {
                        IJ.log("  Step 2c: Motion correction (Anchor-Patch Tracking)");
                        regResult = AnchorPatchTracker.track(imp, config.motionCorrectionReference);
                    } else {
                        IJ.log("  Step 2c: Motion correction (Phase Correlation — fallback)");
                        regResult = MotionCorrector.computePhaseCorrelation(imp,
                                config.motionCorrectionReference);
                    }

                    // Step 2d: Apply registration + save outputs
                    if (regResult != null && !pluginApplied) {
                        IJ.log("  Step 2d: Applying registration (max shift: "
                                + IJ.d2s(regResult.maxShift, 2) + " px)");
                        ImagePlus corrected = MotionCorrector.applyRegistration(imp, regResult);
                        if (corrected != imp) {
                            imp.close();
                            imp = corrected;
                        }
                    }

                    if (regResult != null) {
                        // Save registration transforms for caching
                        saveRegistrationTransforms(transformsPath, regResult);
                        // Save drift trace CSV
                        saveDriftTrace(correctedDir, baseName2, regResult, config.frameIntervalMin);
                        // Generate drift trace plot
                        generateDriftPlot(directory, baseName2, regResult, config.frameIntervalMin,
                                driftResult);
                    }
                }
            } else {
                IJ.log("  Step 2: Motion correction — skipped");
            }

            // Step 2e: Tight Crop (on registered/stabilized image)
            if (config.tightCropEnabled) {
                Rectangle tightCrop = perFileTightCrops.get(baseName0);

                if (tightCrop == null && needTightCropInteraction) {
                    // Interactive tight crop on the registered stack
                    IJ.log("  Step 2e: Tight crop — drawing on registered image...");
                    ImagePlus tightProj;
                    if (imp.getStackSize() > 1) {
                        ZProjector zp = new ZProjector(imp);
                        zp.setMethod(ZProjector.AVG_METHOD);
                        zp.doProjection();
                        tightProj = zp.getProjection();
                    } else {
                        tightProj = imp.duplicate();
                    }
                    IJ.run(tightProj, "Enhance Contrast", "saturated=0.35");

                    tightProj.setTitle("TIGHT CROP (registered) — " + baseName0);
                    tightProj.show();
                    WaitForUserDialog tightWait = new WaitForUserDialog(
                            "CHRONOS — Tight Crop (after registration)",
                            "Image: " + baseName0 + "\n\n"
                            + "The sample is now motion-corrected.\n"
                            + "Draw a TIGHT rectangle around the sample,\n"
                            + "then press OK.\n\n"
                            + "Press ESC to skip tight cropping for this image.");
                    tightWait.show();
                    if (!tightWait.escPressed()) {
                        Roi tightRoi = tightProj.getRoi();
                        if (tightRoi != null && tightRoi.getType() == Roi.RECTANGLE) {
                            tightCrop = tightRoi.getBounds();
                            perFileTightCrops.put(baseName0, tightCrop);
                            IJ.log("  [" + baseName0 + "] tight crop "
                                    + tightCrop.width + "x" + tightCrop.height
                                    + " at (" + tightCrop.x + "," + tightCrop.y + ")");
                        }
                    }
                    tightProj.close();
                }

                if (tightCrop != null) {
                    IJ.log("  Step 2e: Tight crop (" + tightCrop.width + "x" + tightCrop.height
                            + " at " + tightCrop.x + "," + tightCrop.y + ")");
                    ImageStack tightStack = new ImageStack(tightCrop.width, tightCrop.height);
                    ImageStack srcTight = imp.getStack();
                    for (int s = 1; s <= srcTight.getSize(); s++) {
                        ImageProcessor slice = srcTight.getProcessor(s);
                        slice.setRoi(tightCrop.x, tightCrop.y, tightCrop.width, tightCrop.height);
                        tightStack.addSlice(srcTight.getSliceLabel(s), slice.crop());
                    }
                    ImagePlus tightCropped = new ImagePlus(imp.getTitle(), tightStack);
                    tightCropped.setCalibration(imp.getCalibration().copy());
                    imp.close();
                    imp = tightCropped;
                } else {
                    IJ.log("  Step 2e: Tight crop — no crop region defined, skipping");
                }
            } else {
                IJ.log("  Step 2e: Tight crop — skipped");
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

            // Step 7: Pre-ROI Filter
            if (!"None".equalsIgnoreCase(config.preRoiFilter)) {
                IJ.log("  Step 7: Pre-ROI filter (" + config.preRoiFilter + ")");
                String filterMacro = loadFilterPreset(config.preRoiFilter);
                if (filterMacro != null) {
                    // Need to show image for macro execution
                    boolean wasVisible = imp.getWindow() != null;
                    if (!wasVisible) imp.show();

                    IJ.runMacro(filterMacro);

                    // The macro may produce a new image — find it
                    ImagePlus result = WindowManager.getCurrentImage();
                    if (result != null && result != imp) {
                        result.setCalibration(imp.getCalibration().copy());
                        imp.close();
                        imp = result;
                    } else if (!wasVisible && imp.getWindow() != null) {
                        imp.hide();
                    }
                } else {
                    IJ.log("    WARNING: Filter preset not found: " + config.preRoiFilter);
                }
            } else {
                IJ.log("  Step 7: Pre-ROI filter — skipped");
            }

            // Step 8: Apply LUT
            if (!"None".equalsIgnoreCase(config.lutName)) {
                IJ.log("  Step 8: Applying LUT (" + config.lutName + ")");
                IJ.run(imp, config.lutName, "");
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

        // Save tight crop regions if any were drawn during processing
        if (!perFileTightCrops.isEmpty()) {
            saveCropRegions(tightCropRegionsPath, perFileTightCrops);
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

        dlg.addHeader("Recording");
        dlg.addMessage("Reporter: <b>" + config.reporterType + "</b>  |  Interval: <b>"
                + config.frameIntervalMin + "</b> min");
        dlg.addHelpText("Change these in the main Settings dialog.");

        // --- Broad Crop ---
        dlg.addSpacer(4);
        dlg.addHeader("Broad Crop (before registration)");
        dlg.addToggle("Broad crop to reduce image size", config.cropEnabled);
        dlg.addHelpText("Draw a loose rectangle around the sample to exclude empty well space. Applied before registration to speed it up. Does not need to be tight — the sample may shift within this region.");

        // --- Tight Crop ---
        dlg.addSpacer(4);
        dlg.addHeader("Tight Crop (after registration)");
        dlg.addToggle("Tight crop on stabilized image", config.tightCropEnabled);
        dlg.addHelpText("Draw a precise rectangle after motion correction, when the sample is stable. This produces the final tightly-cropped output.");

        // --- Alignment ---
        dlg.addSpacer(4);
        dlg.addHeader("Slice Alignment");
        dlg.addToggle("Align slice orientation", config.alignEnabled);
        dlg.addHelpText("Draw a line through the midline of each slice. The pipeline rotates the image so the line is vertical, ensuring consistent anatomical orientation across all recordings.");

        // --- Frame Binning ---
        dlg.addSpacer(4);
        dlg.addHeader("Frame Binning");
        ToggleSwitch binToggle = dlg.addToggle("Enable", config.binningEnabled);
        dlg.addHelpText("Reduces temporal resolution by averaging or summing groups of consecutive frames. Useful for noisy recordings — increases SNR at the cost of time resolution.");
        final JTextField binFactorField = dlg.addNumericField("Bin Factor", config.binFactor, 0);
        String[] binMethods = {"Mean", "Sum"};
        final JComboBox<String> binMethodCombo = dlg.addChoice("Method", binMethods, config.binMethod);
        // Enable/disable params based on toggle
        binFactorField.setEnabled(config.binningEnabled);
        binMethodCombo.setEnabled(config.binningEnabled);
        binToggle.addChangeListener(new Runnable() {
            public void run() {
                boolean on = binToggle.isSelected();
                binFactorField.setEnabled(on);
                binMethodCombo.setEnabled(on);
            }
        });

        // --- Motion Correction ---
        dlg.addSpacer(4);
        dlg.addHeader("Motion Correction");
        ToggleSwitch mcToggle = dlg.addToggle("Enable", config.motionCorrectionEnabled);
        final JLabel mcHelpLabel = dlg.addHelpText(getMethodHelpText(config.motionCorrectionMethod));
        String[] mcMethods = {"Automatic", "Phase Correlation", "Phase Correlation + Epoch Detection",
                "Anchor-Patch Tracking", "Cross-Correlation", "SIFT", "Descriptor-Based",
                "Correct 3D Drift", "Correct 3D Drift (Manual Landmarks)"};
        final JComboBox<String> mcMethodCombo = dlg.addChoice("Method", mcMethods, config.motionCorrectionMethod);
        String[] refMethods = {"Mean Projection", "Median Projection", "First Frame"};
        String currentRef = refToDisplay(config.motionCorrectionReference);
        final JComboBox<String> mcRefCombo = dlg.addChoice("Reference", refMethods, currentRef);
        ToggleSwitch mcCacheToggle = dlg.addToggle("Cache transforms for reuse", config.motionCorrectionCacheEnabled);
        JButton mcHelpBtn = dlg.addButton("Help: Which method?");
        mcHelpBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                openDecisionTreeSVG();
            }
        });
        // Update help text when method changes
        mcMethodCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String method = (String) mcMethodCombo.getSelectedItem();
                mcHelpLabel.setText("<html><body style='width:280px;'>"
                        + getMethodHelpText(method) + "</body></html>");
            }
        });
        mcMethodCombo.setEnabled(config.motionCorrectionEnabled);
        mcRefCombo.setEnabled(config.motionCorrectionEnabled);
        mcToggle.addChangeListener(new Runnable() {
            public void run() {
                boolean on = mcToggle.isSelected();
                mcMethodCombo.setEnabled(on);
                mcRefCombo.setEnabled(on);
            }
        });

        // --- Background Subtraction ---
        dlg.addSpacer(4);
        dlg.addHeader("Background Subtraction");
        boolean bgEnabled = !"None".equalsIgnoreCase(config.backgroundMethod);
        ToggleSwitch bgToggle = dlg.addToggle("Enable", bgEnabled);
        dlg.addHelpText("Removes non-uniform background illumination. Rolling Ball estimates background as a ball rolling under the intensity surface. Minimum Projection subtracts the minimum value at each pixel across all frames.");
        String[] bgMethods = {"Rolling Ball", "Minimum Projection"};
        String bgDefault = bgEnabled ? config.backgroundMethod : "Rolling Ball";
        final JComboBox<String> bgMethodCombo = dlg.addChoice("Method", bgMethods, bgDefault);
        final JTextField bgRadiusField = dlg.addNumericField("Radius (pixels)", config.backgroundRadius, 0);
        bgMethodCombo.setEnabled(bgEnabled);
        bgRadiusField.setEnabled(bgEnabled);
        bgToggle.addChangeListener(new Runnable() {
            public void run() {
                boolean on = bgToggle.isSelected();
                bgMethodCombo.setEnabled(on);
                bgRadiusField.setEnabled(on);
            }
        });

        // --- Bleach / Decay Correction ---
        dlg.addSpacer(4);
        dlg.addHeader("Bleach / Decay Correction");
        boolean bleachEnabled = !"None".equalsIgnoreCase(config.bleachMethod);
        ToggleSwitch bleachToggle = dlg.addToggle("Enable", bleachEnabled);
        dlg.addHelpText("Corrects for signal decay over time. Auto-selected based on reporter type: Sliding Percentile for bioluminescence (luciferin depletion), Bi-exponential for fluorescent/calcium reporters (photobleaching).");
        String[] bleachMethods = {"Mono-exponential", "Bi-exponential",
                "Sliding Percentile", "Simple Ratio"};
        String bleachDefault = bleachEnabled ? config.bleachMethod : "Bi-exponential";
        final JComboBox<String> bleachCombo = dlg.addChoice("Method", bleachMethods, bleachDefault);
        final JTextField bleachWindowField = dlg.addNumericField("Percentile Window (frames)", config.bleachPercentileWindow, 0);
        final JTextField bleachPctField = dlg.addNumericField("Percentile (%)", config.bleachPercentile, 1);
        bleachCombo.setEnabled(bleachEnabled);
        bleachWindowField.setEnabled(bleachEnabled);
        bleachPctField.setEnabled(bleachEnabled);
        bleachToggle.addChangeListener(new Runnable() {
            public void run() {
                boolean on = bleachToggle.isSelected();
                bleachCombo.setEnabled(on);
                bleachWindowField.setEnabled(on);
                bleachPctField.setEnabled(on);
            }
        });

        // Reporter type is now in global Settings — bleach default set via applyReporterDefaults()

        // --- Spatial Filter ---
        dlg.addSpacer(4);
        dlg.addHeader("Spatial Filter");
        boolean spatialEnabled = !"None".equalsIgnoreCase(config.spatialFilterType);
        ToggleSwitch spatialToggle = dlg.addToggle("Enable", spatialEnabled);
        dlg.addHelpText("Smooths each frame to reduce pixel noise. Gaussian preserves edges better at low sigma. Median is better for salt-and-pepper noise but can blur fine features.");
        String[] spatialTypes = {"Gaussian", "Median"};
        String spatialDefault = spatialEnabled ? config.spatialFilterType : "Gaussian";
        final JComboBox<String> spatialCombo = dlg.addChoice("Type", spatialTypes, spatialDefault);
        final JTextField spatialRadiusField = dlg.addNumericField("Sigma / Radius (pixels)", config.spatialFilterRadius, 1);
        spatialCombo.setEnabled(spatialEnabled);
        spatialRadiusField.setEnabled(spatialEnabled);
        spatialToggle.addChangeListener(new Runnable() {
            public void run() {
                boolean on = spatialToggle.isSelected();
                spatialCombo.setEnabled(on);
                spatialRadiusField.setEnabled(on);
            }
        });

        // --- Temporal Filter ---
        dlg.addSpacer(4);
        dlg.addHeader("Temporal Filter");
        boolean temporalEnabled = !"None".equalsIgnoreCase(config.temporalFilterType);
        ToggleSwitch temporalToggle = dlg.addToggle("Enable", temporalEnabled);
        dlg.addHelpText("Smooths intensity across time at each pixel. Reduces frame-to-frame noise while preserving slower circadian oscillations. Keep window small relative to period.");
        String[] temporalTypes = {"Moving Average", "Savitzky-Golay"};
        String temporalDefault = temporalEnabled ? config.temporalFilterType : "Moving Average";
        final JComboBox<String> temporalCombo = dlg.addChoice("Type", temporalTypes, temporalDefault);
        final JTextField temporalWindowField = dlg.addNumericField("Window (frames)", config.temporalFilterWindow, 0);
        temporalCombo.setEnabled(temporalEnabled);
        temporalWindowField.setEnabled(temporalEnabled);
        temporalToggle.addChangeListener(new Runnable() {
            public void run() {
                boolean on = temporalToggle.isSelected();
                temporalCombo.setEnabled(on);
                temporalWindowField.setEnabled(on);
            }
        });

        // --- Pre-ROI Filter ---
        dlg.addSpacer(4);
        dlg.addHeader("Pre-ROI Filter");
        String[] filterPresets = {"None", "Extract Green (Incucyte GFP)"};
        final JComboBox<String> filterCombo = dlg.addChoice("Preset", filterPresets, config.preRoiFilter);
        final JLabel filterHelp = dlg.addHelpText(getFilterHelpText(config.preRoiFilter));
        filterCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                filterHelp.setText("<html><body style='width:280px;'>"
                        + getFilterHelpText((String) filterCombo.getSelectedItem())
                        + "</body></html>");
            }
        });

        // --- LUT ---
        dlg.addSpacer(4);
        dlg.addHeader("Output LUT");
        String[] lutOptions = {"None", "Green", "Fire", "Cyan Hot", "Grays", "Magenta", "Red", "Blue"};
        dlg.addChoice("LUT", lutOptions, config.lutName);
        dlg.addHelpText("Applies a lookup table to the output. Display only — does not modify pixel values.");

        // Show dialog
        if (!dlg.showDialog()) {
            return false;
        }

        // Read values back into config
        // (Reporter Type and Frame Interval are now in the global Settings dialog)

        config.cropEnabled = dlg.getNextBoolean();           // Broad Crop Enable
        config.tightCropEnabled = dlg.getNextBoolean();     // Tight Crop Enable
        config.alignEnabled = dlg.getNextBoolean();          // Align Enable

        config.binningEnabled = dlg.getNextBoolean();      // Binning Enable
        config.binFactor = Math.max(1, (int) dlg.getNextNumber()); // Bin Factor
        config.binMethod = dlg.getNextChoice();            // Bin Method

        config.motionCorrectionEnabled = dlg.getNextBoolean(); // Motion Enable
        config.motionCorrectionMethod = dlg.getNextChoice();  // Motion Method
        config.motionCorrectionReference = displayToRef(dlg.getNextChoice()); // Reference
        config.motionCorrectionCacheEnabled = dlg.getNextBoolean(); // Cache Enable

        boolean bgOn = dlg.getNextBoolean();               // BG Enable
        config.backgroundMethod = bgOn ? dlg.getNextChoice() : "None"; // BG Method
        config.backgroundRadius = dlg.getNextNumber();     // BG Radius

        boolean bleachOn = dlg.getNextBoolean();           // Bleach Enable
        config.bleachMethod = bleachOn ? dlg.getNextChoice() : "None"; // Bleach Method
        config.bleachPercentileWindow = Math.max(1, (int) dlg.getNextNumber());
        config.bleachPercentile = dlg.getNextNumber();

        boolean spatialOn = dlg.getNextBoolean();          // Spatial Enable
        config.spatialFilterType = spatialOn ? dlg.getNextChoice() : "None";
        config.spatialFilterRadius = dlg.getNextNumber();

        boolean temporalOn = dlg.getNextBoolean();         // Temporal Enable
        config.temporalFilterType = temporalOn ? dlg.getNextChoice() : "None";
        config.temporalFilterWindow = Math.max(1, (int) dlg.getNextNumber());

        config.preRoiFilter = dlg.getNextChoice();            // Pre-ROI Filter
        config.lutName = dlg.getNextChoice();                  // LUT

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

    /**
     * Saves auto-detected frame intervals from assembled stack calibration data.
     */
    private void saveFrameIntervalsFromAssembled(String directory, String assembledDir) {
        String intervalsPath = directory + ".circadian" + File.separator + "frame_intervals.txt";
        File aDir = new File(assembledDir);
        String[] stacks = aDir.list(new FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.toLowerCase().endsWith(".tif") || name.toLowerCase().endsWith(".tiff");
            }
        });
        if (stacks == null || stacks.length == 0) return;

        Map<String, Double> intervals = new LinkedHashMap<String, Double>();
        for (String stack : stacks) {
            ImagePlus imp = IJ.openImage(assembledDir + File.separator + stack);
            if (imp != null) {
                double intervalSec = imp.getCalibration().frameInterval;
                if (intervalSec > 0) {
                    String baseName = stripExtension(stack);
                    intervals.put(baseName, intervalSec / 60.0); // convert to minutes
                }
                imp.close();
            }
        }

        if (!intervals.isEmpty()) {
            saveFrameIntervals(intervalsPath, intervals);
            IJ.log("  Frame intervals saved to .circadian/frame_intervals.txt");
        }
    }

    /**
     * Loads per-file frame intervals from frame_intervals.txt.
     * Format: baseName=intervalMinutes
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
            IJ.log("Warning: could not read frame intervals: " + e.getMessage());
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
        }
        return intervals;
    }

    /**
     * Saves per-file frame intervals to frame_intervals.txt.
     */
    private static void saveFrameIntervals(String path, Map<String, Double> intervals) {
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("# CHRONOS frame intervals (minutes)");
            pw.println("# Auto-detected from Incucyte timestamps");
            for (Map.Entry<String, Double> entry : intervals.entrySet()) {
                pw.println(entry.getKey() + "=" + entry.getValue());
            }
        } catch (IOException e) {
            IJ.log("Error saving frame intervals: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Extracts line coordinates from a line ROI.
     */
    private static java.awt.geom.Line2D.Double getLineCoords(Roi lineRoi) {
        java.awt.Rectangle bounds = lineRoi.getBounds();
        java.awt.Polygon poly = lineRoi.getPolygon();
        if (poly != null && poly.npoints >= 2) {
            return new java.awt.geom.Line2D.Double(
                    poly.xpoints[0], poly.ypoints[0],
                    poly.xpoints[1], poly.ypoints[1]);
        }
        // Fallback using bounds
        return new java.awt.geom.Line2D.Double(
                bounds.x, bounds.y,
                bounds.x + bounds.width, bounds.y + bounds.height);
    }

    /**
     * Loads per-file alignment angles from alignment_angles.txt.
     */
    private static Map<String, Double> loadAlignmentAngles(String path) {
        Map<String, Double> angles = new LinkedHashMap<String, Double>();
        File f = new File(path);
        if (!f.exists()) return angles;
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
                    angles.put(key, Double.parseDouble(val));
                } catch (NumberFormatException e) { /* skip */ }
            }
        } catch (IOException e) {
            IJ.log("Warning: could not read alignment angles: " + e.getMessage());
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
        }
        return angles;
    }

    /**
     * Saves per-file alignment angles to alignment_angles.txt.
     */
    private static void saveAlignmentAngles(String path, Map<String, Double> angles) {
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("# CHRONOS per-file alignment angles (degrees)");
            pw.println("# Positive = clockwise rotation needed to make midline vertical");
            for (Map.Entry<String, Double> entry : angles.entrySet()) {
                pw.println(entry.getKey() + "=" + entry.getValue());
            }
        } catch (IOException e) {
            IJ.log("Error saving alignment angles: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Returns help text for the selected motion correction method.
     */
    private static String getMethodHelpText(String method) {
        if ("Automatic".equalsIgnoreCase(method)) {
            return "Analyzes drift pattern automatically and selects the best method. "
                    + "Fast — adds only ~2s for drift analysis.";
        } else if ("Phase Correlation".equalsIgnoreCase(method)) {
            return "FFT-based phase correlation with magnitude normalization. "
                    + "Intensity-invariant, translation-only. Fast (~1s for 335 frames).";
        } else if ("Phase Correlation + Epoch Detection".equalsIgnoreCase(method)) {
            return "Detects discrete drift jumps and applies constant shifts per epoch. "
                    + "Ideal for sparse drift (2-3 jumps per week-long recording).";
        } else if ("Anchor-Patch Tracking".equalsIgnoreCase(method)) {
            return "Tracks 6 high-gradient patches via NCC. Robust median shift estimate. "
                    + "Good for cell-immune reporters where bright cells move.";
        } else if ("Cross-Correlation".equalsIgnoreCase(method)) {
            return "Standard FFT cross-correlation. Translation-only, fast, "
                    + "but sensitive to intensity changes.";
        } else if ("SIFT".equalsIgnoreCase(method)) {
            return "Feature-based alignment (rotation + translation). Robust to intensity changes "
                    + "but slower (1-3 min). Best for chaotic drift or rotation.";
        } else if ("Descriptor-Based".equalsIgnoreCase(method)) {
            return "Fiji's Descriptor-based Series Registration (2D/3D + t). "
                    + "Full O(n^2) matching — slowest but handles complex deformations.";
        } else if ("Correct 3D Drift".equalsIgnoreCase(method)) {
            return "Cross-correlation based. Computes drift on 8-bit greyscale then applies to original. "
                    + "Robust to moving cells — uses whole-image correlation on tissue landmarks "
                    + "(scratch marks, edges). Best for Incucyte data.";
        } else if ("Correct 3D Drift (Manual Landmarks)".equalsIgnoreCase(method)) {
            return "Same as Correct 3D Drift, but you draw a rectangle around stable landmarks "
                    + "(scratch marks, tissue edges, well boundary) to track. Ignores moving cells "
                    + "completely. Best when cells confuse automatic registration.";
        }
        return "Corrects sample drift across frames.";
    }

    /**
     * Opens the registration decision tree SVG in the system browser.
     * Extracts from JAR on first use, caches in .circadian/.
     */
    private void openDecisionTreeSVG() {
        try {
            // Try to find an existing extracted copy
            String svgPath = config_directory + ".circadian" + File.separator
                    + "registration_decision_tree.svg";
            File svgFile = new File(svgPath);

            if (!svgFile.exists()) {
                // Extract from JAR resources
                InputStream is = getClass().getResourceAsStream("/docs/registration_decision_tree.svg");
                if (is != null) {
                    new File(svgFile.getParent()).mkdirs();
                    OutputStream os = new FileOutputStream(svgFile);
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        os.write(buf, 0, len);
                    }
                    os.close();
                    is.close();
                } else {
                    IJ.log("  WARNING: Could not find decision tree SVG in JAR resources.");
                    return;
                }
            }

            if (svgFile.exists() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(svgFile.toURI());
            }
        } catch (Exception e) {
            IJ.log("  Could not open decision tree: " + e.getMessage());
        }
    }

    /**
     * Save drift analysis results to CSV.
     */
    private static void saveDriftAnalysis(String correctedDir, String baseName,
                                          DriftAnalysisResult result) {
        String path = correctedDir + "drift_analysis_" + baseName + ".csv";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("# CHRONOS Drift Analysis");
            pw.println("# Pattern: " + result.driftPattern);
            pw.println("# Transitions: " + result.nTransitions);
            pw.println("# Rotation likely: " + result.rotationLikely);
            pw.println("# Recommended: " + result.recommendedMethod);
            pw.println("# Reason: " + result.recommendationReason);
            pw.println("Frame,DriftX_px,DriftY_px,CorrelationQuality");
            for (int i = 0; i < result.dx.length; i++) {
                pw.println(i + "," + IJ.d2s(result.dx[i], 4) + ","
                        + IJ.d2s(result.dy[i], 4) + ","
                        + IJ.d2s(result.correlationQuality[i], 4));
            }
        } catch (IOException e) {
            IJ.log("  Error saving drift analysis: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Save registration transforms to CSV for caching.
     */
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
        } catch (IOException e) {
            IJ.log("  Error saving registration transforms: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Load registration transforms from CSV cache.
     * Returns null if file doesn't exist or frame count doesn't match.
     */
    private static RegistrationResult loadRegistrationTransforms(String path, int expectedFrames) {
        File f = new File(path);
        if (!f.exists()) return null;

        String method = "unknown";
        String reference = "unknown";
        java.util.List<double[]> rows = new java.util.ArrayList<double[]>();

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("# Method: ")) {
                    method = line.substring(10).trim();
                } else if (line.startsWith("# Reference: ")) {
                    reference = line.substring(13).trim();
                } else if (line.isEmpty() || line.startsWith("#") || line.startsWith("Frame")) {
                    continue;
                } else {
                    String[] parts = line.split(",");
                    if (parts.length >= 4) {
                        try {
                            double sx = Double.parseDouble(parts[1].trim());
                            double sy = Double.parseDouble(parts[2].trim());
                            double q = Double.parseDouble(parts[3].trim());
                            rows.add(new double[]{sx, sy, q});
                        } catch (NumberFormatException e) { /* skip */ }
                    }
                }
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
        }

        if (rows.size() != expectedFrames) {
            IJ.log("  Cached transforms have " + rows.size() + " frames, expected "
                    + expectedFrames + " — ignoring cache.");
            return null;
        }

        double[] sx = new double[rows.size()];
        double[] sy = new double[rows.size()];
        double[] q = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            sx[i] = rows.get(i)[0];
            sy[i] = rows.get(i)[1];
            q[i] = rows.get(i)[2];
        }

        return new RegistrationResult(sx, sy, q, method, reference);
    }

    /**
     * Save drift trace CSV (shift over time).
     */
    private static void saveDriftTrace(String correctedDir, String baseName,
                                       RegistrationResult result, double frameIntervalMin) {
        String path = correctedDir + "drift_trace_" + baseName + ".csv";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("Frame,Time_hours,ShiftX_px,ShiftY_px,Quality");
            for (int i = 0; i < result.nFrames; i++) {
                double timeH = i * frameIntervalMin / 60.0;
                pw.println(i + "," + IJ.d2s(timeH, 4) + ","
                        + IJ.d2s(result.shiftX[i], 4) + ","
                        + IJ.d2s(result.shiftY[i], 4) + ","
                        + IJ.d2s(result.quality[i], 4));
            }
        } catch (IOException e) {
            IJ.log("  Error saving drift trace: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Generate drift trace plot PNG using ij.gui.Plot.
     */
    private static void generateDriftPlot(String directory, String baseName,
                                          RegistrationResult result, double frameIntervalMin,
                                          DriftAnalysisResult driftResult) {
        String vizDir = directory + ".circadian" + File.separator + "visualizations" + File.separator;
        new File(vizDir).mkdirs();

        int n = result.nFrames;
        double[] timeH = new double[n];
        for (int i = 0; i < n; i++) {
            timeH[i] = i * frameIntervalMin / 60.0;
        }

        double maxTime = timeH[n - 1];
        double xMax = Math.ceil(maxTime / 24.0) * 24.0;
        if (xMax < 24) xMax = 24;

        // Find Y range
        double yMin = 0, yMax = 0;
        for (int i = 0; i < n; i++) {
            yMin = Math.min(yMin, Math.min(result.shiftX[i], result.shiftY[i]));
            yMax = Math.max(yMax, Math.max(result.shiftX[i], result.shiftY[i]));
        }
        double yPad = Math.max(1.0, (yMax - yMin) * 0.1);

        // 3x DPI scale (following existing visualization conventions)
        int plotW = 800 * 3;
        int plotH = 400 * 3;

        ij.gui.Plot plot = new ij.gui.Plot("Drift Trace — " + baseName,
                "Time (hours)", "Shift (pixels)", timeH, result.shiftX);
        plot.setSize(plotW, plotH);
        plot.setLimits(0, xMax, yMin - yPad, yMax + yPad);
        plot.setColor(Color.BLUE);
        plot.addPoints(timeH, result.shiftX, ij.gui.Plot.LINE);
        plot.setColor(Color.RED);
        plot.addPoints(timeH, result.shiftY, ij.gui.Plot.LINE);
        plot.setColor(Color.BLUE);
        plot.addLabel(0.02, 0.05, "dx");
        plot.setColor(Color.RED);
        plot.addLabel(0.08, 0.05, "dy");

        // Add vertical dashed lines at transition frames
        if (driftResult != null && driftResult.transitionFrames.length > 0) {
            plot.setColor(new Color(128, 128, 128, 128));
            plot.setLineWidth(1);
            for (int tf : driftResult.transitionFrames) {
                if (tf >= 0 && tf < n) {
                    double tH = tf * frameIntervalMin / 60.0;
                    plot.drawDottedLine(tH, yMin - yPad, tH, yMax + yPad, 4);
                }
            }
        }

        // Add 24h grid lines
        plot.setColor(new Color(200, 200, 200));
        plot.setLineWidth(1);
        for (double g = 24; g < xMax; g += 24) {
            plot.drawDottedLine(g, yMin - yPad, g, yMax + yPad, 2);
        }

        // Save as PNG
        String plotPath = vizDir + "drift_trace_" + baseName + ".png";
        ImagePlus plotImp = plot.getImagePlus();
        new FileSaver(plotImp).saveAsPng(plotPath);
        plotImp.close();
        IJ.log("  Drift trace plot saved: .circadian/visualizations/drift_trace_" + baseName + ".png");
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

    /** Load a filter preset macro from bundled resources. */
    private static String loadFilterPreset(String presetName) {
        if (presetName == null || "None".equals(presetName)) return null;
        String path = "/named-filters/" + presetName + ".ijm";
        java.io.InputStream is = PreprocessingAnalysis.class.getResourceAsStream(path);
        if (is == null) {
            // Try context classloader
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) is = cl.getResourceAsStream(path.substring(1));
        }
        if (is == null) return null;
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Help text for filter presets. */
    private static String getFilterHelpText(String preset) {
        if ("Extract Green (Incucyte GFP)".equals(preset)) {
            return "Extracts GFP signal from Incucyte RGB via HSB Saturation channel. "
                    + "Removes ventricle glow with double sliding paraboloid (r=50 + r=15), "
                    + "fills HSB conversion holes with median filter.";
        }
        return "No filter applied. The corrected stack is saved as-is.";
    }
}
