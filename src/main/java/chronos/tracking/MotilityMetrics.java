package chronos.tracking;

import java.util.Arrays;

/**
 * Computes motility and morphological metrics from cell tracks.
 * <p>
 * Per-cell, per-timepoint metrics that can be fed into the rhythm analysis
 * pipeline to detect circadian oscillations in microglia behaviour.
 * <p>
 * Key metrics from MotiQ and circadian microglia literature:
 * - Speed: instantaneous displacement per frame
 * - Displacement: cumulative distance from starting position
 * - Mean-squared displacement (MSD): for diffusion characterisation
 * - Cell area over time: for morphological changes
 * - Directionality ratio: net displacement / total path length
 */
public class MotilityMetrics {

    private MotilityMetrics() { }

    /**
     * Computes instantaneous speed (pixels/frame) for each frame transition.
     * Returns array of length (track.length - 1).
     */
    public static double[] instantaneousSpeed(CellTrack track) {
        int n = track.length();
        if (n < 2) return new double[0];

        double[] speed = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            double dx = track.x[i + 1] - track.x[i];
            double dy = track.y[i + 1] - track.y[i];
            int dt = track.frames[i + 1] - track.frames[i];
            if (dt > 0) {
                speed[i] = Math.sqrt(dx * dx + dy * dy) / dt;
            }
        }
        return speed;
    }

    /**
     * Computes total path length (pixels) — sum of all displacements.
     */
    public static double totalPathLength(CellTrack track) {
        double total = 0;
        for (int i = 0; i < track.length() - 1; i++) {
            double dx = track.x[i + 1] - track.x[i];
            double dy = track.y[i + 1] - track.y[i];
            total += Math.sqrt(dx * dx + dy * dy);
        }
        return total;
    }

    /**
     * Computes net displacement from start to end position (pixels).
     */
    public static double netDisplacement(CellTrack track) {
        if (track.length() < 2) return 0;
        double dx = track.x[track.length() - 1] - track.x[0];
        double dy = track.y[track.length() - 1] - track.y[0];
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Directionality ratio: net displacement / total path length.
     * 1.0 = perfectly directed movement, 0.0 = pure random walk.
     */
    public static double directionalityRatio(CellTrack track) {
        double path = totalPathLength(track);
        if (path == 0) return 0;
        return netDisplacement(track) / path;
    }

    /**
     * Computes Mean Squared Displacement (MSD) for each lag.
     * MSD(tau) = <(x(t+tau) - x(t))^2 + (y(t+tau) - y(t))^2>
     * <p>
     * Useful for classifying motion: MSD ~ tau (diffusive), MSD ~ tau^2
     * (directed), MSD ~ tau^alpha with alpha < 1 (confined).
     *
     * @param track  the cell track
     * @param maxLag maximum lag in frames
     * @return MSD values for lags 1 to maxLag
     */
    public static double[] msd(CellTrack track, int maxLag) {
        int n = track.length();
        maxLag = Math.min(maxLag, n - 1);
        double[] msdValues = new double[maxLag];

        for (int lag = 1; lag <= maxLag; lag++) {
            double sum = 0;
            int count = 0;
            for (int i = 0; i < n - lag; i++) {
                // Only compute for frames that are exactly 'lag' apart
                if (track.frames[i + lag] - track.frames[i] == lag) {
                    double dx = track.x[i + lag] - track.x[i];
                    double dy = track.y[i + lag] - track.y[i];
                    sum += dx * dx + dy * dy;
                    count++;
                }
            }
            msdValues[lag - 1] = count > 0 ? sum / count : Double.NaN;
        }
        return msdValues;
    }

    /**
     * Generates per-frame speed time-series for the full recording duration.
     * Frames where the cell is not present are filled with NaN.
     * This can be fed directly into the rhythm analysis pipeline.
     *
     * @param track   the cell track
     * @param nFrames total number of frames in the recording
     * @return speed array of length nFrames (NaN where cell not detected)
     */
    public static double[] speedTimeSeries(CellTrack track, int nFrames) {
        double[] series = new double[nFrames];
        Arrays.fill(series, Double.NaN);

        for (int i = 0; i < track.length() - 1; i++) {
            int f1 = track.frames[i];
            int f2 = track.frames[i + 1];
            int dt = f2 - f1;
            if (dt > 0 && f1 < nFrames) {
                double dx = track.x[i + 1] - track.x[i];
                double dy = track.y[i + 1] - track.y[i];
                double speed = Math.sqrt(dx * dx + dy * dy) / dt;
                // Assign speed to the frame at the start of the interval
                series[f1] = speed;
            }
        }
        return series;
    }

    /**
     * Generates per-frame cell area time-series for the full recording.
     * Frames where the cell is not present are filled with NaN.
     *
     * @param track   the cell track
     * @param nFrames total number of frames in the recording
     * @return area array of length nFrames
     */
    public static double[] areaTimeSeries(CellTrack track, int nFrames) {
        double[] series = new double[nFrames];
        Arrays.fill(series, Double.NaN);

        for (int i = 0; i < track.length(); i++) {
            int f = track.frames[i];
            if (f < nFrames) {
                series[f] = track.area[i];
            }
        }
        return series;
    }

    /**
     * Generates per-frame displacement-from-origin time-series.
     * Distance from the cell's initial position at each frame.
     *
     * @param track   the cell track
     * @param nFrames total number of frames
     * @return displacement array (NaN where not detected)
     */
    public static double[] displacementTimeSeries(CellTrack track, int nFrames) {
        double[] series = new double[nFrames];
        Arrays.fill(series, Double.NaN);

        if (track.length() == 0) return series;
        double x0 = track.x[0];
        double y0 = track.y[0];

        for (int i = 0; i < track.length(); i++) {
            int f = track.frames[i];
            if (f < nFrames) {
                double dx = track.x[i] - x0;
                double dy = track.y[i] - y0;
                series[f] = Math.sqrt(dx * dx + dy * dy);
            }
        }
        return series;
    }
}
