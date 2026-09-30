package com.validator.validation;

import java.util.*;

/**
 * PrecisionRecallReporter
 * ─────────────────────────────────────────────────────────────────────────────
 * Computes standard IR metrics (Precision, Recall, F1, False Discovery Rate)
 * for the DiscoveryEngine across a labeled benchmark dataset.
 *
 * Terminology (binary sensitive vs. non-sensitive classification):
 *
 *   TP  – engine correctly labels a field SENSITIVE   (ground truth = sensitive)
 *   FP  – engine incorrectly labels NON_SENSITIVE as SENSITIVE  → false alarm
 *   TN  – engine correctly labels NON_SENSITIVE
 *   FN  – engine misses a SENSITIVE field (labels it NON_SENSITIVE)  → missed
 *
 *   Precision  = TP / (TP + FP)   → "of fields flagged, how many were truly sensitive?"
 *   Recall     = TP / (TP + FN)   → "of all truly sensitive fields, how many were caught?"
 *   F1         = 2 * P * R / (P + R)
 *   FDR        = FP / (FP + TP)   = 1 - Precision  → false discovery rate
 *
 * Type-level confusion: also tracks per-type accuracy (e.g. EMAIL confused as PHONE).
 *
 * Usage:
 *   PrecisionRecallReporter reporter = new PrecisionRecallReporter();
 *   reporter.record("email_col", "EMAIL",      "EMAIL");        // TP exact
 *   reporter.record("phone_col", "NON_SENSITIVE", "PHONE");     // FN
 *   reporter.record("city_col",  "NON_SENSITIVE", "NON_SENSITIVE"); // TN
 *   BenchmarkResult result = reporter.compute();
 */
public class PrecisionRecallReporter {

    /** Immutable snapshot of one engine prediction vs. ground truth. */
    public record Observation(String columnName,
                              String groundTruth,
                              String predicted) {
        public boolean isGroundTruthSensitive()  { return !"NON_SENSITIVE".equals(groundTruth); }
        public boolean isPredictedSensitive()    { return !"NON_SENSITIVE".equals(predicted); }

        public boolean isTP() { return isGroundTruthSensitive() && isPredictedSensitive(); }
        public boolean isFP() { return !isGroundTruthSensitive() && isPredictedSensitive(); }
        public boolean isTN() { return !isGroundTruthSensitive() && !isPredictedSensitive(); }
        public boolean isFN() { return isGroundTruthSensitive() && !isPredictedSensitive(); }
        public boolean isTypeCorrect() { return groundTruth.equals(predicted); }
    }

    private final List<Observation> observations = new ArrayList<>();

    /**
     * Records one column's prediction.
     *
     * @param columnName  the column being evaluated
     * @param groundTruth the labeled true type (e.g. "EMAIL" or "NON_SENSITIVE")
     * @param predicted   the type the engine assigned
     */
    public void record(String columnName, String groundTruth, String predicted) {
        observations.add(new Observation(columnName, groundTruth, predicted));
    }

    /** Clears all recorded observations (useful for multi-run benchmarks). */
    public void reset() { observations.clear(); }

    /** Returns an unmodifiable view of all recorded observations. */
    public List<Observation> observations() { return Collections.unmodifiableList(observations); }

    /**
     * Computes all metrics from the currently recorded observations.
     * Can be called multiple times as more observations are added.
     *
     * @return a {@link BenchmarkResult} containing all metrics
     */
    public BenchmarkResult compute() {
        int tp = 0, fp = 0, tn = 0, fn = 0;

        // Per-type confusion: predicted -> groundTruth -> count
        Map<String, Map<String, Integer>> confusion = new LinkedHashMap<>();

        for (Observation obs : observations) {
            if (obs.isTP()) tp++;
            else if (obs.isFP()) fp++;
            else if (obs.isTN()) tn++;
            else fn++;

            // Populate confusion matrix entry: (predicted type → actual type)
            confusion
                    .computeIfAbsent(obs.predicted(), k -> new LinkedHashMap<>())
                    .merge(obs.groundTruth(), 1, Integer::sum);
        }

        double precision = (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp);
        double recall    = (tp + fn) == 0 ? 1.0 : (double) tp / (tp + fn);
        double f1        = (precision + recall) == 0 ? 0.0
                : 2.0 * precision * recall / (precision + recall);
        double fdr       = (fp + tp) == 0 ? 0.0 : (double) fp / (fp + tp); // = 1 - precision

        double accuracy  = observations.isEmpty() ? 1.0
                : (double)(tp + tn) / observations.size();

        return new BenchmarkResult(tp, fp, tn, fn, precision, recall, f1, fdr, accuracy,
                Collections.unmodifiableMap(confusion),
                Collections.unmodifiableList(new ArrayList<>(observations)));
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable benchmark result snapshot.
     */
    public record BenchmarkResult(
            int    tp,
            int    fp,
            int    tn,
            int    fn,
            double precision,
            double recall,
            double f1,
            double fdr,
            double accuracy,
            Map<String, Map<String, Integer>> confusionMatrix,
            List<Observation> observations
    ) {
        /** Asserts minimum precision threshold – useful in CI gate checks. */
        public boolean meetsPrecisionThreshold(double threshold) { return precision >= threshold; }

        /** Asserts minimum recall threshold. */
        public boolean meetsRecallThreshold(double threshold)    { return recall >= threshold; }

        /** Generates a human-readable summary table. */
        public String summary() {
            return String.format(
                    "=== Benchmark Report ===%n" +
                    "  Observations : %d (TP=%d, FP=%d, TN=%d, FN=%d)%n" +
                    "  Precision    : %.3f%n" +
                    "  Recall       : %.3f%n" +
                    "  F1 Score     : %.3f%n" +
                    "  FDR          : %.3f%n" +
                    "  Accuracy     : %.3f%n" +
                    "  Confusion Matrix (predicted -> actual -> count):%n%s",
                    tp + fp + tn + fn, tp, fp, tn, fn,
                    precision, recall, f1, fdr, accuracy,
                    formatConfusion()
            );
        }

        private String formatConfusion() {
            if (confusionMatrix.isEmpty()) return "    (empty)\n";
            StringBuilder sb = new StringBuilder();
            confusionMatrix.forEach((predicted, actuals) ->
                actuals.forEach((actual, count) ->
                    sb.append(String.format("    predicted=%-14s actual=%-14s count=%d%n",
                            predicted, actual, count))
                )
            );
            return sb.toString();
        }
    }
}
