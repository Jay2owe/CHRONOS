# CHRONOS - Circadian Rhythm Analyzer

## Scope
- The workspace root is a wrapper folder. The buildable Fiji plugin module is `CHRONOS/`.
- Use this file for always-loaded project instructions.
- Use `CLAUDE_CONTEXT.md` (in the parent CHRONOS experiment folder) for deeper context.

## Build And Deploy
- Build from `CHRONOS/`.
- Required build command:
  - `export JAVA_HOME="/c/Program Files/Java/jdk-25.0.2"`
  - `bash mvnw clean package -Denforcer.skip=true`
- Built artifact: `target/CHRONOS-0.1.0-SNAPSHOT.jar`
- **Deploy to OneDrive Fiji** (not Dropbox):
  - `C:/Users/jamie/OneDrive - Imperial College London/ImageJ/Fiji.app/plugins/`
- Launch: `ImageJ-win64.exe` inside the Fiji app folder.

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

## Package Structure
- `chronos/` — Main entry point (`ChronosPipeline`, `Analysis` interface)
- `chronos/config/` — `SessionConfig`, `SessionConfigIO`
- `chronos/ui/` — `PipelineDialog`, `ToggleSwitch` (reused from IHF pipeline)
- `chronos/io/` — `CsvReader`, `CsvWriter`, `RoiIO`
- `chronos/preprocessing/` — Crop, frame binning, motion correction (SIFT + cross-correlation), background subtraction, bleach/decay correction, spatial/temporal filters
- `chronos/roi/` — ROI definition, grid generation, D/V split, auto-boundary detection
- `chronos/extraction/` — Trace extraction, baseline calculation, dF/F, Z-score
- `chronos/rhythm/` — FFT, autocorrelation, Lomb-Scargle, cosinor fitting, wavelet CWT, Rayleigh test
- `chronos/visualization/` — Time-series plots, kymographs, spatial maps, polar plots, scalograms, dashboard
- `chronos/export/` — Excel export, CSV consolidation, summary statistics

## Key Dependencies (all provided scope)
- `net.imagej:ij` — ImageJ 1.x core
- `org.apache.commons:commons-math3:3.6.1` — Curve fitting, FFT, LevenbergMarquardt
- `org.apache.poi:poi-ooxml:3.17` — Excel export

## Pre-processing Order
1. Crop (interactive rectangle on first image, saved and reused)
2. Frame binning (GroupedZProjector)
3. Motion correction (SIFT default, cross-correlation fallback)
4. Background subtraction (rolling ball, min projection, fixed ROI)
5. Bleach/decay correction (bi-exponential for fluorescent, sliding percentile for bioluminescence)
6. Spatial filter (Gaussian, median)
7. Temporal filter (moving average)

## Reporter Types
- **Bioluminescence** (PER2::LUC) — sliding percentile bleach correction
- **Fluorescent** (CRY1-GFP, HIBA1-GFP, TMEM-EFYP) — bi-exponential bleach correction
- **Calcium** (jRCaMP, GCaMP) — bi-exponential bleach correction

## Data Conventions
- `.circadian/` session directory per experiment folder
- Subdirectories: `corrected/`, `ROIs/`, `traces/`, `rhythm/`, `visualizations/`, `exports/`
- Config persisted in `.circadian/config.txt` (key=value format)
- ROIs saved as `.zip` in `.circadian/ROIs/`
- ROI Definition always runs interactively (never headless)

## UI Conventions
- Use `PipelineDialog` for all dialogs.
- Use `ToggleSwitch` for boolean options.
- `WaitForUserDialog` for interactive steps (crop, ROI drawing).

## Important Notes
- SIFT registration requires image to be visible (show/hide automatically)
- Crop region is saved in config and reused across re-runs
- Auto-boundary detection uses Triangle threshold (best for small bright sample in large dark well)
