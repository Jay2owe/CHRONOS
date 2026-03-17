package chronos;

/**
 * Common interface for all CHRONOS pipeline modules.
 */
public interface Analysis {

    /**
     * Execute this analysis module on the given directory.
     *
     * @param directory path to the experiment folder containing TIF files
     * @return true if the analysis completed successfully, false if cancelled or failed
     */
    boolean execute(String directory);

    /**
     * Set whether this module should run without showing any GUI dialogs.
     */
    void setHeadless(boolean headless);

    /**
     * Set the number of parallel threads for processing.
     */
    void setParallelThreads(int threads);

    /**
     * Get the display name of this module.
     */
    String getName();

    /**
     * Get the index (execution order) of this module.
     * 1 = Pre-processing, 2 = ROI Definition, 3 = Signal Extraction,
     * 4 = Rhythm Analysis, 5 = Visualization, 6 = Export
     */
    int getIndex();
}
