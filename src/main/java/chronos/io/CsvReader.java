package chronos.io;

import ij.IJ;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads trace CSV files produced by SignalExtractionAnalysis.
 * <p>
 * Expected format: Frame, Time_min, ROI_1_name, ROI_2_name, ..., ROI_N_name
 */
public class CsvReader {

    /**
     * Reads trace data from a CSV file.
     *
     * @param path       path to the CSV file
     * @param outHeaders array that will be populated with column headers (ROI names only,
     *                   excluding Frame and Time_min). Must be a String[] of length 1
     *                   that will be replaced with the actual headers array, or pass null
     *                   to skip header extraction. Use {@link #readTraces(String)} and
     *                   {@link #readHeaders(String)} separately for cleaner API.
     * @return double[nRois][nFrames] of trace values, or null if file not found
     */
    public static double[][] readTraces(String path, String[][] outHeaders) {
        File f = new File(path);
        if (!f.exists()) {
            IJ.log("CsvReader: File not found: " + path);
            return null;
        }

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));

            // Read header line
            String headerLine = br.readLine();
            if (headerLine == null) {
                IJ.log("CsvReader: Empty file: " + path);
                return null;
            }

            String[] allHeaders = headerLine.split(",");
            // Skip first 2 columns (Frame, Time_min)
            int nRois = allHeaders.length - 2;
            if (nRois <= 0) {
                IJ.log("CsvReader: No ROI columns found in: " + path);
                return null;
            }

            // Extract ROI headers
            String[] roiHeaders = new String[nRois];
            for (int i = 0; i < nRois; i++) {
                roiHeaders[i] = allHeaders[i + 2].trim();
            }
            if (outHeaders != null && outHeaders.length > 0) {
                outHeaders[0] = roiHeaders;
            }

            // Read data rows
            List<double[]> rows = new ArrayList<double[]>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                double[] vals = new double[nRois];
                for (int i = 0; i < nRois; i++) {
                    if (i + 2 < parts.length) {
                        vals[i] = parseDouble(parts[i + 2].trim());
                    } else {
                        vals[i] = Double.NaN;
                    }
                }
                rows.add(vals);
            }

            int nFrames = rows.size();
            // Transpose: rows are frames, we want [nRois][nFrames]
            double[][] traces = new double[nRois][nFrames];
            for (int f2 = 0; f2 < nFrames; f2++) {
                double[] row = rows.get(f2);
                for (int r = 0; r < nRois; r++) {
                    traces[r][f2] = row[r];
                }
            }

            return traces;
        } catch (IOException e) {
            IJ.log("CsvReader: Error reading CSV: " + e.getMessage());
            return null;
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Reads only the ROI headers (column names excluding Frame and Time_min).
     *
     * @param path path to the CSV file
     * @return array of ROI header names, or empty array if file not found
     */
    public static String[] readHeaders(String path) {
        File f = new File(path);
        if (!f.exists()) return new String[0];

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String headerLine = br.readLine();
            if (headerLine == null) return new String[0];

            String[] allHeaders = headerLine.split(",");
            int nRois = allHeaders.length - 2;
            if (nRois <= 0) return new String[0];

            String[] roiHeaders = new String[nRois];
            for (int i = 0; i < nRois; i++) {
                roiHeaders[i] = allHeaders[i + 2].trim();
            }
            return roiHeaders;
        } catch (IOException e) {
            return new String[0];
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Parses a double, handling NaN and Inf strings gracefully.
     */
    private static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        if ("NaN".equalsIgnoreCase(s)) return Double.NaN;
        if ("Inf".equalsIgnoreCase(s)) return Double.POSITIVE_INFINITY;
        if ("-Inf".equalsIgnoreCase(s)) return Double.NEGATIVE_INFINITY;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
