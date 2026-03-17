package chronos.tracking;

import ij.IJ;
import ij.ImagePlus;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.action.LabelImgExporter;
import fiji.plugin.trackmate.detection.DetectorKeys;
import fiji.plugin.trackmate.stardist.StarDistDetectorFactory;
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs TrackMate with StarDist detector on a time-lapse stack.
 * <p>
 * Unlike the IHF pipeline which uses Z/T swap for 3D segmentation,
 * CHRONOS uses TrackMate in its native time-lapse mode: StarDist
 * detects cells per frame, then LAP tracker links them across time.
 * <p>
 * Thread safety: TrackMate uses global state (WindowManager), so all
 * calls are serialized via a static lock.
 */
public class TrackMateRunner {

    private static final ReentrantLock TRACKMATE_LOCK = new ReentrantLock();

    private TrackMateRunner() { }

    /**
     * Runs TrackMate with StarDist on a time-lapse stack and returns
     * per-cell, per-frame spot positions and measurements.
     *
     * @param imp                the time-lapse stack (must be visible)
     * @param channel            target channel (1-based)
     * @param maxLinkingDistance  max distance to link spots between frames (pixels)
     * @param maxGapClosing      max gap (frames) for gap-closing
     * @param gapClosingDistance  max distance for gap-closing links (pixels)
     * @return tracking results, or null on failure
     */
    public static TrackingResult run(ImagePlus imp, int channel,
                                      double maxLinkingDistance,
                                      int maxGapClosing,
                                      double gapClosingDistance) {
        if (!StarDistDetector.isAvailable()) {
            IJ.log("    " + StarDistDetector.getAvailabilityMessage());
            return null;
        }

        TRACKMATE_LOCK.lock();
        try {
            return runUnsafe(imp, channel, maxLinkingDistance,
                    maxGapClosing, gapClosingDistance);
        } catch (Exception e) {
            IJ.log("    TrackMate failed: " + e.getClass().getSimpleName() +
                    " - " + e.getMessage());
            return null;
        } catch (LinkageError e) {
            IJ.log("    TrackMate runtime error: " + e.getMessage());
            return null;
        } finally {
            TRACKMATE_LOCK.unlock();
        }
    }

    private static TrackingResult runUnsafe(ImagePlus imp, int channel,
                                             double maxLinkingDistance,
                                             int maxGapClosing,
                                             double gapClosingDistance) {
        // Duplicate to avoid modifying the original
        ImagePlus dup = imp.duplicate();
        dup.setTitle("CHRONOS_TrackMate_temp");

        boolean wasVisible = dup.isVisible();
        if (!wasVisible) dup.show();

        try {
            Model model = new Model();
            model.setLogger(fiji.plugin.trackmate.Logger.VOID_LOGGER);

            Settings settings = new Settings(dup);
            settings.addAllAnalyzers();

            // StarDist detector
            settings.detectorFactory = new StarDistDetectorFactory();
            settings.detectorSettings = settings.detectorFactory.getDefaultSettings();
            settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL, Integer.valueOf(channel));

            // LAP tracker for linking cells across frames
            settings.trackerFactory = new SparseLAPTrackerFactory();
            Map<String, Object> trackerSettings = settings.trackerFactory.getDefaultSettings();
            trackerSettings.put("LINKING_MAX_DISTANCE", maxLinkingDistance);
            trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", gapClosingDistance);
            trackerSettings.put("MAX_FRAME_GAP", maxGapClosing);
            trackerSettings.put("ALLOW_GAP_CLOSING", maxGapClosing > 0);
            trackerSettings.put("ALLOW_TRACK_SPLITTING", false);
            trackerSettings.put("ALLOW_TRACK_MERGING", false);
            settings.trackerSettings = trackerSettings;

            TrackMate trackmate = new TrackMate(model, settings);

            if (!trackmate.checkInput()) {
                IJ.log("    TrackMate input check failed: " + trackmate.getErrorMessage());
                return null;
            }

            IJ.log("    Running StarDist detection + LAP tracking...");
            if (!trackmate.process()) {
                IJ.log("    TrackMate processing failed: " + trackmate.getErrorMessage());
                return null;
            }

            // Extract results
            int nTracks = model.getTrackModel().nTracks(true);
            int nSpots = model.getSpots().getNSpots(true);
            IJ.log("    Detected " + nSpots + " spots in " + nTracks + " tracks");

            return extractResults(model, imp.getNFrames());
        } finally {
            if (!wasVisible) dup.close();
            else dup.close();
        }
    }

    /**
     * Extracts tracking results from the TrackMate model into a structured format.
     */
    private static TrackingResult extractResults(Model model, int nFrames) {
        Set<Integer> trackIDs = model.getTrackModel().trackIDs(true);
        List<CellTrack> tracks = new ArrayList<CellTrack>();

        for (int trackID : trackIDs) {
            Set<Spot> spots = model.getTrackModel().trackSpots(trackID);

            // Sort spots by frame
            List<Spot> sortedSpots = new ArrayList<Spot>(spots);
            Collections.sort(sortedSpots, new Comparator<Spot>() {
                @Override
                public int compare(Spot a, Spot b) {
                    return Integer.compare(a.getFeature(Spot.FRAME).intValue(),
                            b.getFeature(Spot.FRAME).intValue());
                }
            });

            double[] x = new double[sortedSpots.size()];
            double[] y = new double[sortedSpots.size()];
            int[] frames = new int[sortedSpots.size()];
            double[] areas = new double[sortedSpots.size()];

            for (int i = 0; i < sortedSpots.size(); i++) {
                Spot spot = sortedSpots.get(i);
                x[i] = spot.getFeature(Spot.POSITION_X);
                y[i] = spot.getFeature(Spot.POSITION_Y);
                frames[i] = spot.getFeature(Spot.FRAME).intValue();

                // Area (radius-based estimate for StarDist spots)
                double radius = spot.getFeature(Spot.RADIUS);
                areas[i] = Math.PI * radius * radius;
            }

            tracks.add(new CellTrack(trackID, frames, x, y, areas));
        }

        return new TrackingResult(tracks, nFrames);
    }
}
