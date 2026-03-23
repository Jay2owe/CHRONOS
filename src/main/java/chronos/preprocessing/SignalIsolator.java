package chronos.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;

/**
 * Runs user-provided ImageJ macro commands on a stack to isolate specific signal.
 * For example, converting to HSB and extracting a single channel.
 */
public class SignalIsolator {

    /**
     * Run user-provided ImageJ macro commands on a duplicate of the input stack.
     * Returns the resulting ImagePlus (whatever is active after macro execution).
     *
     * @param imp           input stack (not modified)
     * @param macroCommands ImageJ macro commands to run
     * @return the resulting ImagePlus after macro execution
     */
    public static ImagePlus isolate(ImagePlus imp, String macroCommands) {
        // Clone the stack so we don't destroy the original
        ImagePlus work = imp.duplicate();
        work.setTitle("isolating_" + imp.getTitle());
        work.show();

        try {
            // Run the macro commands
            IJ.runMacro(macroCommands);

            // Get whatever image is now active
            ImagePlus result = WindowManager.getCurrentImage();
            if (result == null) {
                IJ.log("  WARNING: Signal isolation macro produced no output. Using copy.");
                result = work;
            }

            // Clean up if result is different from work
            if (result != work) {
                work.close();
            }

            result.hide();
            return result;
        } catch (Exception e) {
            IJ.log("  WARNING: Signal isolation failed: " + e.getMessage());
            work.hide();
            return work;
        }
    }
}
