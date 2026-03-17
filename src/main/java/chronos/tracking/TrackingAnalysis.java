package chronos.tracking;

import chronos.Analysis;
import chronos.config.SessionConfig;
import chronos.config.SessionConfigIO;
import chronos.io.CsvWriter;
import chronos.ui.PipelineDialog;

import ij.IJ;
import ij.ImagePlus;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Module 7: Cell Tracking Analysis.
 * <p>
 * Uses TrackMate with StarDist to detect and track microglia (or other cells)
 * across a time-lapse recording. Computes motility metrics (speed, displacement,
 * area, MSD) as time-series that can be fed into the rhythm analysis pipeline.
 * <p>
 * This module is optional and requires TrackMate + StarDist update sites.
 * If not available, it logs a message and skips gracefully.
 */
public class TrackingAnalysis implements Analysis {

    private boolean headless = false;
    private int parallelThreads = 1;

    @Override
    public boolean execute(String directory) {
        IJ.log("===== CHRONOS — Cell Tracking Analysis =====");

        // Check TrackMate availability
        if (!StarDistDetector.isAvailable()) {
            IJ.log("  " + StarDistDetector.getAvailabilityMessage());
            IJ.log("  Skipping tracking analysis.");
            return true; // Not a failure — just not available
        }

        SessionConfig config = SessionConfigIO.readFromDirectory(directory);

        // Show configuration dialog
        if (!headless) {
            if (!showDialog(config)) {
                IJ.log("  Tracking Analysis: Cancelled by user.");
                return false;
            }
            SessionConfigIO.writeToDirectory(directory, config);
        }

        // Find corrected stacks (or raw TIFs)
        String correctedDir = directory + File.separator + ".circadian" + File.separator + "corrected";
        String trackingDir = directory + File.separator + ".circadian" + File.separator + "tracking";
        new File(trackingDir).mkdirs();

        File sourceDir = new File(correctedDir);
        if (!sourceDir.exists()) {
            sourceDir = new File(directory);
        }

        String[] tifFiles = sourceDir.list(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                String lower = name.toLowerCase();
                return (lower.endsWith(".tif") || lower.endsWith(".tiff")) && !lower.startsWith(".");
            }
        });

        if (tifFiles == null || tifFiles.length == 0) {
            IJ.error("Tracking Analysis", "No TIF stacks found.");
            return false;
        }

        java.util.Arrays.sort(tifFiles);
        IJ.log("  Found " + tifFiles.length + " stack(s) to track");

        double frameIntervalMin = config.frameIntervalMin;
        double frameIntervalHours = frameIntervalMin / 60.0;

        for (int fi = 0; fi < tifFiles.length; fi++) {
            String filename = tifFiles[fi];
            IJ.log("");
            IJ.log("  [" + (fi + 1) + "/" + tifFiles.length + "] " + filename);
            IJ.showProgress(fi, tifFiles.length);

            String path = sourceDir.getAbsolutePath() + File.separator + filename;
            ImagePlus imp = IJ.openImage(path);
            if (imp == null) {
                IJ.log("    Could not open: " + path);
                continue;
            }

            int nFrames = imp.getStackSize();
            if (imp.getNFrames() > 1) nFrames = imp.getNFrames();

            // Run TrackMate
            TrackingResult result = TrackMateRunner.run(imp, 1,
                    config.trackMaxLinkDistance,
                    config.trackMaxGapFrames,
                    config.trackGapClosingDistance);

            imp.close();

            if (result == null || result.nTracks() == 0) {
                IJ.log("    No tracks found.");
                continue;
            }

            // Extract base name
            String baseName = filename;
            if (baseName.endsWith("_corrected.tif")) {
                baseName = baseName.substring(0, baseName.length() - "_corrected.tif".length());
            } else if (baseName.endsWith(".tif") || baseName.endsWith(".tiff")) {
                baseName = baseName.substring(0, baseName.lastIndexOf('.'));
            }

            // Filter tracks by minimum duration
            List<CellTrack> longTracks = new ArrayList<CellTrack>();
            for (CellTrack track : result.tracks) {
                if (track.duration() >= config.trackMinDurationFrames) {
                    longTracks.add(track);
                }
            }

            IJ.log("    " + longTracks.size() + "/" + result.nTracks() +
                    " tracks pass minimum duration (" + config.trackMinDurationFrames + " frames)");

            if (longTracks.isEmpty()) continue;

            // Compute and save motility time-series
            saveSpeedTraces(trackingDir, baseName, longTracks, nFrames, frameIntervalMin);
            saveAreaTraces(trackingDir, baseName, longTracks, nFrames, frameIntervalMin);
            saveDisplacementTraces(trackingDir, baseName, longTracks, nFrames, frameIntervalMin);
            saveTrackSummary(trackingDir, baseName, longTracks, frameIntervalHours);
        }

        IJ.showProgress(1.0);
        IJ.log("");
        IJ.log("  Tracking Analysis: Complete.");
        return true;
    }

    private boolean showDialog(SessionConfig config) {
        PipelineDialog dlg = new PipelineDialog("CHRONOS - Cell Tracking");

        dlg.addHeader("StarDist Detection");
        dlg.addHelpText("Uses StarDist AI to detect cells per frame, then " +
                "LAP tracker links them across time.");

        dlg.addSpacer(8);
        dlg.addHeader("Tracking Parameters");
        dlg.addNumericField("Max linking distance (px):", config.trackMaxLinkDistance, 1);
        dlg.addNumericField("Max gap (frames):", config.trackMaxGapFrames, 0);
        dlg.addNumericField("Gap closing distance (px):", config.trackGapClosingDistance, 1);

        dlg.addSpacer(8);
        dlg.addHeader("Filtering");
        dlg.addNumericField("Min track duration (frames):", config.trackMinDurationFrames, 0);
        dlg.addHelpText("Tracks shorter than this are discarded.");

        if (!dlg.showDialog()) return false;

        config.trackMaxLinkDistance = dlg.getNextNumber();
        config.trackMaxGapFrames = (int) dlg.getNextNumber();
        config.trackGapClosingDistance = dlg.getNextNumber();
        config.trackMinDurationFrames = (int) dlg.getNextNumber();

        return true;
    }

    private void saveSpeedTraces(String dir, String baseName,
                                  List<CellTrack> tracks, int nFrames,
                                  double frameIntervalMin) {
        String[] names = new String[tracks.size()];
        double[][] data = new double[tracks.size()][nFrames];

        for (int t = 0; t < tracks.size(); t++) {
            names[t] = "Cell_" + tracks.get(t).trackID;
            data[t] = MotilityMetrics.speedTimeSeries(tracks.get(t), nFrames);
        }

        String path = dir + File.separator + "Speed_Traces_" + baseName + ".csv";
        CsvWriter.writeTraces(path, names, data, frameIntervalMin);
        IJ.log("    Saved: Speed_Traces_" + baseName + ".csv");
    }

    private void saveAreaTraces(String dir, String baseName,
                                 List<CellTrack> tracks, int nFrames,
                                 double frameIntervalMin) {
        String[] names = new String[tracks.size()];
        double[][] data = new double[tracks.size()][nFrames];

        for (int t = 0; t < tracks.size(); t++) {
            names[t] = "Cell_" + tracks.get(t).trackID;
            data[t] = MotilityMetrics.areaTimeSeries(tracks.get(t), nFrames);
        }

        String path = dir + File.separator + "Area_Traces_" + baseName + ".csv";
        CsvWriter.writeTraces(path, names, data, frameIntervalMin);
        IJ.log("    Saved: Area_Traces_" + baseName + ".csv");
    }

    private void saveDisplacementTraces(String dir, String baseName,
                                         List<CellTrack> tracks, int nFrames,
                                         double frameIntervalMin) {
        String[] names = new String[tracks.size()];
        double[][] data = new double[tracks.size()][nFrames];

        for (int t = 0; t < tracks.size(); t++) {
            names[t] = "Cell_" + tracks.get(t).trackID;
            data[t] = MotilityMetrics.displacementTimeSeries(tracks.get(t), nFrames);
        }

        String path = dir + File.separator + "Displacement_Traces_" + baseName + ".csv";
        CsvWriter.writeTraces(path, names, data, frameIntervalMin);
        IJ.log("    Saved: Displacement_Traces_" + baseName + ".csv");
    }

    private void saveTrackSummary(String dir, String baseName,
                                   List<CellTrack> tracks, double intervalH) {
        String path = dir + File.separator + "Track_Summary_" + baseName + ".csv";
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));
            pw.println("Track_ID,N_Frames,Duration_frames,Duration_hours," +
                    "Total_Path_px,Net_Displacement_px,Directionality_Ratio," +
                    "Mean_Speed_px_per_frame");

            for (CellTrack track : tracks) {
                double path_len = MotilityMetrics.totalPathLength(track);
                double netDisp = MotilityMetrics.netDisplacement(track);
                double dirRatio = MotilityMetrics.directionalityRatio(track);
                double[] speeds = MotilityMetrics.instantaneousSpeed(track);

                double meanSpeed = 0;
                for (double s : speeds) meanSpeed += s;
                if (speeds.length > 0) meanSpeed /= speeds.length;

                pw.println(track.trackID + "," +
                        track.length() + "," +
                        track.duration() + "," +
                        String.format("%.2f", track.duration() * intervalH) + "," +
                        String.format("%.2f", path_len) + "," +
                        String.format("%.2f", netDisp) + "," +
                        String.format("%.4f", dirRatio) + "," +
                        String.format("%.4f", meanSpeed));
            }

            IJ.log("    Saved: Track_Summary_" + baseName + ".csv");
        } catch (IOException e) {
            IJ.log("    ERROR saving track summary: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
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
        return "Cell Tracking";
    }

    @Override
    public int getIndex() {
        return 7;
    }
}
