package com.validator.service;

import com.validator.engine.DiscoveryEngine;
import com.validator.util.MaskingUtil;
import com.validator.validation.MaskingInvariantChecker;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ScanService
 * -----------
 * The single place where a parsed dataset becomes a report: discovery, masked
 * preview and invariant validation happen here, once, so the controller no longer
 * carries two copies of the same review-building loop.
 *
 * Pure Java on purpose (no servlet types) so it can be unit-tested directly.
 *
 * Privacy rule: a ColumnReport NEVER holds a raw value of a sensitive column.
 * Sensitive columns expose the masked preview only; the invariant violations are
 * rule labels ("EMAIL_STRUCTURE: ..."), not data.
 */
@Service
public class ScanService {

    /** Non-blank values per column handed to the discovery engine. */
    public static final int SAMPLE_SIZE = 20;
    /** Non-blank values per sensitive column run through MaskingInvariantChecker. */
    public static final int INVARIANT_SAMPLES = 5;

    private final DiscoveryEngine engine = new DiscoveryEngine(); // stateless -> shareable

    public record ColumnReport(int index, String column, String type, boolean sensitive,
                               String signal, String preview,
                               int checked, int failed, List<String> violations,
                               int filled, int total, boolean review) implements Serializable {}

    public record ScanReport(String fileName, int rows, int columns,
                             int sensitiveCount, int reviewCount,
                             int checksTotal, int checksFailed, long elapsedMs,
                             List<ColumnReport> columnReports,
                             Map<String, Integer> typeCounts,
                             Map<String, String> types) implements Serializable {}

    public ScanReport scan(String fileName, Map<String, List<String>> data) {
        long start = System.nanoTime();

        List<ColumnReport> reports = new ArrayList<>(data.size());
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        int rows = data.isEmpty() ? 0 : data.values().iterator().next().size();
        int sensitive = 0, review = 0, checksTotal = 0, checksFailed = 0, idx = 0;

        for (Map.Entry<String, List<String>> e : data.entrySet()) {
            String column = e.getKey();
            List<String> values = e.getValue();

            List<String> nonBlank = new ArrayList<>();
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    nonBlank.add(v);
                    if (nonBlank.size() >= SAMPLE_SIZE) break;
                }
            }
            int filled = 0;
            for (String v : values) {
                if (v != null && !v.isBlank()) filled++;
            }

            String byName    = engine.detectByName(column);
            String byPattern = engine.detectByPattern(nonBlank);
            String type      = engine.discoverFieldType(column, nonBlank);
            boolean isSensitive = !"NON_SENSITIVE".equals(type);

            String signal;
            if (byName != null && byPattern != null) signal = "Name + value";
            else if (byPattern != null)              signal = "Value pattern";
            else if (byName != null)                 signal = "Column name";
            else                                     signal = "No match";

            String preview = "";
            int checked = 0, failed = 0;
            Set<String> violations = new LinkedHashSet<>();

            if (isSensitive) {
                sensitive++;
                typeCounts.merge(type, 1, Integer::sum);
                if (!nonBlank.isEmpty()) {
                    preview = MaskingUtil.mask(type, nonBlank.get(0));
                }
                int limit = Math.min(INVARIANT_SAMPLES, nonBlank.size());
                for (int i = 0; i < limit; i++) {
                    String raw = nonBlank.get(i);
                    checked++;
                    try {
                        var report = MaskingInvariantChecker.check(type, raw, MaskingUtil.mask(type, raw));
                        if (!report.passed()) {
                            failed++;
                            violations.addAll(report.violations());
                        }
                    } catch (RuntimeException ex) {
                        failed++;
                        violations.add("CHECKER_ERROR: " + ex.getClass().getSimpleName());
                    }
                }
            } else if (!nonBlank.isEmpty()) {
                String sample = nonBlank.get(0);
                preview = sample.length() > 40 ? sample.substring(0, 40) + "\u2026" : sample;
            }

            checksTotal  += checked;
            checksFailed += failed;

            // "Needs review": inferred from the header alone (values did not back it up),
            // or the masking invariants failed for at least one sample.
            boolean needsReview = isSensitive && ("Column name".equals(signal) || failed > 0);
            if (needsReview) review++;

            types.put(column, type);
            reports.add(new ColumnReport(++idx, column, type, isSensitive, signal, preview,
                    checked, failed, new ArrayList<>(violations),
                    filled, values.size(), needsReview));
        }

        long ms = (System.nanoTime() - start) / 1_000_000;
        return new ScanReport(fileName, rows, data.size(), sensitive, review,
                checksTotal, checksFailed, ms, reports, typeCounts, types);
    }
}
