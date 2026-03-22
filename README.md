# CHRONOS — Circadian Rhythm Analyzer

A Fiji/ImageJ plugin for automated analysis of circadian rhythms from longitudinal time-lapse recordings of SCN organotypic slices.

## Features

- **Two pipeline modes**: Guided (single interactive session) or Advanced (module-by-module)
- **7-module pipeline**: Pre-processing, ROI Definition, Signal Extraction, Rhythm Analysis, Visualization, Export, Cell Tracking
- **Incucyte import**: Auto-detects Incucyte image sequences (`VID_Well_Field_DDdHHhMMm.tif`), groups by series, assembles into stacks, and incrementally appends new frames
- **Automatic drift analysis**: Fast 4x-downsampled phase correlation scan (~2s) classifies drift pattern and recommends registration method
- **8 registration methods**: Automatic, Phase Correlation, Phase Correlation + Epoch Detection, Anchor-Patch Tracking, Cross-Correlation, SIFT, Descriptor-Based, Correct 3D Drift
- **Two-stage crop**: Broad crop before registration (loose, speeds up processing) + tight crop after registration (precise, on stabilized image)
- **Interactive registration approval**: View each registered stack, accept/retry/restart, apply same transforms or method to remaining
- **Pre-processing**: Alignment rotation, frame binning, background subtraction, bleach/decay correction, spatial and temporal filtering, pre-ROI filter presets, LUT application
- **ROI tools**: Grid-based ROI generation, dorsal/ventral splitting, automatic boundary detection, individual cells, custom regions
- **Rhythm analysis**: FFT, autocorrelation, Lomb-Scargle, cosinor fitting (standard/damped), wavelet CWT, JTK_CYCLE, CircaCompare, Rayleigh test
- **Detrending**: Linear/quadratic/cubic polynomial, Sinc filter (FFT bandpass), EMD, LOESS
- **Pre-ROI filter presets**: Dropdown with bundled filter macros (e.g., "Extract Green (Incucyte GFP)" — HSB saturation + double paraboloid + median)
- **Output LUT**: Apply a lookup table (Green, Fire, Cyan Hot, etc.) to corrected stacks before saving
- **Signal isolation**: Apply custom ImageJ macro (e.g., HSB channel split) for secondary signal extraction
- **Cell tracking**: TrackMate + StarDist AI detection with per-object-per-frame CSV (TrackID, centroid, mean/total intensity, area, perimeter)
- **Visualization**: Time-series plots, kymographs, spatial maps (phase/period/amplitude), polar plots, scalograms, drift trace plots, summary dashboard
- **Export**: Excel workbook with all results, consolidated CSVs

## Pipeline Modes

### Guided Pipeline
Walks through the entire workflow in one session:
1. Settings (reporter type, frame interval, preprocessing params)
2. Image discovery (scan corrected/assembled/raw, show status)
3. Assembly (Incucyte frame assembly with incremental append)
4. Registration (drift analysis → method recommendation → interactive approval)
5. ROI definition (interactive drawing on projections)
6. Signal extraction (per-ROI traces + optional whole-image trace)
7. Signal isolation (optional user macro for secondary extraction)
8. Cell tracking (optional TrackMate + StarDist)

### Advanced (Module-by-Module)
Select individual modules to run independently — useful for re-running specific analysis steps.

## Supported Reporter Types

| Reporter | Example | Bleach Correction |
|---|---|---|
| Bioluminescence | PER2::LUC | Sliding percentile |
| Fluorescent | CRY1-GFP, HIBA1-GFP, TMEM-EFYP | Bi-exponential |
| Calcium | jRCaMP, GCaMP | Bi-exponential |

## Installation

1. Build: `bash mvnw clean package -Denforcer.skip=true`
2. Copy `target/CHRONOS-0.1.0-SNAPSHOT.jar` to your Fiji `plugins/` folder
3. Restart Fiji
4. Run: **Plugins > CHRONOS > CHRONOS - Circadian Rhythm Analyzer**

### Optional: Cell Tracking
For cell tracking support, enable these Fiji update sites:
- TrackMate
- StarDist
- CSBDeep

## Usage

1. Select the experiment folder containing TIF stacks (or Incucyte individual frames)
2. Choose **Guided Pipeline** or **Advanced (Module-by-Module)**
3. Configure parameters and follow interactive prompts
4. Results are saved to `.circadian/` within the experiment folder

## Session Directory Structure

```
experiment_folder/
  *.tif                              (input stacks)
  .circadian/
    config.txt                       (persisted parameters)
    alignment_angles.txt             (per-file rotation angles)
    crop_regions.txt                 (per-file broad crop rectangles)
    tight_crop_regions.txt           (per-file tight crop rectangles)
    frame_intervals.txt              (per-file frame intervals)
    assembled/                       (Incucyte assembled stacks)
    corrected/                       (pre-processed stacks)
      registration_transforms_*.csv  (cached registration shifts)
      drift_analysis_*.csv           (drift pattern classification)
      drift_trace_*.csv              (per-frame shift trace)
    projections/                     (mean/max projections)
    ROIs/                            (ROI ZIP files)
    traces/                          (raw, dF/F, Z-score, isolated CSVs)
    rhythm/                          (FFT, autocorrelation, cosinor, wavelet results)
    visualizations/                  (plots, maps, drift traces, dashboard)
    exports/                         (Excel workbook, consolidated CSVs)
    tracking/                        (per-object-per-frame tracking CSVs)
```

## Requirements

- Fiji (ImageJ distribution) with bundled plugins
- Java 8+
- Dependencies (provided by Fiji): ImageJ 1.x, Commons Math 3, Apache POI
- Optional: TrackMate 7.14.0 + StarDist 1.2.1 (for cell tracking)
