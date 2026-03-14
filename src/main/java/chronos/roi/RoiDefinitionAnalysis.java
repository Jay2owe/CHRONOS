package chronos.roi;

import chronos.Analysis;
import chronos.io.RoiIO;
import chronos.ui.PipelineDialog;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.plugin.ZProjector;
import ij.plugin.frame.RoiManager;

import java.awt.Color;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Module 2: ROI Definition.
 * <p>
 * Provides interactive ROI creation for SCN analysis with multiple ROI types:
 * Whole SCN outline, Dorsal/Ventral split, Grid overlay, Individual cells,
 * Custom regions, and automatic boundary detection.
 * <p>
 * If existing ROIs are found in {@code .circadian/ROIs/}, the user is offered
 * the choice to reuse them or redefine.
 */
public class RoiDefinitionAnalysis implements Analysis {

    private boolean headless = false;
    private int parallelThreads = 1;

    @Override
    public boolean execute(String directory) {
        IJ.log("ROI Definition: Starting...");

        // Find TIF files
        File dir = new File(directory);
        String[] tifFiles = findTifFiles(dir);
        if (tifFiles.length == 0) {
            IJ.log("ROI Definition: No TIF files found.");
            return false;
        }

        // Check for existing ROIs
        String roiDir = directory + ".circadian" + File.separator + "ROIs" + File.separator;
        new File(roiDir).mkdirs();

        boolean existingRoisFound = hasExistingRois(roiDir, tifFiles);

        if (existingRoisFound && !headless) {
            // Offer to reuse existing ROIs
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

        // Step 0: Choose ROI types
        boolean[] roiTypeFlags = new boolean[6]; // wholeSCN, dvSplit, grid, cells, custom, autoDetect
        int gridRows = 5;
        int gridCols = 5;
        int cellRadius = 10;

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

            roiTypeFlags[0] = typeDlg.getNextBoolean(); // Whole SCN
            roiTypeFlags[1] = typeDlg.getNextBoolean(); // D/V Split
            roiTypeFlags[2] = typeDlg.getNextBoolean(); // Grid
            roiTypeFlags[3] = typeDlg.getNextBoolean(); // Individual Cells
            roiTypeFlags[4] = typeDlg.getNextBoolean(); // Custom
            roiTypeFlags[5] = typeDlg.getNextBoolean(); // Auto-detect

            gridRows = Math.max(1, (int) typeDlg.getNextNumber());
            gridCols = Math.max(1, (int) typeDlg.getNextNumber());
            cellRadius = Math.max(1, (int) typeDlg.getNextNumber());
        } else {
            // Headless defaults: whole SCN only
            roiTypeFlags[0] = true;
        }

        boolean wantWholeSCN = roiTypeFlags[0];
        boolean wantDVSplit = roiTypeFlags[1];
        boolean wantGrid = roiTypeFlags[2];
        boolean wantCells = roiTypeFlags[3];
        boolean wantCustom = roiTypeFlags[4];
        boolean wantAutoDetect = roiTypeFlags[5];

        // Process each TIF file
        for (int fi = 0; fi < tifFiles.length; fi++) {
            String tifName = tifFiles[fi];
            String baseName = tifName;
            if (baseName.toLowerCase().endsWith(".tif")) {
                baseName = baseName.substring(0, baseName.length() - 4);
            } else if (baseName.toLowerCase().endsWith(".tiff")) {
                baseName = baseName.substring(0, baseName.length() - 5);
            }

            IJ.log("");
            IJ.log("[" + (fi + 1) + "/" + tifFiles.length + "] Defining ROIs for: " + tifName);

            // Load the image: prefer corrected, fall back to raw
            ImagePlus imp = loadImage(directory, tifName);
            if (imp == null) {
                IJ.log("  Could not load image. Skipping.");
                continue;
            }

            // Compute mean intensity projection
            ImagePlus meanProj = computeMeanProjection(imp);
            imp.close();

            List<Roi> allRois = new ArrayList<Roi>();
            Roi scnBoundary = null;

            // Step 1: Auto-detect if requested
            if (wantAutoDetect) {
                IJ.log("  Auto-detecting SCN boundary...");
                Roi detected = AutoBoundaryDetector.detect(meanProj);
                if (detected != null) {
                    scnBoundary = detected;
                    if (!headless) {
                        // Show detected boundary for verification
                        meanProj.setRoi(detected);
                        meanProj.show();
                        meanProj.setTitle("Auto-detected SCN Boundary -- " + baseName);

                        PipelineDialog verifyDlg = new PipelineDialog("CHRONOS -- Verify Boundary");
                        verifyDlg.addHeader("Auto-detected SCN Boundary");
                        verifyDlg.addMessage("The auto-detected boundary is shown on the image. " +
                                "You can accept it or redraw manually.");
                        verifyDlg.addToggle("Accept auto-detected boundary", true);

                        if (verifyDlg.showDialog()) {
                            boolean accept = verifyDlg.getNextBoolean();
                            if (!accept) {
                                // Let user redraw
                                IJ.log("  User chose to redraw boundary.");
                                meanProj.deleteRoi();
                                scnBoundary = null;
                            } else {
                                IJ.log("  Auto-detected boundary accepted.");
                            }
                        }
                        if (!wantWholeSCN && !wantGrid && !wantDVSplit && !wantCells && !wantCustom) {
                            meanProj.hide();
                        }
                    }
                } else {
                    IJ.log("  Auto-detection failed. Manual drawing required.");
                }
            }

            // Step 2: Interactive ROI drawing
            if (!headless) {
                // Show the image for interactive drawing
                meanProj.show();
                meanProj.setTitle("Draw ROIs -- " + baseName);

                // Open ROI Manager
                RoiManager rm = RoiManager.getInstance();
                if (rm == null) {
                    rm = new RoiManager();
                }
                rm.reset();

                // If we have an auto-detected boundary, add it to manager
                if (scnBoundary != null) {
                    rm.addRoi(scnBoundary);
                }

                // Whole SCN outline
                if (wantWholeSCN && scnBoundary == null) {
                    WaitForUserDialog waitDlg = new WaitForUserDialog(
                            "CHRONOS -- Whole SCN",
                            "Draw the whole SCN outline using Freehand or Polygon tool,\n" +
                            "then press OK.\n\n" +
                            "The ROI will be added to the ROI Manager.");
                    waitDlg.show();
                    if (waitDlg.escPressed()) {
                        IJ.log("  Cancelled by user.");
                        meanProj.close();
                        return false;
                    }
                    Roi drawn = meanProj.getRoi();
                    if (drawn != null) {
                        drawn.setName("Whole_SCN");
                        scnBoundary = drawn;
                        rm.addRoi(drawn);
                        meanProj.deleteRoi();
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
                            "CHRONOS -- Dorsal/Ventral",
                            "Draw a straight line across the SCN to divide\n" +
                            "Dorsal (above) from Ventral (below),\n" +
                            "then press OK.");
                    dvWait.show();
                    if (!dvWait.escPressed()) {
                        Roi lineRoi = meanProj.getRoi();
                        if (lineRoi != null && lineRoi instanceof Line) {
                            Roi[] dvRois = DorsalVentralSplitter.split(scnBoundary, (Line) lineRoi);
                            for (Roi r : dvRois) {
                                allRois.add(r);
                                rm.addRoi(r);
                            }
                            meanProj.deleteRoi();
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

                // Individual cells
                if (wantCells) {
                    WaitForUserDialog cellWait = new WaitForUserDialog(
                            "CHRONOS -- Individual Cells",
                            "Click to place point ROIs for individual cells.\n" +
                            "Use Multi-point tool or add each point to ROI Manager.\n" +
                            "Press OK when done.");
                    cellWait.show();
                    if (!cellWait.escPressed()) {
                        // Collect any point ROIs from the manager that were added
                        Roi[] managerRois = rm.getRoisAsArray();
                        int cellCount = 0;
                        for (Roi r : managerRois) {
                            if (r.getType() == Roi.POINT) {
                                // Convert point to circular ROI
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
                            "CHRONOS -- Custom Regions",
                            "Draw custom ROIs and add each to the ROI Manager (press 't').\n" +
                            "Press OK when done.");
                    customWait.show();
                    if (!customWait.escPressed()) {
                        // Gather any new ROIs from the manager
                        Roi[] managerRois = rm.getRoisAsArray();
                        int customCount = 0;
                        for (Roi r : managerRois) {
                            // Check if this ROI is already in our list by name
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

                // Step 3: Confirmation -- show all ROIs overlaid
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
                    meanProj.setOverlay(overlay);
                    meanProj.updateAndDraw();

                    PipelineDialog confirmDlg = new PipelineDialog("CHRONOS -- Confirm ROIs");
                    confirmDlg.addHeader("ROI Confirmation");
                    confirmDlg.addMessage(allRois.size() + " ROI(s) defined. " +
                            "All ROIs are shown overlaid on the mean projection.");
                    for (Roi r : allRois) {
                        String name = r.getName() != null ? r.getName() : "(unnamed)";
                        confirmDlg.addMessage("  - " + name);
                    }
                    confirmDlg.addSpacer(4);
                    confirmDlg.addMessage("Press OK to save, or Cancel to discard.");

                    if (!confirmDlg.showDialog()) {
                        IJ.log("  ROI definition cancelled for " + tifName);
                        meanProj.close();
                        continue;
                    }
                }

                meanProj.close();

            } else {
                // Headless mode: only auto-detect is possible
                if (wantAutoDetect) {
                    Roi detected = AutoBoundaryDetector.detect(meanProj);
                    if (detected != null) {
                        detected.setName("Whole_SCN");
                        allRois.add(detected);
                    }
                }
            }

            // Save ROIs
            if (!allRois.isEmpty()) {
                String roiPath = roiDir + baseName + "_rois.zip";
                RoiIO.saveRoisToZip(allRois.toArray(new Roi[0]), roiPath);
                IJ.log("  Saved " + allRois.size() + " ROI(s) to " + roiPath);
            } else {
                IJ.log("  No ROIs defined for " + tifName);
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

    /**
     * Finds TIF files in the given directory.
     */
    private String[] findTifFiles(File dir) {
        String[] files = dir.list(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                String lower = name.toLowerCase();
                return lower.endsWith(".tif") || lower.endsWith(".tiff");
            }
        });
        return files != null ? files : new String[0];
    }

    /**
     * Loads an image, preferring the corrected version from .circadian/corrected/.
     */
    private ImagePlus loadImage(String directory, String tifName) {
        // Try corrected first
        String baseName = tifName;
        if (baseName.toLowerCase().endsWith(".tif")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        } else if (baseName.toLowerCase().endsWith(".tiff")) {
            baseName = baseName.substring(0, baseName.length() - 5);
        }

        String correctedPath = directory + ".circadian" + File.separator +
                "corrected" + File.separator + baseName + "_corrected.tif";
        File correctedFile = new File(correctedPath);
        if (correctedFile.exists()) {
            IJ.log("  Loading corrected image: " + correctedPath);
            return IJ.openImage(correctedPath);
        }

        // Fall back to raw
        String rawPath = directory + tifName;
        IJ.log("  Loading raw image: " + rawPath);
        return IJ.openImage(rawPath);
    }

    /**
     * Computes the mean intensity projection of a stack.
     */
    private ImagePlus computeMeanProjection(ImagePlus imp) {
        if (imp.getStackSize() <= 1) {
            return imp.duplicate();
        }
        ZProjector zp = new ZProjector(imp);
        zp.setMethod(ZProjector.AVG_METHOD);
        zp.doProjection();
        ImagePlus proj = zp.getProjection();
        proj.setTitle("Mean_Projection");
        return proj;
    }

    /**
     * Checks if ROI zip files already exist for the given TIF files.
     */
    private boolean hasExistingRois(String roiDir, String[] tifFiles) {
        for (String tif : tifFiles) {
            String baseName = tif;
            if (baseName.toLowerCase().endsWith(".tif")) {
                baseName = baseName.substring(0, baseName.length() - 4);
            } else if (baseName.toLowerCase().endsWith(".tiff")) {
                baseName = baseName.substring(0, baseName.length() - 5);
            }
            File roiFile = new File(roiDir + baseName + "_rois.zip");
            if (roiFile.exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Saves ROI configuration (grid params, etc.) to roi_config.txt.
     */
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
