# CHRONOS - Circadian Rhythm Analyzer

## Build And Deploy
- Build from this directory (`CHRONOS/`).
- Required build command:
  - `export JAVA_HOME="/c/Program Files/Java/jdk-25.0.2"`
  - `bash mvnw clean package -Denforcer.skip=true`
- Built artifact:
  - `target/CHRONOS-0.1.0-SNAPSHOT.jar`
- Deploy by copying the JAR into Fiji `plugins/`:
  - `C:/Users/jamie/OneDrive - Imperial College London/ImageJ/Fiji.app/plugins/`
- JAVA_HOME: `/c/Program Files/Java/jdk-25.0.2`

## Module Index (execution order)
- 1 = Pre-processing (`chronos.preprocessing.PreprocessingAnalysis`)
- 2 = ROI Definition (`chronos.roi.RoiDefinitionAnalysis`)
- 3 = Signal Extraction (`chronos.extraction.SignalExtractionAnalysis`)
- 4 = Rhythm Analysis (`chronos.rhythm.RhythmAnalysis`)
- 5 = Visualization (`chronos.visualization.VisualizationAnalysis`)
- 6 = Export (`chronos.export.ExportAnalysis`)

## Package Structure
- `chronos/` - Main entry point (`ChronosPipeline`, `Analysis` interface)
- `chronos/config/` - `SessionConfig`, `SessionConfigIO`
- `chronos/ui/` - `PipelineDialog`, `ToggleSwitch`
- `chronos/io/` - `CsvReader`, `CsvWriter`, `RoiIO`
- `chronos/preprocessing/` - Frame binning, motion correction, bleach correction, filters
- `chronos/roi/` - ROI definition, grid generation, auto-boundary detection
- `chronos/extraction/` - Trace extraction, baseline calculation
- `chronos/rhythm/` - FFT, autocorrelation, Lomb-Scargle, cosinor fitting, wavelet analysis
- `chronos/visualization/` - Time-series plots, kymographs, spatial maps, polar plots
- `chronos/export/` - Excel export, CSV consolidation

## Key Dependencies
- `net.imagej:ij` (ImageJ 1.x core)
- `org.apache.commons:commons-math3:3.6.1` (provided) - curve fitting, FFT
- `org.apache.poi:poi-ooxml:3.17` (provided) - Excel export
- Parent POM: `pom-scijava:31.1.0` (Java 8 target)

## Build Constraints
- Output must stay Java 8 compatible for Fiji.
- `-Denforcer.skip=true` is required.
- Commons Math 3 and Apache POI are `provided` scope (Fiji supplies at runtime).

## Data Conventions
- `.circadian/` session directory created per experiment folder
- Subdirectories: `corrected/`, `ROIs/`, `traces/`, `rhythm/`, `visualizations/`, `exports/`
- Config persisted in `.circadian/session_config.properties`
