package chronos.roi;

import chronos.Analysis;
import chronos.io.RoiIO;
import chronos.ui.PipelineDialog;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.plugin.ZProjector;
import ij.plugin.frame.RoiManager;

import java.awt.Color;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Module 2: ROI Definition.
 * <p>
 * Discovers all image sets, reports info to the user, computes and saves
 * mean + max projections, then presents each image sequentially for ROI drawing.
 * Each image gets its own interactive session with confirmation before moving on.
 */
public class RoiDefinitionAnalysis implements Analysis {

    private boolean headless = false;
    private int parallelThreads = 1;

    @Override
    public boolean execute(String directory) {
        IJ.log("ROI Definition: Starting...");

        // =====================================================================
        // STEP 1: Find all image sets and report info
        // =====================================================================
        File dir = new File(directory);
        String[] tifFiles = findTifFiles(dir);
        if (tifFiles.length == 0) {
            IJ.log("ROI Definition: No TIF files found.");
            return false;
        }

        Arrays.sort(tifFiles);

        IJ.log("");
        IJ.log("ROI Definition: Found " + tifFiles.length + " image set(s):");
        IJ.log("-------------------------------------------------------------");

        String[] baseNames = new String[tifFiles.length];
        for (int i = 0; i < tifFiles.length; i++) {
            String tifName = tifFiles[i];
            baseNames[i] = stripExtension(tifName);

            ImagePlus imp = loadImage(directory, tifName);
            if (imp != null) {
                int w = imp.getWidth();
                int h = imp.getHeight();
                int nFrames = imp.getStackSize();
                int bitDepth = imp.getBitDepth();
                double sizeMB = (double) w * h * nFrames * (bitDepth / 8) / (1024.0 * 1024.0);
                IJ.log("  [" + (i + 1) + "] " + tifName);
                IJ.log("      " + w + " x " + h + " px, " + nFrames + " frames, "
                        + bitDepth + "-bit, " + String.format("%.1f", sizeMB) + " MB");
                imp.close();
            } else {
                IJ.log("  [" + (i + 1) + "] " + tifName + " (could not load)");
            }
        }
        IJ.log("-------------------------------------------------------------");

        // Check for existing ROIs
        String roiDir = directory + ".circadian" + File.separator + "ROIs" + File.separator;
        new File(roiDir).mkdirs();

        boolean existingRoisFound = hasExistingRois(roiDir, tifFiles);

        if (existingRoisFound && !headless) {
            PipelineDialog reuseDlg = new PipelineDialog("CHRONOS -- ROI Definition");
            reuseDlg.addHeader("Existing ROIs Found");
            reuseDlg.addMessage("ROI sets were found in .circadian/ROIs/ from a previous run.");
            reuseDlg.addToggle("Use existing ROIs (skip redefinition)", true);
            if (reuseDlg.showDialog()) {
                boolean useExisting = reuseDlg.getNextBoolean();
                if (useExisting) {
                    IJ.log("ROI Definition: Using existing ROIs.");
                    return true;
                }
            } else {
                IJ.log("ROI Definition: Cancelled.");
                return false;
            }
        }

        // =====================================================================
        // STEP 2: ROI type selection dialog
        // =====================================================================
        boolean[] roiTypeFlags = new boolean[7];
        int gridRows = 5;
        int gridCols = 5;
        int cellRadius = 10;
        String projectionType = "Mean";

        if (!headless) {
            PipelineDialog typeDlg = new PipelineDialog("CHRONOS -- ROI Definition");
            typeDlg.addHeader("ROI Types");
            typeDlg.addHelpText("Select which ROI types to define. Multiple can be combined.");
            typeDlg.addToggle("Whole SCN Outline", true);
            typeDlg.addToggle("Dorsal/Ventral Split", false);
            typeDlg.addToggle("Grid Overlay", false);
            typeDlg.addToggle("Individual Cells", false);
            typeDlg.addToggle("Custom Regions", false);
            typeDlg.addToggle("Auto-detect SCN Boundary", false);
            typeDlg.addToggle("Auto-detect Core/Shell", false);
            typeDlg.addHelpText("Subdivides the SCN boundary into Core (bright) and Shell (dim) using k-means clustering.");

            typeDlg.addSpacer(8);
            typeDlg.addHeader("Projection for ROI Drawing");
            typeDlg.addHelpText("Use Max projection if the slice moved during recording. " +
                    "Both Mean and Max projections are computed and saved regardless.");
            typeDlg.addChoice("Show projection", new String[]{"Mean", "Max"}, "Mean");

            typeDlg.addSpacer(8);
            typeDlg.addHeader("Grid Settings");
            typeDlg.addNumericField("Grid Rows", 5, 0);
            typeDlg.addNumericField("Grid Cols", 5, 0);

            typeDlg.addSpacer(8);
            typeDlg.addHeader("Cell Detection Settings");
            typeDlg.addNumericField("Cell Radius (px)", 10, 0);

            if (!typeDlg.showDialog()) {
                IJ.log("ROI Definition: Cancelled.");
                return false;
            }

            roiTypeFlags[0] = typeDlg.getNextBoolean();
            roiTypeFlags[1] = typeDlg.getNextBoolean();
            roiTypeFlags[2] = typeDlg.getNextBoolean();
            roiTypeFlags[3] = typeDlg.getNextBoolean();
            roiTypeFlags[4] = typeDlg.getNextBoolean();
            roiTypeFlags[5] = typeDlg.getNextBoolean();
            roiTypeFlags[6] = typeDlg.getNextBoolean();

            projectionType = typeDlg.getNextChoice();
            gridRows = Math.max(1, (int) typeDlg.getNextNumber());
            gridCols = Math.max(1, (int) typeDlg.getNextNumber());
            cellRadius = Math.max(1, (int) typeDlg.getNextNumber());
        } else {
            roiTypeFlags[0] = true;
        }

        boolean wantWholeSCN = roiTypeFlags[0];
        boolean wantDVSplit = roiTypeFlags[1];
        boolean wantGrid = roiTypeFlags[2];
        boolean wantCells = roiTypeFlags[3];
        boolean wantCustom = roiTypeFlags[4];
        boolean wantAutoDetect = roiTypeFlags[5];
        boolean wantCoreShell = roiTypeFlags[6];
        boolean useMaxProj = "Max".equals(projectionType);

        // =====================================================================
        // STEP 3: Compute projections for ALL files, save to .circadian/projections/
        // =====================================================================
        String projDir = directory + ".circadian" + File.separator + "projections" + File.separator;
        new File(projDir).mkdirs();

        IJ.log("");
        IJ.log("ROI Definition: Checking projections...");

        for (int i = 0; i < tifFiles.length; i++) {
            String tifName = tifFiles[i];
            String baseName = baseNames[i];
            IJ.showProgress(i, tifFiles.length);

            // Also check for name without _corrected suffix
            String strippedBase = baseName.endsWith("_corrected")
                    ? baseName.substring(0, baseName.length() - "_corrected".length())
                    : baseName;

            boolean hasMean = new File(projDir + baseName + "_mean.tif").exists()
                    || new File(projDir + strippedBase + "_mean.tif").exists();
            boolean hasMax = new File(projDir + baseName + "_max.tif").exists()
                    || new File(projDir + strippedBase + "_max.tif").exists();

            if (hasMean && hasMax) {
                IJ.log("  [" + (i + 1) + "/" + tifFiles.length + "] " + baseName
                        + " — projections already exist, reusing");
                continue;
            }

            ImagePlus imp = loadImage(directory, tifName);
            if (imp == null) {
                IJ.log("  Could not load " + tifName + ". Skipping.");
                continue;
            }

            if (!hasMean) {
                ImagePlus meanProj = computeProjection(imp, ZProjector.AVG_METHOD);
                new FileSaver(meanProj).saveAsTiff(projDir + baseName + "_mean.tif");
                meanProj.close();
            }

            if (!hasMax) {
                ImagePlus maxProj = computeProjection(imp, ZProjector.MAX_METHOD);
                new FileSaver(maxProj).saveAsTiff(projDir + baseName + "_max.tif");
                maxProj.close();
            }

            imp.close();

            IJ.log("  [" + (i + 1) + "/" + tifFiles.length + "] " + baseName
                    + " — projections computed and saved");
        }

        IJ.showProgress(1.0);
        IJ.log("ROI Definition: All projections ready in .circadian/projections/");

        // =====================================================================
        // STEP 4: Sequential interactive ROI drawing — one image at a time
        // =====================================================================
        for (int fi = 0; fi < tifFiles.length; fi++) {
            String baseName = baseNames[fi];

            IJ.log("");
            IJ.log("[" + (fi + 1) + "/" + tifFiles.length + "] Defining ROIs for: " + baseName);

            // Load the appropriate projection (try exact name, then stripped name)
            String strippedBase = baseName.endsWith("_corrected")
                    ? baseName.substring(0, baseName.length() - "_corrected".length())
                    : baseName;
            String suffix = useMaxProj ? "_max.tif" : "_mean.tif";
            String projPath = projDir + baseName + suffix;
            if (!new File(projPath).exists()) {
                projPath = projDir + strippedBase + suffix;
            }
            ImagePlus displayProj = IJ.openImage(projPath);
            if (displayProj == null) {
                IJ.log("  Could not load projection. Skipping.");
                continue;
            }
            displayProj.setTitle("ROIs [" + (fi + 1) + "/" + tifFiles.length + "] — " + baseName);

            if (headless) {
                // Headless: only auto-detect
                List<Roi> allRois = new ArrayList<Roi>();
                if (wantAutoDetect) {
                    Roi detected = AutoBoundaryDetector.detect(displayProj);
                    if (detected != null) {
                        detected.setName("Whole_SCN");
                        allRois.add(detected);
                    }
                }
                if (!allRois.isEmpty()) {
                    String roiPath = roiDir + baseName + "_rois.zip";
                    RoiIO.saveRoisToZip(allRois.toArray(new Roi[0]), roiPath);
                    IJ.log("  Saved " + allRois.size() + " ROI(s)");
                }
                displayProj.close();
                continue;
            }

            // --- Interactive mode ---
            List<Roi> allRois = new ArrayList<Roi>();
            Roi scnBoundary = null;

            // Auto-detect if requested
            if (wantAutoDetect) {
                IJ.log("  Auto-detecting SCN boundary...");
                // Use mean projection for auto-detect (more reliable)
                String meanDetectPath = projDir + baseName + "_mean.tif";
                if (!new File(meanDetectPath).exists()) {
                    meanDetectPath = projDir + strippedBase + "_mean.tif";
                }
                ImagePlus meanForDetect = IJ.openImage(meanDetectPath);
                Roi detected = AutoBoundaryDetector.detect(
                        meanForDetect != null ? meanForDetect : displayProj);
                if (meanForDetect != null) meanForDetect.close();

                if (detected != null) {
                    scnBoundary = detected;
                    displayProj.setRoi(detected);
                    displayProj.show();
                    displayProj.setTitle("Auto-detected Boundary — " + baseName);

                    PipelineDialog verifyDlg = new PipelineDialog("CHRONOS -- Verify Boundary");
                    verifyDlg.addHeader("Auto-detected SCN Boundary");
                    verifyDlg.addMessage("The auto-detected boundary is shown on the image.");
                    verifyDlg.addToggle("Accept auto-detected boundary", true);

                    if (verifyDlg.showDialog()) {
                        if (!verifyDlg.getNextBoolean()) {
                            IJ.log("  User chose to redraw boundary.");
                            displayProj.deleteRoi();
                            scnBoundary = null;
                        } else {
                            IJ.log("  Auto-detected boundary accepted.");
                        }
                    }
                    if (!wantWholeSCN && !wantGrid && !wantDVSplit && !wantCells && !wantCustom) {
                        displayProj.hide();
                    }
                } else {
                    IJ.log("  Auto-detection failed. Manual drawing required.");
                }
            }

            // Show image for interactive drawing
            displayProj.show();
            displayProj.setTitle("Draw ROIs [" + (fi + 1) + "/" + tifFiles.length + "] — " + baseName);

            // Open/reset ROI Manager
            RoiManager rm = RoiManager.getInstance();
            if (rm == null) {
                rm = new RoiManager();
            }
            rm.reset();

            if (scnBoundary != null) {
                rm.addRoi(scnBoundary);
            }

            // Whole SCN outline
            if (wantWholeSCN && scnBoundary == null) {
                WaitForUserDialog waitDlg = new WaitForUserDialog(
                        "CHRONOS -- Whole SCN [" + (fi + 1) + "/" + tifFiles.length + "]",
                        "Image: " + baseName + "\n\n" +
                        "Draw the whole SCN outline using Freehand or Polygon tool,\n" +
                        "then press OK.\n\n" +
                        "The ROI will be added to the ROI Manager.");
                waitDlg.show();
                if (waitDlg.escPressed()) {
                    IJ.log("  Cancelled by user.");
                    displayProj.close();
                    return false;
                }
                Roi drawn = displayProj.getRoi();
                if (drawn != null) {
                    drawn.setName("Whole_SCN");
                    scnBoundary = drawn;
                    rm.addRoi(drawn);
                    displayProj.deleteRoi();
                    IJ.log("  Whole SCN outline drawn.");
                }
            } else if (wantWholeSCN && scnBoundary != null) {
                scnBoundary.setName("Whole_SCN");
            }

            if (scnBoundary != null) {
                allRois.add(scnBoundary);
            }

            // Dorsal/Ventral split
            if (wantDVSplit && scnBoundary != null) {
                WaitForUserDialog dvWait = new WaitForUserDialog(
                        "CHRONOS -- D/V Split [" + (fi + 1) + "/" + tifFiles.length + "]",
                        "Image: " + baseName + "\n\n" +
                        "Draw a straight line across the SCN to divide\n" +
                        "Dorsal (above) from Ventral (below),\n" +
                        "then press OK.");
                dvWait.show();
                if (!dvWait.escPressed()) {
                    Roi lineRoi = displayProj.getRoi();
                    if (lineRoi != null && lineRoi instanceof Line) {
                        Roi[] dvRois = DorsalVentralSplitter.split(scnBoundary, (Line) lineRoi);
                        for (Roi r : dvRois) {
                            allRois.add(r);
                            rm.addRoi(r);
                        }
                        displayProj.deleteRoi();
                        IJ.log("  Dorsal/Ventral split created.");
                    } else {
                        IJ.log("  No line ROI detected. Skipping D/V split.");
                    }
                }
            }

            // Grid overlay
            if (wantGrid && scnBoundary != null) {
                Roi[] gridRois = GridRoiGenerator.generateGrid(scnBoundary, gridRows, gridCols);
                for (Roi r : gridRois) {
                    allRois.add(r);
                    rm.addRoi(r);
                }
                IJ.log("  Grid overlay created (" + gridRois.length + " cells).");
            }

            // Core/Shell detection
            if (wantCoreShell && scnBoundary != null) {
                Roi[] coreShellRois = SubRegionDetector.detectCoreShell(displayProj, scnBoundary);
                for (Roi r : coreShellRois) {
                    allRois.add(r);
                    rm.addRoi(r);
                }
                if (coreShellRois.length > 0) {
                    IJ.log("  Core/Shell detection: " + coreShellRois.length + " sub-regions.");
                } else {
                    IJ.log("  Core/Shell detection failed.");
                }
            }

            // Individual cells
            if (wantCells) {
                WaitForUserDialog cellWait = new WaitForUserDialog(
                        "CHRONOS -- Cells [" + (fi + 1) + "/" + tifFiles.length + "]",
                        "Image: " + baseName + "\n\n" +
                        "Click to place point ROIs for individual cells.\n" +
                        "Use Multi-point tool or add each point to ROI Manager.\n" +
                        "Press OK when done.");
                cellWait.show();
                if (!cellWait.escPressed()) {
                    Roi[] managerRois = rm.getRoisAsArray();
                    int cellCount = 0;
                    for (Roi r : managerRois) {
                        if (r.getType() == Roi.POINT) {
                            java.awt.Rectangle b = r.getBounds();
                            Roi circle = new ij.gui.OvalRoi(
                                    b.x - cellRadius, b.y - cellRadius,
                                    cellRadius * 2, cellRadius * 2);
                            cellCount++;
                            circle.setName("Cell_" + cellCount);
                            allRois.add(circle);
                        }
                    }
                    IJ.log("  Individual cells: " + cellCount + " cell ROIs created.");
                }
            }

            // Custom regions
            if (wantCustom) {
                WaitForUserDialog customWait = new WaitForUserDialog(
                        "CHRONOS -- Custom [" + (fi + 1) + "/" + tifFiles.length + "]",
                        "Image: " + baseName + "\n\n" +
                        "Draw custom ROIs and add each to the ROI Manager (press 't').\n" +
                        "Press OK when done.");
                customWait.show();
                if (!customWait.escPressed()) {
                    Roi[] managerRois = rm.getRoisAsArray();
                    int customCount = 0;
                    for (Roi r : managerRois) {
                        boolean alreadyAdded = false;
                        for (Roi existing : allRois) {
                            if (existing.getName() != null && existing.getName().equals(r.getName())) {
                                alreadyAdded = true;
                                break;
                            }
                        }
                        if (!alreadyAdded) {
                            customCount++;
                            if (r.getName() == null || r.getName().isEmpty()) {
                                r.setName("Custom_" + customCount);
                            }
                            allRois.add(r);
                        }
                    }
                    IJ.log("  Custom regions: " + customCount + " ROIs added.");
                }
            }

            // Confirmation — show all ROIs overlaid
            if (!allRois.isEmpty()) {
                Overlay overlay = new Overlay();
                Color[] colors = {Color.YELLOW, Color.CYAN, Color.MAGENTA,
                                  Color.GREEN, Color.RED, Color.ORANGE};
                for (int i = 0; i < allRois.size(); i++) {
                    Roi r = allRois.get(i);
                    r.setStrokeColor(colors[i % colors.length]);
                    r.setStrokeWidth(1.5);
                    overlay.add(r);
                }
                displayProj.setOverlay(overlay);
                displayProj.updateAndDraw();

                PipelineDialog confirmDlg = new PipelineDialog("CHRONOS -- Confirm ROIs");
                confirmDlg.addHeader("ROI Confirmation — " + baseName);
                confirmDlg.addMessage(allRois.size() + " ROI(s) defined:");
                for (Roi r : allRois) {
                    String name = r.getName() != null ? r.getName() : "(unnamed)";
                    confirmDlg.addMessage("  • " + name);
                }
                confirmDlg.addSpacer(4);
                confirmDlg.addMessage("Press OK to save, or Cancel to discard.");

                if (!confirmDlg.showDialog()) {
                    IJ.log("  ROI definition cancelled for " + baseName);
                    displayProj.close();
                    continue;
                }
            }

            displayProj.close();

            // Save ROIs
            if (!allRois.isEmpty()) {
                String roiPath = roiDir + baseName + "_rois.zip";
                RoiIO.saveRoisToZip(allRois.toArray(new Roi[0]), roiPath);
                IJ.log("  Saved " + allRois.size() + " ROI(s) to " + roiPath);
            } else {
                IJ.log("  No ROIs defined for " + baseName);
            }

            // Save grid/DV config if applicable
            if (wantGrid || wantDVSplit) {
                saveRoiConfig(roiDir, gridRows, gridCols, cellRadius);
            }
        }

        IJ.log("");
        IJ.log("ROI Definition: Complete.");
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
        return "ROI Definition";
    }

    @Override
    public int getIndex() {
        return 2;
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private String[] findTifFiles(File dir) {
        // Look in corrected directory first, then assembled, then raw
        File correctedDir = new File(dir, ".circadian" + File.separator + "corrected");
        File assembledDir = new File(dir, ".circadian" + File.separator + "assembled");

        String[] corrected = listTifs(correctedDir);
        if (corrected.length > 0) {
            IJ.log("ROI Definition: Using corrected images from .circadian/corrected/");
            return corrected;
        }

        String[] assembled = listTifs(assembledDir);
        if (assembled.length > 0) {
            IJ.log("ROI Definition: Using assembled stacks from .circadian/assembled/");
            return assembled;
        }

        return listTifs(dir);
    }

    private String[] listTifs(File dir) {
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

    private ImagePlus loadImage(String directory, String tifName) {
        String baseName = stripExtension(tifName);

        // Try exact filename in corrected directory first (handles already-corrected names)
        String correctedExact = directory + ".circadian" + File.separator +
                "corrected" + File.separator + tifName;
        if (new File(correctedExact).exists()) {
            return IJ.openImage(correctedExact);
        }

        // Try adding _corrected suffix (for when raw filename is passed)
        String correctedSuffix = directory + ".circadian" + File.separator +
                "corrected" + File.separator + baseName + "_corrected.tif";
        if (new File(correctedSuffix).exists()) {
            return IJ.openImage(correctedSuffix);
        }

        String assembledPath = directory + ".circadian" + File.separator +
                "assembled" + File.separator + tifName;
        if (new File(assembledPath).exists()) {
            return IJ.openImage(assembledPath);
        }

        String rawPath = directory + tifName;
        if (new File(rawPath).exists()) {
            return IJ.openImage(rawPath);
        }

        return null;
    }

    private ImagePlus computeProjection(ImagePlus imp, int method) {
        if (imp.getStackSize() <= 1) {
            return imp.duplicate();
        }
        ZProjector zp = new ZProjector(imp);
        zp.setMethod(method);
        zp.doProjection();
        return zp.getProjection();
    }

    private String stripExtension(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".tiff")) {
            return filename.substring(0, filename.length() - 5);
        } else if (lower.endsWith(".tif")) {
            return filename.substring(0, filename.length() - 4);
        }
        return filename;
    }

    private boolean hasExistingRois(String roiDir, String[] tifFiles) {
        for (String tif : tifFiles) {
            String baseName = stripExtension(tif);
            File roiFile = new File(roiDir + baseName + "_rois.zip");
            if (roiFile.exists()) {
                return true;
            }
        }
        return false;
    }

    private void saveRoiConfig(String roiDir, int gridRows, int gridCols, int cellRadius) {
        String configPath = roiDir + "roi_config.txt";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(configPath)));
            pw.println("grid_rows=" + gridRows);
            pw.println("grid_cols=" + gridCols);
            pw.println("cell_radius=" + cellRadius);
            IJ.log("  Saved ROI config to " + configPath);
        } catch (IOException e) {
            IJ.log("  Error saving ROI config: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }
}
