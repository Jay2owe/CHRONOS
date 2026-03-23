package chronos.export;

import chronos.config.SessionConfig;
import chronos.export.SummaryStatistics.SummaryStats;
import chronos.rhythm.RhythmResult;

import ij.IJ;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Creates an Excel (.xlsx) workbook with 5 sheets summarising a CHRONOS analysis.
 * <p>
 * Uses Apache POI 3.17 (provided by Fiji at runtime).
 * <ul>
 *   <li>Sheet 1 "Experiment Summary" -- all parameters, date, file list</li>
 *   <li>Sheet 2 "Raw Traces" (optional) -- time + raw traces per file</li>
 *   <li>Sheet 3 "dF/F Traces" -- time + dF/F traces per file</li>
 *   <li>Sheet 4 "Rhythm Summary" -- one row per ROI per file with rhythm params</li>
 *   <li>Sheet 5 "Summary Statistics" -- grouped by region with mean/SEM/Rayleigh</li>
 * </ul>
 */
public class ExcelExporter {

    private ExcelExporter() { }

    /**
     * Exports a complete CHRONOS analysis to an Excel workbook.
     *
     * @param outputPath      full path for the .xlsx file
     * @param config          session config (for experiment parameters)
     * @param rawTraces       map of filename -> double[nRois][nFrames] raw traces (may be null)
     * @param rawHeaders      map of filename -> String[] ROI headers for raw traces
     * @param deltafTraces    map of filename -> double[nRois][nFrames] dF/F traces
     * @param deltafHeaders   map of filename -> String[] ROI headers for dF/F traces
     * @param rhythmResults   map of filename -> List of RhythmResult
     * @param includeRaw      whether to include the raw traces sheet
     */
    public static void export(String outputPath,
                              SessionConfig config,
                              Map<String, double[][]> rawTraces,
                              Map<String, String[]> rawHeaders,
                              Map<String, double[][]> deltafTraces,
                              Map<String, String[]> deltafHeaders,
                              Map<String, List<RhythmResult>> rhythmResults,
                              boolean includeRaw) {

        // Ensure parent directory exists
        File parent = new File(outputPath).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        Workbook wb = new XSSFWorkbook();
        try {
            // Create styles
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle numberStyle = createNumberStyle(wb, "0.000");
            CellStyle intStyle = createNumberStyle(wb, "0");
            CellStyle pctStyle = createNumberStyle(wb, "0.0");

            // Sheet 1: Experiment Summary
            createExperimentSummarySheet(wb, headerStyle, config,
                    rhythmResults != null ? rhythmResults.keySet() : Collections.<String>emptySet());

            // Sheet 2: Raw Traces (optional)
            if (includeRaw && rawTraces != null && !rawTraces.isEmpty()) {
                createTracesSheet(wb, "Raw Traces", headerStyle, numberStyle,
                        rawTraces, rawHeaders, config.frameIntervalMin);
            }

            // Sheet 3: dF/F Traces
            if (deltafTraces != null && !deltafTraces.isEmpty()) {
                createTracesSheet(wb, "dF-F Traces", headerStyle, numberStyle,
                        deltafTraces, deltafHeaders, config.frameIntervalMin);
            }

            // Sheet 4: Rhythm Summary
            if (rhythmResults != null && !rhythmResults.isEmpty()) {
                createRhythmSummarySheet(wb, headerStyle, numberStyle, rhythmResults);
            }

            // Sheet 5: Summary Statistics
            if (rhythmResults != null && !rhythmResults.isEmpty()) {
                // Flatten all results
                List<RhythmResult> allResults = new ArrayList<RhythmResult>();
                for (List<RhythmResult> list : rhythmResults.values()) {
                    allResults.addAll(list);
                }
                Map<String, SummaryStats> stats = SummaryStatistics.compute(allResults);
                createSummaryStatsSheet(wb, headerStyle, numberStyle, pctStyle, intStyle, stats);
            }

            // Write workbook
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(outputPath);
                wb.write(fos);
                IJ.log("ExcelExporter: Saved workbook to " + outputPath);
            } finally {
                if (fos != null) {
                    try { fos.close(); } catch (IOException ignored) { }
                }
            }
        } catch (Exception e) {
            IJ.log("ExcelExporter: Error creating workbook: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
        } finally {
            try { wb.close(); } catch (IOException ignored) { }
        }
    }

    // ---------------------------------------------------------------
    // Sheet 1: Experiment Summary
    // ---------------------------------------------------------------
    private static void createExperimentSummarySheet(Workbook wb, CellStyle headerStyle,
                                                      SessionConfig cfg, Set<String> fileNames) {
        Sheet sheet = wb.createSheet("Experiment Summary");
        int rowIdx = 0;

        // Title
        Row titleRow = sheet.createRow(rowIdx++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("CHRONOS Experiment Summary");
        titleCell.setCellStyle(headerStyle);

        rowIdx++; // blank row

        // Date
        Row dateRow = sheet.createRow(rowIdx++);
        dateRow.createCell(0).setCellValue("Date");
        dateRow.createCell(1).setCellValue(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        rowIdx++; // blank

        // Parameters section header
        Row paramHeader = sheet.createRow(rowIdx++);
        Cell phCell = paramHeader.createCell(0);
        phCell.setCellValue("Parameter");
        phCell.setCellStyle(headerStyle);
        Cell pvCell = paramHeader.createCell(1);
        pvCell.setCellValue("Value");
        pvCell.setCellStyle(headerStyle);

        // Write all config parameters
        rowIdx = writeParam(sheet, rowIdx, "Reporter Type", cfg.reporterType);
        rowIdx = writeParam(sheet, rowIdx, "Frame Interval (min)", String.valueOf(cfg.frameIntervalMin));
        rowIdx = writeParam(sheet, rowIdx, "Binning Enabled", String.valueOf(cfg.binningEnabled));
        rowIdx = writeParam(sheet, rowIdx, "Bin Factor", String.valueOf(cfg.binFactor));
        rowIdx = writeParam(sheet, rowIdx, "Bin Method", cfg.binMethod);
        rowIdx = writeParam(sheet, rowIdx, "Motion Correction", String.valueOf(cfg.motionCorrectionEnabled));
        rowIdx = writeParam(sheet, rowIdx, "Motion Ref", cfg.motionCorrectionReference);
        rowIdx = writeParam(sheet, rowIdx, "Background Method", cfg.backgroundMethod);
        rowIdx = writeParam(sheet, rowIdx, "Background Radius", String.valueOf(cfg.backgroundRadius));
        rowIdx = writeParam(sheet, rowIdx, "Bleach Method", cfg.bleachMethod);
        rowIdx = writeParam(sheet, rowIdx, "Spatial Filter", cfg.spatialFilterType);
        rowIdx = writeParam(sheet, rowIdx, "Spatial Filter Radius", String.valueOf(cfg.spatialFilterRadius));
        rowIdx = writeParam(sheet, rowIdx, "Temporal Filter", cfg.temporalFilterType);
        rowIdx = writeParam(sheet, rowIdx, "Temporal Filter Window", String.valueOf(cfg.temporalFilterWindow));
        rowIdx = writeParam(sheet, rowIdx, "F0 Method", cfg.f0Method);
        rowIdx = writeParam(sheet, rowIdx, "F0 Window Size", String.valueOf(cfg.f0WindowSize));
        rowIdx = writeParam(sheet, rowIdx, "F0 Percentile", String.valueOf(cfg.f0Percentile));
        rowIdx = writeParam(sheet, rowIdx, "Crop Start Frame", String.valueOf(cfg.cropStartFrame));
        rowIdx = writeParam(sheet, rowIdx, "Crop End Frame", String.valueOf(cfg.cropEndFrame));
        rowIdx = writeParam(sheet, rowIdx, "Period Min (h)", String.valueOf(cfg.periodMinHours));
        rowIdx = writeParam(sheet, rowIdx, "Period Max (h)", String.valueOf(cfg.periodMaxHours));
        rowIdx = writeParam(sheet, rowIdx, "Detrending", cfg.detrendingMethod);
        rowIdx = writeParam(sheet, rowIdx, "Cosinor Model", cfg.cosinorModel);
        rowIdx = writeParam(sheet, rowIdx, "Significance Threshold", String.valueOf(cfg.significanceThreshold));

        rowIdx++; // blank

        // File list
        Row filesHeader = sheet.createRow(rowIdx++);
        Cell fhCell = filesHeader.createCell(0);
        fhCell.setCellValue("Files Analysed");
        fhCell.setCellStyle(headerStyle);

        for (String name : fileNames) {
            Row fr = sheet.createRow(rowIdx++);
            fr.createCell(0).setCellValue(name);
        }

        // Auto-size columns
        autoSizeColumns(sheet, 2);
    }

    // ---------------------------------------------------------------
    // Sheet 2/3: Traces (raw or dF/F)
    // ---------------------------------------------------------------
    private static void createTracesSheet(Workbook wb, String sheetName,
                                           CellStyle headerStyle, CellStyle numberStyle,
                                           Map<String, double[][]> traces,
                                           Map<String, String[]> headers,
                                           double frameIntervalMin) {
        Sheet sheet = wb.createSheet(sheetName);
        int rowIdx = 0;

        for (Map.Entry<String, double[][]> entry : traces.entrySet()) {
            String fileName = entry.getKey();
            double[][] data = entry.getValue();
            String[] roiHeaders = headers != null ? headers.get(fileName) : null;

            if (data == null || data.length == 0) continue;
            int nRois = data.length;
            int nFrames = data[0].length;

            // File name header
            Row fileRow = sheet.createRow(rowIdx++);
            Cell fnCell = fileRow.createCell(0);
            fnCell.setCellValue(fileName);
            fnCell.setCellStyle(headerStyle);

            // Column headers
            Row hdr = sheet.createRow(rowIdx++);
            Cell frameH = hdr.createCell(0);
            frameH.setCellValue("Frame");
            frameH.setCellStyle(headerStyle);
            Cell timeH = hdr.createCell(1);
            timeH.setCellValue("Time_min");
            timeH.setCellStyle(headerStyle);
            for (int r = 0; r < nRois; r++) {
                Cell c = hdr.createCell(r + 2);
                String name = (roiHeaders != null && r < roiHeaders.length)
                        ? roiHeaders[r] : ("ROI_" + (r + 1));
                c.setCellValue(name);
                c.setCellStyle(headerStyle);
            }

            // Data rows
            for (int f = 0; f < nFrames; f++) {
                Row row = sheet.createRow(rowIdx++);
                Cell frameCell = row.createCell(0);
                frameCell.setCellValue(f + 1);

                Cell timeCell = row.createCell(1);
                timeCell.setCellValue(f * frameIntervalMin);
                timeCell.setCellStyle(numberStyle);

                for (int r = 0; r < nRois; r++) {
                    Cell dc = row.createCell(r + 2);
                    double val = data[r][f];
                    if (Double.isNaN(val) || Double.isInfinite(val)) {
                        dc.setCellValue("");
                    } else {
                        dc.setCellValue(val);
                        dc.setCellStyle(numberStyle);
                    }
                }
            }

            rowIdx++; // blank row between files
        }

        // Auto-size first 2 columns at minimum
        autoSizeColumns(sheet, 2);
    }

    // ---------------------------------------------------------------
    // Sheet 4: Rhythm Summary
    // ---------------------------------------------------------------
    private static void createRhythmSummarySheet(Workbook wb, CellStyle headerStyle,
                                                   CellStyle numberStyle,
                                                   Map<String, List<RhythmResult>> rhythmResults) {
        Sheet sheet = wb.createSheet("Rhythm Summary");
        int rowIdx = 0;

        // Header row
        String[] colNames = {
                "File", "ROI", "Period", "Phase_h", "Amplitude", "Mesor",
                "Damping_tau", "R_squared", "p_value", "Is_Rhythmic",
                "JTK_p_value", "RAIN_p_value", "RAIN_Peak_Shape"
        };
        Row hdr = sheet.createRow(rowIdx++);
        for (int c = 0; c < colNames.length; c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(colNames[c]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        for (Map.Entry<String, List<RhythmResult>> entry : rhythmResults.entrySet()) {
            String fileName = entry.getKey();
            for (RhythmResult r : entry.getValue()) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(fileName);
                row.createCell(1).setCellValue(r.roiName);
                setCellNumber(row, 2, r.period, numberStyle);
                setCellNumber(row, 3, r.phaseHours, numberStyle);
                setCellNumber(row, 4, r.amplitude, numberStyle);
                setCellNumber(row, 5, r.mesor, numberStyle);
                setCellNumber(row, 6, r.dampingTau, numberStyle);
                setCellNumber(row, 7, r.rSquared, numberStyle);
                setCellNumber(row, 8, r.pValue, numberStyle);
                row.createCell(9).setCellValue(r.isRhythmic ? "Yes" : "No");
                setCellNumber(row, 10,
                        r.jtkResult != null ? r.jtkResult.pValue : Double.NaN, numberStyle);
                setCellNumber(row, 11,
                        r.rainResult != null ? r.rainResult.pValue : Double.NaN, numberStyle);
                setCellNumber(row, 12,
                        r.rainResult != null ? r.rainResult.peakShape : Double.NaN, numberStyle);
            }
        }

        autoSizeColumns(sheet, colNames.length);
    }

    // ---------------------------------------------------------------
    // Sheet 5: Summary Statistics
    // ---------------------------------------------------------------
    private static void createSummaryStatsSheet(Workbook wb, CellStyle headerStyle,
                                                  CellStyle numberStyle, CellStyle pctStyle,
                                                  CellStyle intStyle,
                                                  Map<String, SummaryStats> stats) {
        Sheet sheet = wb.createSheet("Summary Statistics");
        int rowIdx = 0;

        String[] colNames = {
                "Region", "N_rhythmic", "N_total", "Pct_rhythmic",
                "Mean_period", "SEM_period",
                "Mean_amplitude", "SEM_amplitude",
                "Mean_phase_h", "SEM_phase_h",
                "Rayleigh_R", "Rayleigh_p"
        };
        Row hdr = sheet.createRow(rowIdx++);
        for (int c = 0; c < colNames.length; c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(colNames[c]);
            cell.setCellStyle(headerStyle);
        }

        for (SummaryStats s : stats.values()) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(s.regionName);
            setCellNumber(row, 1, s.nRhythmic, intStyle);
            setCellNumber(row, 2, s.nTotal, intStyle);
            setCellNumber(row, 3, s.pctRhythmic, pctStyle);
            setCellNumber(row, 4, s.meanPeriod, numberStyle);
            setCellNumber(row, 5, s.semPeriod, numberStyle);
            setCellNumber(row, 6, s.meanAmplitude, numberStyle);
            setCellNumber(row, 7, s.semAmplitude, numberStyle);
            setCellNumber(row, 8, s.meanPhase, numberStyle);
            setCellNumber(row, 9, s.semPhase, numberStyle);
            setCellNumber(row, 10, s.rayleigh != null ? s.rayleigh.vectorLength : Double.NaN, numberStyle);
            setCellNumber(row, 11, s.rayleigh != null ? s.rayleigh.pValue : Double.NaN, numberStyle);
        }

        autoSizeColumns(sheet, colNames.length);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static int writeParam(Sheet sheet, int rowIdx, String name, String value) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(name);
        row.createCell(1).setCellValue(value);
        return rowIdx + 1;
    }

    private static void setCellNumber(Row row, int col, double value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            cell.setCellValue("");
        } else {
            cell.setCellValue(value);
            cell.setCellStyle(style);
        }
    }

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static CellStyle createNumberStyle(Workbook wb, String format) {
        CellStyle style = wb.createCellStyle();
        DataFormat df = wb.createDataFormat();
        style.setDataFormat(df.getFormat(format));
        return style;
    }

    private static void autoSizeColumns(Sheet sheet, int numCols) {
        for (int i = 0; i < numCols; i++) {
            try {
                sheet.autoSizeColumn(i);
            } catch (Exception ignored) {
                // auto-size can fail in headless environments; not critical
            }
        }
    }
}
