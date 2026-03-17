package chronos.tracking;

import net.imglib2.img.display.imagej.ImgPlusViews;

import java.util.Map;

/**
 * Runtime availability detection for TrackMate-StarDist.
 * Uses reflection to check if the required classes are present
 * without failing at compile time when they're not installed.
 */
public class StarDistDetector {

    private static Boolean available;
    private static String message = "";

    private StarDistDetector() { }

    public static boolean isAvailable() {
        ensureProbed();
        return available.booleanValue();
    }

    public static String getAvailabilityMessage() {
        ensureProbed();
        return message;
    }

    private static synchronized void ensureProbed() {
        if (available != null) return;

        try {
            Class.forName("fiji.plugin.trackmate.stardist.StarDistDetectorFactory");
            Class.forName("fiji.plugin.trackmate.TrackMate");
            available = Boolean.TRUE;
            message = "TrackMate-StarDist is available.";
        } catch (ClassNotFoundException e) {
            available = Boolean.FALSE;
            message = "TrackMate-StarDist not found. Enable CSBDeep, StarDist, " +
                    "and TrackMate update sites in Fiji (Help > Update... > Manage Update Sites).";
        } catch (LinkageError e) {
            available = Boolean.FALSE;
            message = "TrackMate-StarDist version incompatible: " + e.getMessage();
        }
    }
}
