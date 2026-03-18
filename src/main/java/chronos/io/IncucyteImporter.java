package chronos.io;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.FolderOpener;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports Incucyte time-lapse image sequences and assembles them into
 * ImageJ stacks sorted by time.
 *
 * Incucyte exports individual TIF files with the naming convention:
 *   {PREFIX}_{DD}d{HH}h{MM}m.tif
 * e.g. VID22_D2_1_00d00h30m.tif
 *
 * The prefix (everything before the timestamp) groups images into series.
 * Each group is assembled into a single time-ordered stack using ImageJ's
 * FolderOpener (Import > Image Sequence), which is optimised for this task.
 *
 * After successful assembly, individual frame files are deleted to save space.
 */
public class IncucyteImporter {

    /**
     * Pattern matching Incucyte timestamp filenames.
     * Group 1 = prefix (e.g. "VID22_D2_1")
     * Group 2 = days
     * Group 3 = hours
     * Group 4 = minutes
     */
    private static final Pattern INCUCYTE_PATTERN =
            Pattern.compile("^(.+?)_(\\d{2,})d(\\d{2})h(\\d{2})m\\.tiff?$", Pattern.CASE_INSENSITIVE);

    /**
     * Checks whether a directory contains Incucyte-style individual frame TIFs.
     * Returns true if at least 2 files match the Incucyte naming pattern.
     */
    public static boolean isIncucyteDirectory(File dir) {
        String[] files = dir.list();
        if (files == null) return false;
        int count = 0;
        for (String name : files) {
            if (INCUCYTE_PATTERN.matcher(name).matches()) {
                count++;
                if (count >= 2) return true;
            }
        }
        return false;
    }

    /**
     * Groups Incucyte TIF files by their prefix and sorts each group by time.
     * Returns an ordered map of prefix -> sorted file list.
     * Files that don't match the Incucyte pattern are ignored.
     */
    public static Map<String, List<IncucyteFrame>> groupAndSort(File dir) {
        String[] allFiles = dir.list(new FilenameFilter() {
            public boolean accept(File d, String name) {
                return INCUCYTE_PATTERN.matcher(name).matches();
            }
        });

        if (allFiles == null || allFiles.length == 0) {
            return Collections.emptyMap();
        }

        // Group by prefix
        Map<String, List<IncucyteFrame>> groups = new LinkedHashMap<String, List<IncucyteFrame>>();
        for (String filename : allFiles) {
            Matcher m = INCUCYTE_PATTERN.matcher(filename);
            if (m.matches()) {
                String prefix = m.group(1);
                int days = Integer.parseInt(m.group(2));
                int hours = Integer.parseInt(m.group(3));
                int minutes = Integer.parseInt(m.group(4));
                int totalMinutes = days * 24 * 60 + hours * 60 + minutes;

                List<IncucyteFrame> list = groups.get(prefix);
                if (list == null) {
                    list = new ArrayList<IncucyteFrame>();
                    groups.put(prefix, list);
                }
                list.add(new IncucyteFrame(filename, prefix, totalMinutes));
            }
        }

        // Sort each group by time
        for (List<IncucyteFrame> list : groups.values()) {
            Collections.sort(list, new Comparator<IncucyteFrame>() {
                public int compare(IncucyteFrame a, IncucyteFrame b) {
                    return Integer.compare(a.totalMinutes, b.totalMinutes);
                }
            });
        }

        return groups;
    }

    /**
     * Assembles all Incucyte frame groups into time-ordered stacks using
     * ImageJ's FolderOpener (Image Sequence). Each group's frames are
     * sorted by timestamp, opened as a sequence, calibrated, and saved
     * as a single TIFF stack.
     *
     * After successful assembly, the individual frame TIFs are deleted.
     *
     * @param sourceDir        the directory containing individual Incucyte TIF frames
     * @param outputDir        where to save the assembled stacks
     * @param frameIntervalMin the frame interval in minutes (fallback if timestamps can't derive it)
     * @return list of assembled stack filenames
     */
    public static List<String> assembleStacks(File sourceDir, String outputDir,
                                               double frameIntervalMin) {
        Map<String, List<IncucyteFrame>> groups = groupAndSort(sourceDir);
        List<String> assembledFiles = new ArrayList<String>();

        new File(outputDir).mkdirs();

        int groupNum = 0;
        for (Map.Entry<String, List<IncucyteFrame>> entry : groups.entrySet()) {
            groupNum++;
            String prefix = entry.getKey();
            List<IncucyteFrame> frames = entry.getValue();

            IJ.log("  Assembling group " + groupNum + "/" + groups.size()
                    + ": " + prefix + " (" + frames.size() + " frames)");

            // Derive actual interval from timestamps
            double intervalMin = frameIntervalMin;
            if (frames.size() >= 2) {
                double derived = (double)(frames.get(1).totalMinutes - frames.get(0).totalMinutes);
                if (derived > 0) {
                    intervalMin = derived;
                    IJ.log("    Frame interval from timestamps: " + intervalMin + " min");
                }
            }

            String outputPath = outputDir + File.separator + prefix + "_stack.tif";

            // Use FolderOpener.open(path, options) — ImageJ's built-in
            // Image Sequence importer. It sorts filenames lexicographically,
            // which matches our zero-padded timestamp format (DDdHHhMMm).
            // The filter is a regex applied to each filename.
            String options = "filter=" + prefix + " sort";

            ImagePlus imp = FolderOpener.open(sourceDir.getAbsolutePath(), options);

            if (imp == null || imp.getStackSize() == 0) {
                IJ.log("    ERROR: FolderOpener returned no images for " + prefix);
                // Try fallback filter with just the prefix
                imp = FolderOpener.open(sourceDir.getAbsolutePath(),
                        "filter=" + prefix + " sort");
                if (imp == null || imp.getStackSize() == 0) {
                    IJ.log("    ERROR: Fallback also failed. Skipping this group.");
                    continue;
                }
            }

            // Set dimensions: 1 channel, 1 z-slice, N time frames
            imp.setDimensions(1, 1, imp.getStackSize());

            // Set calibration
            Calibration cal = imp.getCalibration();
            cal.frameInterval = intervalMin * 60.0; // seconds
            cal.setTimeUnit("sec");

            // Save the assembled stack
            IJ.log("    Saving " + imp.getStackSize() + " frames...");
            FileSaver saver = new FileSaver(imp);
            saver.saveAsTiffStack(outputPath);
            imp.close();

            assembledFiles.add(prefix + "_stack.tif");
            IJ.log("    Saved: " + prefix + "_stack.tif");

            // Delete individual frame files
            int deleted = 0;
            for (IncucyteFrame frame : frames) {
                File f = new File(sourceDir, frame.filename);
                if (f.delete()) {
                    deleted++;
                }
            }
            IJ.log("    Cleaned up " + deleted + "/" + frames.size() + " individual frames");

            IJ.showProgress(groupNum, groups.size());
        }

        return assembledFiles;
    }

    /**
     * Updates existing assembled stacks with new Incucyte frames.
     * For each series with new frames: opens the existing stack, appends the
     * new frames in time order, and saves the updated stack.
     * For new series (no existing stack): assembles from scratch.
     * After successful update, individual frame files are deleted.
     *
     * @param sourceDir        directory containing new individual Incucyte TIF frames
     * @param outputDir        directory containing existing assembled stacks
     * @param frameIntervalMin fallback frame interval
     * @return list of updated/created stack filenames
     */
    public static List<String> updateStacks(File sourceDir, String outputDir,
                                             double frameIntervalMin) {
        Map<String, List<IncucyteFrame>> groups = groupAndSort(sourceDir);
        List<String> updatedFiles = new ArrayList<String>();

        for (Map.Entry<String, List<IncucyteFrame>> entry : groups.entrySet()) {
            String prefix = entry.getKey();
            List<IncucyteFrame> newFrames = entry.getValue();
            String stackPath = outputDir + File.separator + prefix + "_stack.tif";
            File existingStack = new File(stackPath);

            // Derive interval from new frame timestamps
            double intervalMin = frameIntervalMin;
            if (newFrames.size() >= 2) {
                double derived = (double)(newFrames.get(1).totalMinutes - newFrames.get(0).totalMinutes);
                if (derived > 0) intervalMin = derived;
            }

            if (existingStack.exists()) {
                // Append new frames to existing stack
                IJ.log("  Updating " + prefix + "_stack.tif with " + newFrames.size() + " new frame(s)...");
                ImagePlus existing = IJ.openImage(stackPath);
                if (existing == null) {
                    IJ.log("    ERROR: Could not open existing stack " + stackPath);
                    continue;
                }

                ij.ImageStack stack = existing.getStack();
                for (IncucyteFrame frame : newFrames) {
                    ImagePlus frameImp = IJ.openImage(
                            sourceDir.getAbsolutePath() + File.separator + frame.filename);
                    if (frameImp != null) {
                        stack.addSlice(frame.filename, frameImp.getProcessor());
                        frameImp.close();
                    }
                }

                existing.setDimensions(1, 1, stack.getSize());
                Calibration cal = existing.getCalibration();
                cal.frameInterval = intervalMin * 60.0;
                cal.setTimeUnit("sec");

                FileSaver saver = new FileSaver(existing);
                saver.saveAsTiffStack(stackPath);
                existing.close();

                updatedFiles.add(prefix + "_stack.tif");
                IJ.log("    Updated: " + stack.getSize() + " total frames");
            } else {
                // New series — assemble from scratch
                IJ.log("  Assembling new series: " + prefix + " (" + newFrames.size() + " frames)");
                String options = "filter=" + prefix + " sort";
                ImagePlus imp = FolderOpener.open(sourceDir.getAbsolutePath(), options);
                if (imp == null || imp.getStackSize() == 0) {
                    IJ.log("    ERROR: Could not assemble " + prefix);
                    continue;
                }
                imp.setDimensions(1, 1, imp.getStackSize());
                Calibration cal = imp.getCalibration();
                cal.frameInterval = intervalMin * 60.0;
                cal.setTimeUnit("sec");
                FileSaver saver = new FileSaver(imp);
                saver.saveAsTiffStack(stackPath);
                imp.close();
                updatedFiles.add(prefix + "_stack.tif");
                IJ.log("    Saved: " + prefix + "_stack.tif (" + imp.getStackSize() + " frames)");
            }

            // Delete individual frame files
            int deleted = 0;
            for (IncucyteFrame frame : newFrames) {
                File f = new File(sourceDir, frame.filename);
                if (f.delete()) deleted++;
            }
            IJ.log("    Cleaned up " + deleted + "/" + newFrames.size() + " individual frames");
        }

        return updatedFiles;
    }

    /**
     * Returns the list of non-Incucyte TIF files in the directory
     * (i.e. files that don't match the Incucyte timestamp pattern).
     */
    public static String[] getNonIncucyteTifs(File dir) {
        String[] files = dir.list(new FilenameFilter() {
            public boolean accept(File d, String name) {
                String lower = name.toLowerCase();
                if (!lower.endsWith(".tif") && !lower.endsWith(".tiff")) return false;
                return !INCUCYTE_PATTERN.matcher(name).matches();
            }
        });
        return files != null ? files : new String[0];
    }

    /**
     * Represents a single Incucyte frame with its parsed metadata.
     */
    public static class IncucyteFrame {
        public final String filename;
        public final String prefix;
        public final int totalMinutes;

        public IncucyteFrame(String filename, String prefix, int totalMinutes) {
            this.filename = filename;
            this.prefix = prefix;
            this.totalMinutes = totalMinutes;
        }
    }
}
