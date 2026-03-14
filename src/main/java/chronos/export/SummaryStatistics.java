package chronos.export;

import chronos.rhythm.RayleighResult;
import chronos.rhythm.RayleighTest;
import chronos.rhythm.RhythmResult;

import java.util.*;

/**
 * Groups ROIs by region name prefix and computes descriptive statistics
 * (N, mean, SEM, min, max) for period, amplitude, and phase per group.
 * Also runs a Rayleigh test on phases within each group.
 */
public class SummaryStatistics {

    /**
     * Holds summary statistics for one region group.
     */
    public static class SummaryStats {
        public final String regionName;
        public final int nTotal;
        public final int nRhythmic;
        public final double pctRhythmic;

        public final double meanPeriod;
        public final double semPeriod;
        public final double minPeriod;
        public final double maxPeriod;

        public final double meanAmplitude;
        public final double semAmplitude;
        public final double minAmplitude;
        public final double maxAmplitude;

        public final double meanPhase;
        public final double semPhase;
        public final double minPhase;
        public final double maxPhase;

        public final RayleighResult rayleigh;

        public SummaryStats(String regionName, int nTotal, int nRhythmic,
                            double pctRhythmic,
                            double meanPeriod, double semPeriod,
                            double minPeriod, double maxPeriod,
                            double meanAmplitude, double semAmplitude,
                            double minAmplitude, double maxAmplitude,
                            double meanPhase, double semPhase,
                            double minPhase, double maxPhase,
                            RayleighResult rayleigh) {
            this.regionName = regionName;
            this.nTotal = nTotal;
            this.nRhythmic = nRhythmic;
            this.pctRhythmic = pctRhythmic;
            this.meanPeriod = meanPeriod;
            this.semPeriod = semPeriod;
            this.minPeriod = minPeriod;
            this.maxPeriod = maxPeriod;
            this.meanAmplitude = meanAmplitude;
            this.semAmplitude = semAmplitude;
            this.minAmplitude = minAmplitude;
            this.maxAmplitude = maxAmplitude;
            this.meanPhase = meanPhase;
            this.semPhase = semPhase;
            this.minPhase = minPhase;
            this.maxPhase = maxPhase;
            this.rayleigh = rayleigh;
        }
    }

    private SummaryStatistics() { }

    /**
     * Computes summary statistics grouped by region name prefix.
     * <p>
     * ROI names are grouped by prefix: everything before the last underscore-number
     * suffix is the region name. For example:
     * <ul>
     *   <li>"Dorsal_1", "Dorsal_2" -> region "Dorsal"</li>
     *   <li>"Grid_R1_C1", "Grid_R1_C2" -> region "Grid_R1"</li>
     *   <li>"Ventral" (no suffix) -> region "Ventral"</li>
     *   <li>"SCN_Outline" -> region "SCN_Outline"</li>
     * </ul>
     * If all ROI names are unique with no clear prefix grouping, each ROI
     * becomes its own group. An "All" group is always included.
     *
     * @param results list of RhythmResult for all ROIs (may span multiple files)
     * @return ordered map of region name to SummaryStats
     */
    public static Map<String, SummaryStats> compute(List<RhythmResult> results) {
        if (results == null || results.isEmpty()) {
            return new LinkedHashMap<String, SummaryStats>();
        }

        // Group results by region prefix
        Map<String, List<RhythmResult>> groups = new LinkedHashMap<String, List<RhythmResult>>();
        for (RhythmResult r : results) {
            String region = extractRegionPrefix(r.roiName);
            List<RhythmResult> list = groups.get(region);
            if (list == null) {
                list = new ArrayList<RhythmResult>();
                groups.put(region, list);
            }
            list.add(r);
        }

        Map<String, SummaryStats> output = new LinkedHashMap<String, SummaryStats>();

        // Compute stats for each group
        for (Map.Entry<String, List<RhythmResult>> entry : groups.entrySet()) {
            output.put(entry.getKey(), computeGroup(entry.getKey(), entry.getValue()));
        }

        // Always add an "All" group if more than one region
        if (groups.size() > 1 || !groups.containsKey("All")) {
            output.put("All", computeGroup("All", results));
        }

        return output;
    }

    /**
     * Extracts the region prefix from an ROI name.
     * Strategy: if name ends with _<digits>, strip that suffix.
     * Otherwise use the full name.
     */
    static String extractRegionPrefix(String roiName) {
        if (roiName == null || roiName.isEmpty()) return "Unknown";

        // Check for trailing _<digits> pattern
        int lastUnderscore = roiName.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < roiName.length() - 1) {
            String suffix = roiName.substring(lastUnderscore + 1);
            boolean allDigits = true;
            for (int i = 0; i < suffix.length(); i++) {
                if (!Character.isDigit(suffix.charAt(i))) {
                    allDigits = false;
                    break;
                }
            }
            if (allDigits) {
                return roiName.substring(0, lastUnderscore);
            }
        }

        return roiName;
    }

    private static SummaryStats computeGroup(String regionName, List<RhythmResult> results) {
        int nTotal = results.size();
        int nRhythmic = 0;

        List<Double> periods = new ArrayList<Double>();
        List<Double> amplitudes = new ArrayList<Double>();
        List<Double> phasesH = new ArrayList<Double>();
        List<Double> phasesRad = new ArrayList<Double>();

        for (RhythmResult r : results) {
            if (r.isRhythmic) {
                nRhythmic++;
                periods.add(r.period);
                amplitudes.add(r.amplitude);
                phasesH.add(r.phaseHours);
                phasesRad.add(r.phaseRad);
            }
        }

        double pctRhythmic = nTotal > 0 ? (100.0 * nRhythmic / nTotal) : 0;

        double meanPeriod = mean(periods);
        double semPeriod = sem(periods);
        double minPeriod = min(periods);
        double maxPeriod = max(periods);

        double meanAmplitude = mean(amplitudes);
        double semAmplitude = sem(amplitudes);
        double minAmplitude = min(amplitudes);
        double maxAmplitude = max(amplitudes);

        double meanPhase = mean(phasesH);
        double semPhase = sem(phasesH);
        double minPhase = min(phasesH);
        double maxPhase = max(phasesH);

        // Rayleigh test on rhythmic ROI phases
        RayleighResult rayleigh;
        if (phasesRad.isEmpty()) {
            rayleigh = new RayleighResult(0, 0, 1.0, Double.NaN, Double.NaN);
        } else {
            double[] phaseArr = new double[phasesRad.size()];
            for (int i = 0; i < phaseArr.length; i++) {
                phaseArr[i] = phasesRad.get(i);
            }
            rayleigh = RayleighTest.test(phaseArr);
        }

        return new SummaryStats(regionName, nTotal, nRhythmic, pctRhythmic,
                meanPeriod, semPeriod, minPeriod, maxPeriod,
                meanAmplitude, semAmplitude, minAmplitude, maxAmplitude,
                meanPhase, semPhase, minPhase, maxPhase,
                rayleigh);
    }

    private static double mean(List<Double> vals) {
        if (vals.isEmpty()) return Double.NaN;
        double sum = 0;
        for (double v : vals) sum += v;
        return sum / vals.size();
    }

    private static double sem(List<Double> vals) {
        if (vals.size() < 2) return Double.NaN;
        double m = mean(vals);
        double ssq = 0;
        for (double v : vals) {
            double diff = v - m;
            ssq += diff * diff;
        }
        double sd = Math.sqrt(ssq / (vals.size() - 1));
        return sd / Math.sqrt(vals.size());
    }

    private static double min(List<Double> vals) {
        if (vals.isEmpty()) return Double.NaN;
        double m = Double.MAX_VALUE;
        for (double v : vals) {
            if (v < m) m = v;
        }
        return m;
    }

    private static double max(List<Double> vals) {
        if (vals.isEmpty()) return Double.NaN;
        double m = -Double.MAX_VALUE;
        for (double v : vals) {
            if (v > m) m = v;
        }
        return m;
    }
}
