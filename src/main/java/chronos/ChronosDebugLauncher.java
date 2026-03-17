package chronos;

import ij.ImageJ;

/**
 * Debug launcher for running CHRONOS outside Fiji.
 * Starts a minimal ImageJ environment and launches the pipeline.
 */
public class ChronosDebugLauncher {

    public static void main(String[] args) {
        // Start ImageJ
        new ImageJ();

        // Launch the CHRONOS pipeline
        ChronosPipeline pipeline = new ChronosPipeline();
        pipeline.run("");
    }
}
