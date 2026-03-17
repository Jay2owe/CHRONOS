package chronos.io;

import ij.IJ;
import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;

import java.io.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Thin wrapper around ImageJ's RoiDecoder / RoiEncoder for loading and saving
 * ROI zip files to {@code .circadian/ROIs/}.
 */
public class RoiIO {

    /**
     * Loads all ROIs from a zip file.
     *
     * @param path absolute path to the .zip file
     * @return array of ROIs, or empty array if file not found or unreadable
     */
    public static Roi[] loadRoisFromZip(String path) {
        File f = new File(path);
        if (!f.exists()) {
            IJ.log("RoiIO: File not found: " + path);
            return new Roi[0];
        }

        List<Roi> rois = new ArrayList<Roi>();
        ZipFile zf = null;
        try {
            zf = new ZipFile(f);
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.endsWith(".roi")) continue;

                InputStream is = zf.getInputStream(entry);
                byte[] bytes = readAllBytes(is);
                is.close();

                RoiDecoder decoder = new RoiDecoder(bytes, name);
                Roi roi = decoder.getRoi();
                if (roi != null) {
                    // Preserve the name from the zip entry (minus .roi extension)
                    if (roi.getName() == null || roi.getName().isEmpty()) {
                        String roiName = name;
                        if (roiName.endsWith(".roi")) {
                            roiName = roiName.substring(0, roiName.length() - 4);
                        }
                        roi.setName(roiName);
                    }
                    rois.add(roi);
                }
            }
        } catch (IOException e) {
            IJ.log("RoiIO: Error reading zip: " + e.getMessage());
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (IOException ignored) {}
            }
        }

        return rois.toArray(new Roi[0]);
    }

    /**
     * Saves an array of ROIs to a zip file.
     *
     * @param rois  the ROIs to save
     * @param path  absolute path for the output .zip file
     */
    public static void saveRoisToZip(Roi[] rois, String path) {
        if (rois == null || rois.length == 0) {
            IJ.log("RoiIO: No ROIs to save.");
            return;
        }

        // Ensure parent directory exists
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
            for (int i = 0; i < rois.length; i++) {
                Roi roi = rois[i];
                String name = roi.getName();
                if (name == null || name.isEmpty()) {
                    name = "ROI_" + (i + 1);
                }
                if (!name.endsWith(".roi")) {
                    name = name + ".roi";
                }

                zos.putNextEntry(new ZipEntry(name));
                // RoiEncoder writes directly to an OutputStream
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                RoiEncoder encoder = new RoiEncoder(baos);
                encoder.write(roi);
                baos.flush();
                zos.write(baos.toByteArray());
                zos.closeEntry();
            }
            IJ.log("RoiIO: Saved " + rois.length + " ROI(s) to " + path);
        } catch (IOException e) {
            IJ.log("RoiIO: Error writing zip: " + e.getMessage());
        } finally {
            if (zos != null) {
                try { zos.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Reads all bytes from an InputStream (Java 8 compatible).
     */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int n;
        while ((n = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, n);
        }
        buffer.flush();
        return buffer.toByteArray();
    }
}
