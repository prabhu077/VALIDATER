package com.validator.engine;

import java.util.*;
import java.util.regex.Pattern;

/**
 * DiscoveryEngine
 * ----------------
 * Automatically identifies sensitive fields in a dataset using two signals:
 *   1. Field-name rules   -> keyword matching on the column name itself
 *   2. Value-pattern rules -> regex matching on sample data values
 *
 * Covers exactly 7 sensitive types: EMAIL, PHONE, CARD, PAN, AADHAAR, PASSWORD, GST.
 * No Spring/web dependencies here on purpose - pure logic, easy to unit test.
 */
public class DiscoveryEngine {

    // ---------- 1. FIELD-NAME RULES ----------
    private static final Map<String, List<String>> NAME_KEYWORDS = new LinkedHashMap<>();
    static {
        NAME_KEYWORDS.put("EMAIL", List.of("email", "e_mail", "mail"));
        NAME_KEYWORDS.put("PHONE", List.of("phone", "mobile", "contact_no", "telephone", "contact_number"));
        NAME_KEYWORDS.put("CARD", List.of("card", "credit_card", "debit_card", "card_number"));
        NAME_KEYWORDS.put("PAN", List.of("pan", "pan_number", "pan_card"));
        NAME_KEYWORDS.put("AADHAAR", List.of("aadhaar", "aadhar", "uid_number"));
        NAME_KEYWORDS.put("PASSWORD", List.of("password", "pwd", "passcode"));
        NAME_KEYWORDS.put("GST", List.of("gst", "gstin", "gst_number", "tax_id"));
    }

    // ---------- 2. VALUE-PATTERN RULES ----------
    // Order matters: most specific patterns first, since detectByPattern()
    // stops at the first pattern that matches a given value.
    private static final Map<String, Pattern> VALUE_PATTERNS = new LinkedHashMap<>();
    static {
        VALUE_PATTERNS.put("EMAIL",
                Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[a-zA-Z]{2,}$"));
        VALUE_PATTERNS.put("PAN",
                Pattern.compile("^[A-Z]{5}\\d{4}[A-Z]$"));                     // e.g. ABCDE1234F
        VALUE_PATTERNS.put("GST",
                Pattern.compile("^\\d{2}[A-Z]{5}\\d{4}[A-Z]\\d[Z][A-Z\\d]$")); // e.g. 22ABCDE1234F1Z5
        VALUE_PATTERNS.put("AADHAAR",
                Pattern.compile("^\\d{4}\\s?\\d{4}\\s?\\d{4}$"));              // 12-digit, optionally spaced
        VALUE_PATTERNS.put("CARD",
                Pattern.compile("^\\d{13,16}$"));
        VALUE_PATTERNS.put("PHONE",
                Pattern.compile("^\\d{10}$"));

        // NOTE: PASSWORD is intentionally NOT given a value-pattern here.
        // It is free text with no reliable regex signature - pattern-matching it
        // would produce false positives. It relies on field-name detection only.
        // Documented as a known limitation in docs/limitations_report.md.
    }

    /**
     * Detects a sensitive type purely from the column name.
     * @return matched type, or null if no keyword matches.
     */
    public String detectByName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return null;
        }
        String normalized = columnName.toLowerCase().trim();

        for (Map.Entry<String, List<String>> entry : NAME_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (normalized.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * Detects a sensitive type by checking sample values against regex patterns.
     * Uses majority match across the samples so a single stray value
     * doesn't misclassify the whole column.
     *
     * @param sampleValues a handful of actual values from the column (e.g. 5-10 rows)
     * @return matched type, or null if no pattern matches the majority of samples.
     */
    public String detectByPattern(List<String> sampleValues) {
        if (sampleValues == null || sampleValues.isEmpty()) {
            return null;
        }

        Map<String, Integer> matchCounts = new LinkedHashMap<>();
        int validSamples = 0;

        for (String rawValue : sampleValues) {
            if (rawValue == null || rawValue.isBlank()) {
                continue; // skip nulls, don't let them count against a match
            }
            String value = rawValue.trim();
            validSamples++;

            for (Map.Entry<String, Pattern> entry : VALUE_PATTERNS.entrySet()) {
                if (entry.getValue().matcher(value).matches()) {
                    matchCounts.merge(entry.getKey(), 1, Integer::sum);
                    break; // stop at first pattern match per value (most specific wins, map order)
                }
            }
        }

        if (validSamples == 0) {
            return null;
        }

        String bestType = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : matchCounts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestType = entry.getKey();
            }
        }

        if (bestType != null && bestCount > (validSamples / 2.0)) {
            return bestType;
        }
        return null;
    }

    /**
     * Combines name-based and pattern-based detection into one final decision.
     * Priority: pattern-based wins on conflict (values don't lie, column
     * names can be misleading, e.g. "contact" holding an email).
     *
     * @param columnName   the column/field name
     * @param sampleValues a handful of sample values from that column
     * @return the detected sensitive type, or "NON_SENSITIVE" if nothing matched.
     */
    public String discoverFieldType(String columnName, List<String> sampleValues) {
        String byName = detectByName(columnName);
        String byPattern = detectByPattern(sampleValues);

        if (byPattern != null) {
            return byPattern;
        }
        if (byName != null) {
            return byName;
        }
        return "NON_SENSITIVE";
    }

    /**
     * Returns every sensitive type this engine is capable of detecting.
     * Useful for populating the Masking Policies screen and reports.
     */
    public Set<String> getSupportedTypes() {
        Set<String> types = new LinkedHashSet<>(NAME_KEYWORDS.keySet());
        types.addAll(VALUE_PATTERNS.keySet());
        return types;
    }

    /**
     * Runs discovery across an entire dataset.
     *
     * @param columnSamples map of column name -> list of sample values for that column
     * @return map of column name -> detected sensitive type
     */
    public Map<String, String> discoverFields(Map<String, List<String>> columnSamples) {
        Map<String, String> results = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : columnSamples.entrySet()) {
            String columnName = entry.getKey();
            List<String> samples = entry.getValue();
            results.put(columnName, discoverFieldType(columnName, samples));
        }
        return results;
    }
}
