package chronos.preprocessing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads Named Filter .ijm macros bundled inside the plugin JAR.
 * Resources live at /named-filters/ in the JAR (src/main/resources/named-filters/).
 */
public final class NamedFilterLoader {

    private static final String RESOURCE_DIR = "/named-filters/";

    /** All bundled filter preset names (order matches the UI dropdown). */
    public static final String[] FILTER_NAMES = {
            "Extract Green (Incucyte GFP)",
            "Extract Red Channel",
            "HSB Saturation",
            "Extract Brightness (HSB)",
            "Background Subtraction Only",
            "Custom"
    };

    private NamedFilterLoader() {}

    /** Maps a display preset name to its resource filename. */
    private static String toResourceFilename(String presetName) {
        return presetName + ".ijm";
    }

    /**
     * Loads the macro content for a named filter preset from bundled JAR resources.
     *
     * @param presetName one of {@link #FILTER_NAMES}, or "Custom" (returns null)
     * @return the macro text, or null if the preset is "Custom" or not found
     */
    public static String loadFilterContent(String presetName) {
        if (presetName == null || "Custom".equals(presetName)) return null;
        String path = RESOURCE_DIR + toResourceFilename(presetName);
        InputStream is = NamedFilterLoader.class.getResourceAsStream(path);
        if (is == null) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) is = cl.getResourceAsStream(path.substring(1));
        }
        if (is == null) return null;
        try {
            return readStreamFully(is);
        } catch (IOException e) {
            return null;
        } finally {
            try { is.close(); } catch (IOException ignored) {}
        }
    }

    /** Java-8-safe stream read. */
    private static String readStreamFully(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int len;
        while ((len = is.read(tmp)) != -1) {
            buf.write(tmp, 0, len);
        }
        return buf.toString("UTF-8");
    }
}
