# CHRONOS — Circadian Rhythm Analyzer

A Fiji/ImageJ plugin for automated analysis of circadian rhythms from longitudinal time-lapse recordings of SCN organotypic slices.

## Features

- **6-module pipeline**: Pre-processing, ROI Definition, Signal Extraction, Rhythm Analysis, Visualization, Export
- **Incucyte import**: Auto-detects Incucyte image sequences (`VID_Well_Field_DDdHHhMMm.tif`), groups by series, and assembles into time-ordered stacks using ImageJ's FolderOpener
- **Motion correction**: SIFT-based registration (robust to intensity changes) or FFT cross-correlation (translation-only)
- **Pre-processing**: Crop, frame binning, background subtraction, bleach/decay correction, spatial and temporal filtering
- **ROI tools**: Grid-based ROI generation, dorsal/ventral splitting, automatic boundary detection
- **Rhythm analysis**: FFT, autocorrelation, Lomb-Scargle, cosinor fitting, wavelet CWT, Rayleigh test
- **Visualization**: Time-series plots, kymographs, spatial maps (phase/period/amplitude), polar plots, scalograms, summary dashboard
- **Export**: Excel workbook with all results, consolidated CSVs

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

## Usage

1. Select the experiment folder containing TIF stacks (or Incucyte individual frames)
2. Toggle which pipeline modules to run
3. Configure pre-processing parameters (reporter type, motion correction, filters, etc.)
4. Draw crop and ROI regions interactively when prompted
5. Results are saved to `.circadian/` within the experiment folder

## Session Directory Structure

```
experiment_folder/
  *.tif                          (input stacks)
  .circadian/
    config.txt                   (persisted parameters)
    assembled/                   (Incucyte assembled stacks)
    corrected/                   (pre-processed stacks)
    ROIs/                        (ROI ZIP files)
    traces/                      (raw, dF/F, Z-score CSVs)
    rhythm/                      (FFT, autocorrelation, cosinor, wavelet results)
    visualizations/              (PNG/TIFF output images)
    exports/                     (Excel workbook, consolidated CSVs)
```

## Requirements

- Fiji (ImageJ distribution) with bundled plugins
- Java 8+
- Dependencies (provided by Fiji): ImageJ 1.x, Commons Math 3, Apache POI
