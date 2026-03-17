package chronos.tracking;

import java.util.List;

/**
 * Container for all tracking results from a single time-lapse recording.
 */
public class TrackingResult {

    /** All detected cell tracks. */
    public final List<CellTrack> tracks;

    /** Total number of frames in the recording. */
    public final int nFrames;

    public TrackingResult(List<CellTrack> tracks, int nFrames) {
        this.tracks = tracks;
        this.nFrames = nFrames;
    }

    /** Number of tracks. */
    public int nTracks() {
        return tracks.size();
    }
}
