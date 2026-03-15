package chronos.preprocessing;

import chronos.Analysis;
import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.ui.PipelineDialog;
import chronos.ui.ToggleSwitch;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.FileSaver;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.io.FilenameFilter;

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

        // Scan for TIF files
        File dir = new File(directory);
        String[] tifFiles = dir.list(new FilenameFilter() {
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

        // Interactive crop: if enabled and not yet defined, show first image for user to draw crop rectangle
        if (config.cropEnabled && config.cropX < 0) {
            String firstPath = directory + tifFiles[0];
            IJ.log("");
            IJ.log("Crop: Loading first image for crop region selection...");
            ImagePlus firstImp = IJ.openImage(firstPath);
            if (firstImp != null) {
                // Show a mean projection so the user can see the sample
                ij.plugin.ZProjector zp = new ij.plugin.ZProjector(firstImp);
                zp.setMethod(ij.plugin.ZProjector.AVG_METHOD);
                zp.doProjection();
                ImagePlus proj = zp.getProjection();
                proj.setTitle("Draw crop rectangle — " + tifFiles[0]);
                // Auto-enhance contrast for visibility
                IJ.run(proj, "Enhance Contrast", "saturated=0.35");
                proj.show();
                firstImp.close();

                WaitForUserDialog cropWait = new WaitForUserDialog(
                        "CHRONOS — Crop Region",
                        "Draw a RECTANGLE around the sample area.\n" +
                        "This crop will be applied to all images.\n\n" +
                        "Use the Rectangle tool, then press OK.\n" +
                        "Press ESC to skip cropping.");
                cropWait.show();

                if (!cropWait.escPressed()) {
                    Roi cropRoi = proj.getRoi();
                    if (cropRoi != null && cropRoi.getType() == Roi.RECTANGLE) {
                        Rectangle r = cropRoi.getBounds();
                        config.cropX = r.x;
                        config.cropY = r.y;
                        config.cropWidth = r.width;
                        config.cropHeight = r.height;
                        IJ.log("  Crop region set: " + r.width + "x" + r.height + " at (" + r.x + "," + r.y + ")");
                        SessionConfigIO.writeToDirectory(directory, config);
                    } else {
                        IJ.log("  No rectangle ROI drawn. Skipping crop.");
                        config.cropEnabled = false;
                    }
                } else {
                    IJ.log("  Crop skipped by user.");
                    config.cropEnabled = false;
                }
                proj.close();
            }
        } else if (config.cropEnabled && config.cropX >= 0) {
            IJ.log("  Using saved crop region: " + config.cropWidth + "x" + config.cropHeight
                    + " at (" + config.cropX + "," + config.cropY + ")");
        }

        for (int f = 0; f < tifFiles.length; f++) {
            String fileName = tifFiles[f];
            String filePath = directory + fileName;
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

            // Step 0: Crop
            if (config.cropEnabled && config.cropX >= 0) {
                IJ.log("  Step 0: Crop (" + config.cropWidth + "x" + config.cropHeight + ")");
                ImageStack croppedStack = new ImageStack(config.cropWidth, config.cropHeight);
                ImageStack srcStack = imp.getStack();
                for (int s = 1; s <= srcStack.getSize(); s++) {
                    ImageProcessor slice = srcStack.getProcessor(s);
                    slice.setRoi(config.cropX, config.cropY, config.cropWidth, config.cropHeight);
                    croppedStack.addSlice(srcStack.getSliceLabel(s), slice.crop());
                }
                ImagePlus cropped = new ImagePlus(imp.getTitle(), croppedStack);
                cropped.setCalibration(imp.getCalibration().copy());
                imp.close();
                imp = cropped;
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
        dlg.addHelpText("Draw a rectangle on the first image to exclude empty well space.");

        // --- Frame Binning ---
        dlg.addSpacer(4);
        dlg.addHeader("Frame Binning");
        final ToggleSwitch binToggle = dlg.addToggle("Enable", config.binningEnabled);
        dlg.addNumericField("Bin Factor", config.binFactor, 0);
        String[] binMethods = {"Mean", "Sum"};
        dlg.addChoice("Method", binMethods, config.binMethod);

        // --- Motion Correction ---
        dlg.addSpacer(4);
        dlg.addHeader("Motion Correction");
        dlg.addToggle("Enable", config.motionCorrectionEnabled);
        String[] mcMethods = {"SIFT", "Cross-Correlation"};
        dlg.addChoice("Method", mcMethods, config.motionCorrectionMethod);
        String[] refMethods = {"Mean Projection", "Median Projection", "First Frame"};
        String currentRef = refToDisplay(config.motionCorrectionReference);
        dlg.addChoice("Reference (for Cross-Correlation)", refMethods, currentRef);
        dlg.addHelpText("SIFT is recommended — robust to intensity changes. Cross-Correlation is faster but translation-only.");

        // --- Background Subtraction ---
        dlg.addSpacer(4);
        dlg.addHeader("Background Subtraction");
        String[] bgMethods = {"None", "Rolling Ball", "Minimum Projection"};
        dlg.addChoice("Method", bgMethods, config.backgroundMethod);
        dlg.addNumericField("Radius (pixels)", config.backgroundRadius, 0);

        // --- Bleach / Decay Correction ---
        dlg.addSpacer(4);
        dlg.addHeader("Bleach / Decay Correction");
        String[] bleachMethods = {"None", "Mono-exponential", "Bi-exponential",
                "Sliding Percentile", "Simple Ratio"};
        final JComboBox<String> bleachCombo = dlg.addChoice("Method", bleachMethods, config.bleachMethod);
        dlg.addNumericField("Percentile Window (frames)", config.bleachPercentileWindow, 0);
        dlg.addNumericField("Percentile (%)", config.bleachPercentile, 1);
        dlg.addHelpText("Auto-selected based on reporter type. Sliding Percentile for bioluminescence, Bi-exponential for fluorescent/calcium.");

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
        String[] spatialTypes = {"None", "Gaussian", "Median"};
        dlg.addChoice("Type", spatialTypes, config.spatialFilterType);
        dlg.addNumericField("Sigma / Radius (pixels)", config.spatialFilterRadius, 1);

        // --- Temporal Filter ---
        dlg.addSpacer(4);
        dlg.addHeader("Temporal Filter");
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
        // Reset crop coords if re-enabled so user gets prompted again
        if (config.cropEnabled) {
            config.cropX = -1;
        }

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
