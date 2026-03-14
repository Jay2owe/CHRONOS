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
