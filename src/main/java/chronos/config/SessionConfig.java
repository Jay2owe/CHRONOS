package chronos.config;

/**
 * Holds all user-configurable parameters for the CHRONOS pipeline.
 * Sensible defaults are provided for all fields.
 */
public class SessionConfig {

    // --- Recording Setup ---
    /** Reporter type: "Bioluminescence", "Fluorescent", or "Calcium" */
    public String reporterType = "Fluorescent";

    /** Frame interval in minutes */
    public double frameIntervalMin = 10.0;

    // --- Crop ---
    /** Broad crop: loose rectangle before registration to reduce image size */
    public boolean cropEnabled = true;
    /** Tight crop: precise rectangle after registration on the stabilized image */
    public boolean tightCropEnabled = true;
    /** Legacy crop rectangle (migrated to per-file format). */
    public int cropX = -1;
    public int cropY = -1;
    public int cropWidth = -1;
    public int cropHeight = -1;

    // --- Alignment ---
    public boolean alignEnabled = false;

    // --- Frame Binning ---
    public boolean binningEnabled = false;
    public int binFactor = 4;
    /** "Mean" or "Sum" */
    public String binMethod = "Mean";

    // --- Motion Correction ---
    public boolean motionCorrectionEnabled = true;
    /** "Automatic", "Phase Correlation", "Phase Correlation + Epoch Detection",
     *  "Anchor-Patch Tracking", "Cross-Correlation", "SIFT", "Descriptor-Based" */
    public String motionCorrectionMethod = "Automatic";
    /** "first", "mean", or "median" — used by correlation-based methods */
    public String motionCorrectionReference = "mean";
    /** Whether to cache and reuse registration transforms across runs */
    public boolean motionCorrectionCacheEnabled = true;

    // --- Pre-ROI Filter ---
    /** "None", or a named preset like "Extract Green (Incucyte GFP)" */
    public String preRoiFilter = "None";

    // --- LUT ---
    /** "None", "Green", "Fire", "Cyan Hot", "Grays", "Magenta", "Red", "Blue" */
    public String lutName = "None";

    // --- Background Subtraction ---
    /** "None", "Rolling Ball", "Fixed ROI", or "Minimum Projection" */
    public String backgroundMethod = "Rolling Ball";
    public double backgroundRadius = 50.0;

    // --- Bleach / Decay Correction ---
    /** "None", "Mono-exponential", "Bi-exponential", "Sliding Percentile", "Simple Ratio" */
    public String bleachMethod = "Bi-exponential";
    public int bleachPercentileWindow = 100;
    public double bleachPercentile = 8.0;

    // --- Spatial Filter ---
    /** "None", "Gaussian", or "Median" */
    public String spatialFilterType = "Gaussian";
    public double spatialFilterRadius = 2.0;

    // --- Temporal Filter ---
    /** "None", "Moving Average", or "Savitzky-Golay" */
    public String temporalFilterType = "Moving Average";
    public int temporalFilterWindow = 3;

    // --- Signal Extraction ---
    /** "Sliding Percentile", "First N Frames", "Whole-Trace Mean",
     *  "Whole-Trace Median", or "Exponential Fit" */
    public String f0Method = "Sliding Percentile";
    public int f0WindowSize = 100;
    public double f0Percentile = 8.0;
    public int f0NFrames = 10;
    public int cropStartFrame = 1;
    /** 0 means use all frames */
    public int cropEndFrame = 0;
    public boolean outputDeltaFF = true;
    public boolean outputZscore = false;

    // --- Rhythm Analysis ---
    public double periodMinHours = 18.0;
    public double periodMaxHours = 30.0;
    /** "None", "Linear", "Quadratic", or "Cubic" */
    public String detrendingMethod = "Linear";
    public boolean runFFT = true;
    public boolean runAutocorrelation = true;
    public boolean runLombScargle = false;
    public boolean runWavelet = false;
    public boolean runJTKCycle = false;
    /** "Standard" or "Damped" */
    public String cosinorModel = "Standard";
    public double significanceThreshold = 0.05;
    /** Run CircaCompare group comparison (requires D/V or multi-group ROIs) */
    public boolean runCircaCompare = false;

    // --- Cell Tracking ---
    /** Enable cell tracking module (requires TrackMate + StarDist) */
    public boolean trackingEnabled = false;
    /** Max linking distance between frames (pixels) */
    public double trackMaxLinkDistance = 30.0;
    /** Max gap for gap-closing (frames) */
    public int trackMaxGapFrames = 2;
    /** Max distance for gap-closing links (pixels) */
    public double trackGapClosingDistance = 40.0;
    /** Minimum track duration in frames (filter short tracks) */
    public int trackMinDurationFrames = 10;

    // --- Visualization ---
    public boolean vizTimeSeries = true;
    public boolean vizKymograph = true;
    public boolean vizPhaseMap = true;
    public boolean vizPeriodMap = true;
    public boolean vizAmplitudeMap = true;
    public boolean vizRasterPlot = true;
    public boolean vizPolarPlot = true;
    public boolean vizScalogram = true;

    // --- Export ---
    /** "PNG" or "TIFF" */
    public String exportImageFormat = "PNG";
    public boolean exportIncludeRawTraces = false;

    // --- Pipeline ---
    public boolean hideImageWindows = true;
    public boolean parallelProcessing = true;
    public int parallelThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    /**
     * Applies reporter-type-dependent defaults for bleach correction method.
     */
    public void applyReporterDefaults() {
        if ("Bioluminescence".equals(reporterType)) {
            bleachMethod = "Sliding Percentile";
        } else {
            bleachMethod = "Bi-exponential";
        }
    }
}
