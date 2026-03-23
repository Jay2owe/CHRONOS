# CHRONOS - Circadian Rhythm Analyzer

## Scope
- The workspace root is a wrapper folder. The buildable Fiji plugin module is `CHRONOS/`.
- Use this file for always-loaded project instructions.
- Use `CLAUDE_CONTEXT.md` (in the parent CHRONOS experiment folder) for deeper context.

## Build And Deploy
- Build from `CHRONOS/`.
- Required build command:
  - `export JAVA_HOME` to a JDK 17+ installation (e.g. Eclipse Adoptium)
  - `bash mvnw clean package -Denforcer.skip=true`
- Built artifact: `target/CHRONOS-<version>.jar` (currently `CHRONOS-0.4.2.jar`)
- **Deploy to both Fiji installations:**
  - OneDrive: `~/OneDrive - Imperial College London/ImageJ/Fiji.app/plugins/`
  - Dropbox: `~/UK Dementia Research Institute Dropbox/Brancaccio Lab/Jamie/Fiji.app/plugins/`
- Launch: `ImageJ-win64.exe` inside either Fiji app folder.

## Versioning
- Format: `MAJOR.MINOR.PATCH` (no `-SNAPSHOT` suffix — JARs deployed to Fiji must have clean version names)
  - **MAJOR** — new major feature or architectural rework
  - **MINOR** — substantial change to an existing feature
  - **PATCH** — bug fix or small tweak
- Version is set in `pom.xml` `<version>` tag. Bump it when making changes, choosing the right digit.

## Build Constraints
- Maven parent: `pom-scijava:31.1.0`; output must stay Java 8 compatible.
- `-Denforcer.skip=true` is required.
- Commons Math 3 and Apache POI are `provided` scope (Fiji supplies at runtime).

## Git Rules
- **No Co-Author**: Do NOT add `Co-Authored-By` lines to commit messages.
- Git repo is `CHRONOS/` (inner directory, `master` branch).

## Pipeline Modes
On launch, user chooses between:
- **Guided Pipeline** (`chronos.GuidedPipeline`) — single interactive session through all stages
- **Advanced (Module-by-Module)** — select individual modules to run independently

### Guided Pipeline Stages
1. Settings — reporter type, frame interval, preprocessing params
2. Image Discovery — scan corrected/assembled/raw, show status, flag mismatches
3. Assembly — detect/append Incucyte frames
4. Registration — drift scan, method recommendation, interactive approval per stack, restart/reapply options
5. ROI Definition — existing Module 2
6. Signal Extraction — existing Module 3 + whole-image trace option
7. Signal Isolation — filter preset macros, filtered stack + AVI export, LUT selection
8. Rhythm Analysis — FFT, autocorrelation, Lomb-Scargle, wavelet, JTK_CYCLE, cosinor, CircaCompare
9. Visualization — time-series plots, kymographs, spatial maps, polar plots, scalograms
10. Cell Tracking — TrackMate+StarDist with per-object-per-frame CSV
11. Export — Excel consolidation, CSV summary, summary statistics

### Module Index (Advanced mode, execution order)
- 1 = Pre-processing (`chronos.preprocessing.PreprocessingAnalysis`)
- 2 = ROI Definition (`chronos.roi.RoiDefinitionAnalysis`)
- 3 = Signal Extraction (`chronos.extraction.SignalExtractionAnalysis`)
- 4 = Rhythm Analysis (`chronos.rhythm.RhythmAnalysis`)
- 5 = Visualization (`chronos.visualization.VisualizationAnalysis`)
- 6 = Export (`chronos.export.ExportAnalysis`)
- 7 = Cell Tracking (`chronos.tracking.TrackingAnalysis`) — optional, requires TrackMate + StarDist

## Package Structure
- `chronos/` — Main entry point (`ChronosPipeline`, `Analysis` interface)
- `chronos/config/` — `SessionConfig`, `SessionConfigIO`
- `chronos/ui/` — `PipelineDialog`, `ToggleSwitch` (reused from IHF pipeline)
- `chronos/io/` — `CsvReader`, `CsvWriter`, `RoiIO`, `IncucyteImporter`
- `chronos/preprocessing/` — Crop, frame binning, motion correction (SIFT, cross-correlation, Correct 3D Drift + 5 others), background subtraction, bleach/decay correction, spatial/temporal filters, pre-ROI filter presets, LUT application
- `chronos/roi/` — ROI definition, grid generation, D/V split, auto-boundary detection
- `chronos/extraction/` — Trace extraction, baseline calculation, dF/F, Z-score
- `chronos/rhythm/` — FFT, autocorrelation, Lomb-Scargle, cosinor fitting, wavelet CWT, JTK_CYCLE, Rayleigh test, CircaCompare
- `chronos/tracking/` — TrackMate + StarDist cell tracking, motility metrics (speed, displacement, area, MSD)
- `chronos/visualization/` — Time-series plots, kymographs, spatial maps, polar plots, scalograms, dashboard
- `chronos/export/` — Excel export, CSV consolidation, summary statistics

## Key Dependencies (all provided scope)
- `net.imagej:ij` — ImageJ 1.x core
- `org.apache.commons:commons-math3:3.6.1` — Curve fitting, FFT, LevenbergMarquardt, LOESS
- `org.apache.poi:poi-ooxml:3.17` — Excel export
- `sc.fiji:TrackMate:7.14.0` — Cell tracking (optional, runtime-detected)
- `sc.fiji:TrackMate-StarDist:1.2.1` — AI cell detection (optional, runtime-detected)

## Rhythm Analysis Methods
- **FFT** — Fast Fourier Transform with Hann window
- **Autocorrelation** — Normalized ACF with rhythmicity index
- **Lomb-Scargle** — Non-uniform sampling, significance testing
- **Wavelet CWT** — Time-resolved period/amplitude/phase via Morlet wavelet
- **JTK_CYCLE** — Non-parametric rank-based rhythmicity test (Hughes et al. 2010)
- **Cosinor fitting** — Standard or damped sinusoidal model via LevenbergMarquardt
- **CircaCompare** — Statistical comparison of rhythm parameters between ROI groups
- **RAIN** — Asymmetric waveform detection via Mack-Wolfe umbrella test (Thaben & Westermark 2014)
- **Rayleigh test** — Phase coherence across ROIs
- **Per-Pixel Rhythmicity Maps** — Cosinor on every pixel, generates period/phase/amplitude/R²/p-value heatmaps

## Detrending Methods
- None, Linear, Quadratic, Cubic (polynomial)
- **Sinc Filter** — FFT-based ideal bandpass (pyBOAT-style)
- **EMD** — Empirical Mode Decomposition (adaptive, no assumptions about trend shape)
- **LOESS** — Locally weighted polynomial regression

## Cell Tracking (Module 7)
- Uses TrackMate with StarDist for AI cell detection per frame
- LAP tracker links cells across time with gap-closing
- Thread-safe via ReentrantLock (TrackMate uses global state)
- Computes per-cell motility time-series: speed, area, displacement, MSD
- Morphology metrics: circularity, ramification index, perimeter (for circadian microglia analysis)
- Morphology time-series saved as Track_Speed/Area/Circularity/Ramification CSVs in traces/
- Output to `.circadian/tracking/` as CSV trace files
- Requires TrackMate + StarDist + CSBDeep update sites in Fiji
- Degrades gracefully if not installed (runtime reflection check)

## Incucyte Import
- Auto-detects Incucyte individual frame TIFs matching `{PREFIX}_{DD}d{HH}h{MM}m.tif`
- Groups by prefix (e.g. `VID22_D2_1`), sorts chronologically, assembles into stacks
- Uses ImageJ's `FolderOpener` (Image Sequence) for efficient assembly
- Assembled stacks saved to `.circadian/assembled/`; individual frames deleted after assembly
- Runs automatically at the start of Module 1 (Pre-processing) when Incucyte frames are detected
- Frame interval auto-derived from timestamps
- **Incremental update**: If assembled stacks already exist and new Incucyte frames are found, new frames are appended to existing stacks (or new series assembled). Handles later time points added to the same experiment.

## Pre-processing Order
1. Slice alignment (per-file rotation via user-drawn midline, saved to `.circadian/alignment_angles.txt`)
2. Broad crop (per-file loose rectangle on the **rotated** projection, saved to `.circadian/crop_regions.txt`)
3. Frame binning (GroupedZProjector)
4. Motion correction (Automatic default — drift analysis + smart method selection)
5. Tight crop (per-file precise rectangle on the **registered** mean projection, saved to `.circadian/tight_crop_regions.txt`)
6. Background subtraction (rolling ball, min projection, fixed ROI)
7. Bleach/decay correction (bi-exponential for fluorescent, sliding percentile for bioluminescence)
8. Spatial filter (Gaussian, median)
9. Temporal filter (moving average)
10. Pre-ROI filter presets (bundled .ijm macros, e.g., "Extract Green (Incucyte GFP)")
11. LUT application (Green, Fire, Cyan Hot, etc.)

- Two-stage crop: broad crop before registration (loose, reduces image size for speed), tight crop after registration (precise, on stabilized image)
- Alignment runs first: user draws midline, then projection is rotated so broad crop is drawn on the aligned image
- Rotation uses enlarged canvas (bounding box of rotated rectangle) to prevent clipping
- Angle normalized to [-90°, 90°] so line drawing direction doesn't matter
- All interactive steps (crop, align, ROI drawing) always run regardless of headless flag
- Previous crop/alignment/ROI values prompt reuse dialog before applying
- Motion correction methods: Automatic, Phase Correlation, Phase Correlation + Epoch Detection, Anchor-Patch Tracking, Cross-Correlation, SIFT, Descriptor-Based, Correct 3D Drift, Correct 3D Drift (Manual Landmarks)
- Correct 3D Drift: computes drift on 8-bit greyscale (with calibration removed — critical for Incucyte), parses shifts from Log, applies to original stack. Robust to moving cells.
- Correct 3D Drift (Manual Landmarks): same as above but user draws a rectangle around stable landmarks (scratch marks, tissue edges) — only that region is used for cross-correlation, ignoring moving cells entirely. Falls back to automatic if no ROI drawn.
- Pre-ROI filter presets: bundled .ijm macros in `src/main/resources/named-filters/`. First preset: "Extract Green (Incucyte GFP)" — HSB saturation + double sliding paraboloid (r=50 + r=15) + median fill.
- LUT: applied after filtering, before save. Display only, does not modify pixel values.
- Registration transforms cached to `.circadian/corrected/registration_transforms_{base}.csv` for reuse
- Drift analysis outputs: `drift_analysis_{base}.csv`, `drift_trace_{base}.csv`, `drift_trace_{base}.png`

## Global Settings Dialog
Accessible via "Settings..." button on main pipeline dialog:
- Reporter Type, Frame Interval (auto-detected from Incucyte timestamps)
- Period search range (min/max hours), Significance threshold
- Hide Image Windows, Parallel Processing + thread count
- Output image format (PNG/TIFF)

## Reporter Types
- **Bioluminescence** (PER2::LUC) — sliding percentile bleach correction
- **Fluorescent** (CRY1-GFP, HIBA1-GFP, TMEM-EFYP) — bi-exponential bleach correction
- **Calcium** (jRCaMP, GCaMP) — bi-exponential bleach correction

## Data Conventions
- `.circadian/` session directory per experiment folder
- Subdirectories: `assembled/`, `corrected/`, `projections/`, `ROIs/`, `traces/`, `rhythm/`, `visualizations/`, `exports/`, `tracking/`
- Config persisted in `.circadian/config.txt` (key=value format)
- Per-file crop regions in `.circadian/crop_regions.txt`
- Per-file alignment angles in `.circadian/alignment_angles.txt`
- Per-file frame intervals in `.circadian/frame_intervals.txt`
- ROIs saved as `.zip` in `.circadian/ROIs/`
- Mean + max projections saved to `.circadian/projections/`
- ROI Definition always runs interactively (never headless)

## UI Conventions
- Use `PipelineDialog` for all dialogs.
- Use `ToggleSwitch` for boolean options — all preprocessing filters have toggle switches (when OFF, parameters are greyed out).
- `WaitForUserDialog` for interactive steps (crop, alignment, ROI drawing).
- Module descriptions shown under each toggle in main dialog (IHF-style `addHelpText`).
- Settings button in main dialog footer opens global settings.

## Visualization Output
- All plots rendered at 3x DPI scale (~300 DPI at print size)
- Time axes tick at 24h intervals (0, 24, 48, 72...) with light grey grid lines
- X-axis limits snap to nearest multiple of 24h

## Batch / Headless Mode
- Run from macro: `run("CHRONOS", "dir=/path/to/experiment mode=advanced modules=1,2,3,4,5")`
- Or guided: `run("CHRONOS", "dir=/path mode=guided")`
- Skips all interactive dialogs, forces `hideImageWindows=true`
- Modules specified as comma-separated numbers (1=Preprocessing, 2=ROI, etc.)
- Uses saved config from `.circadian/config.txt` if present

## SCN Sub-Region Detection
- `SubRegionDetector.detectCoreShell()` — k-means (k=2) on intensity within SCN boundary
- Core = brighter cluster (ventromedial), Shell = dimmer (dorsolateral)
- Toggle in ROI Definition dialog, requires auto-boundary detection first
- Generates SCN_Core and SCN_Shell polygon ROIs for CircaCompare group comparison

## Important Notes
- SIFT registration requires image to be visible (show/hide automatically)
- Per-file crop regions replace old single-crop (legacy values auto-migrated)
- Auto-boundary detection uses Triangle threshold (best for small bright sample in large dark well)
- If user selects a `.circadian/` subdirectory, pipeline auto-navigates to experiment root
- Preprocessing settings dialog always shows (not suppressed by headless flag)
- Frame interval auto-detected from Incucyte timestamps and saved for re-runs
- ROI and trace file lookups try the full name (with `_corrected`) first, then fall back to stripped name — handles both naming conventions
