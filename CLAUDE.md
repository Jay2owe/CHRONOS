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
- Built artifact: `target/CHRONOS-0.1.0-SNAPSHOT.jar`
- **Deploy to both Fiji installations:**
  - OneDrive: `~/OneDrive - Imperial College London/ImageJ/Fiji.app/plugins/`
  - Dropbox: `~/UK Dementia Research Institute Dropbox/Brancaccio Lab/Jamie/Fiji.app/plugins/`
- Launch: `ImageJ-win64.exe` inside either Fiji app folder.

## Build Constraints
- Maven parent: `pom-scijava:31.1.0`; output must stay Java 8 compatible.
- `-Denforcer.skip=true` is required.
- Commons Math 3 and Apache POI are `provided` scope (Fiji supplies at runtime).

## Git Rules
- **No Co-Author**: Do NOT add `Co-Authored-By` lines to commit messages.
- Git repo is `CHRONOS/` (inner directory, `master` branch).

## Module Index (execution order)
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
- `chronos/preprocessing/` — Crop, frame binning, motion correction (SIFT + cross-correlation), background subtraction, bleach/decay correction, spatial/temporal filters
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
- **Rayleigh test** — Phase coherence across ROIs

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

## Pre-processing Order
1. Crop (per-file interactive rectangle, saved to `.circadian/crop_regions.txt`)
2. Slice alignment (per-file rotation via user-drawn midline, saved to `.circadian/alignment_angles.txt`)
3. Frame binning (GroupedZProjector)
4. Motion correction (SIFT default, cross-correlation fallback)
5. Background subtraction (rolling ball, min projection, fixed ROI)
6. Bleach/decay correction (bi-exponential for fluorescent, sliding percentile for bioluminescence)
7. Spatial filter (Gaussian, median)
8. Temporal filter (moving average)

- Crop + alignment are combined into a single interactive pass per image (crop then align on same projection)
- All interactive steps (crop, align, ROI drawing) always run regardless of headless flag
- Previous crop/alignment/ROI values prompt reuse dialog before applying

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

## Important Notes
- SIFT registration requires image to be visible (show/hide automatically)
- Per-file crop regions replace old single-crop (legacy values auto-migrated)
- Auto-boundary detection uses Triangle threshold (best for small bright sample in large dark well)
- If user selects a `.circadian/` subdirectory, pipeline auto-navigates to experiment root
- Preprocessing settings dialog always shows (not suppressed by headless flag)
- Frame interval auto-detected from Incucyte timestamps and saved for re-runs
