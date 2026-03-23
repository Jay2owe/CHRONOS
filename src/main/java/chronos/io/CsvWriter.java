package chronos.io;

import ij.IJ;

import java.io.*;

/**
 * Writes trace data to CSV files with Frame and Time_min columns prepended.
 * <p>
 * Format: Frame, Time_min, ROI_1_name, ROI_2_name, ..., ROI_N_name
 */
public class CsvWriter {

    /**
     * Writes trace data to a CSV file.
     *
     * @param path             output file path
     * @param headers          column headers for each ROI (length = nRois)
     * @param data             trace data [nRois][nFrames]
     * @param frameIntervalMin frame interval in minutes
     */
    public static void writeTraces(String path, String[] headers, double[][] data,
                                   double frameIntervalMin) {
        if (data == null || data.length == 0 || data[0].length == 0) {
            IJ.log("CsvWriter: No data to write.");
            return;
        }

        // Ensure parent directory exists
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        int nRois = data.length;
        int nFrames = data[0].length;

        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));

            // Header row
            StringBuilder sb = new StringBuilder();
            sb.append("Frame,Time_min");
            for (int r = 0; r < nRois; r++) {
                sb.append(",");
                sb.append(headers[r]);
            }
            pw.println(sb.toString());

            // Data rows
            for (int f = 0; f < nFrames; f++) {
                sb.setLength(0);
                sb.append(f + 1);
                sb.append(",");
                double timeMin = f * frameIntervalMin;
                sb.append(formatValue(timeMin));

                for (int r = 0; r < nRois; r++) {
                    sb.append(",");
                    sb.append(formatValue(data[r][f]));
                }
                pw.println(sb.toString());
            }

            IJ.log("CsvWriter: Saved " + nRois + " traces (" + nFrames + " frames) to " + path);
        } catch (IOException e) {
            IJ.log("CsvWriter: Error writing CSV: " + e.getMessage());
        } finally {
            if (pw != null) {
                pw.close();
            }
        }
    }

    /**
     * Writes a consolidated CSV with multiple trace types side by side.
     * <p>
     * For ROI traces: Frame, Time_min, ROI1_Raw, ROI1_DeltaFF, ROI1_Isolated, ROI2_Raw, ...
     * For whole-image traces: Frame, Time_min, Raw, DeltaFF, Isolated, Zscore
     *
     * @param path             output file path
     * @param roiNames         ROI names (null for whole-image mode)
     * @param traceTypes       array of trace type names (e.g., "Raw", "DeltaFF", "Isolated")
     * @param traceData        array of trace data arrays [typeIndex][roiIndex][frameIndex],
     *                         entries may be null if that trace type wasn't computed
     * @param nFrames          number of frames
     * @param frameIntervalMin frame interval in minutes
     */
    public static void writeConsolidatedTraces(String path, String[] roiNames,
                                               String[] traceTypes, double[][][] traceData,
                                               int nFrames, double frameIntervalMin) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Count available trace types
        int availableTypes = 0;
        for (double[][] td : traceData) {
            if (td != null) availableTypes++;
        }
        if (availableTypes == 0) return;

        int nRois = (roiNames != null) ? roiNames.length : 1;

        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new FileWriter(path)));

            // Build header
            StringBuilder sb = new StringBuilder("Frame,Time_min");
            for (int r = 0; r < nRois; r++) {
                String roiPrefix = (roiNames != null) ? roiNames[r] + "_" : "";
                for (int t = 0; t < traceTypes.length; t++) {
                    if (traceData[t] != null) {
                        sb.append(",").append(roiPrefix).append(traceTypes[t]);
                    }
                }
            }
            pw.println(sb.toString());

            // Write data rows
            for (int f = 0; f < nFrames; f++) {
                sb.setLength(0);
                sb.append(f + 1).append(",").append(formatValue(f * frameIntervalMin));
                for (int r = 0; r < nRois; r++) {
                    for (int t = 0; t < traceTypes.length; t++) {
                        if (traceData[t] != null) {
                            sb.append(",");
                            if (r < traceData[t].length && f < traceData[t][r].length) {
                                sb.append(formatValue(traceData[t][r][f]));
                            } else {
                                sb.append("NaN");
                            }
                        }
                    }
                }
                pw.println(sb.toString());
            }

            IJ.log("CsvWriter: Saved consolidated traces to " + path);
        } catch (IOException e) {
            IJ.log("CsvWriter: Error writing consolidated CSV: " + e.getMessage());
        } finally {
            if (pw != null) pw.close();
        }
    }

    /**
     * Formats a double value for CSV output. Handles NaN gracefully.
     */
    private static String formatValue(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Inf" : "-Inf";
        }
        // Use enough precision for scientific data
        return String.format("%.6g", value);
    }
}
