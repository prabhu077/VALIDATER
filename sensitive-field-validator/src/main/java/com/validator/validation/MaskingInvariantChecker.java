package com.validator.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MaskingInvariantChecker
 * ─────────────────────────────────────────────────────────────────────────────
 * Validates that MaskingUtil's output satisfies non-leakage and structural
 * invariants rather than checking exact expected strings.
 *
 * WHY INVARIANTS INSTEAD OF EXPECTED VALUES?
 *   - Exact-value tests are brittle: changing masking style breaks every test.
 *   - Invariants express *what must always be true* regardless of style:
 *       "no raw digit after position 4 in a CARD mask"
 *       "EMAIL mask never exposes more than the first character before @"
 *       "GST mask always starts with the raw 2-digit state code"
 *
 * Usage:
 *   InvariantReport report = MaskingInvariantChecker.check("EMAIL", raw, masked);
 *   assertTrue(report.passed(), report.summary());
 */
public final class MaskingInvariantChecker {

    private MaskingInvariantChecker() {}

    // ── Non-leakage patterns: a PASSING masked value must NOT match these ────

    /**
     * Full email regex – if the masked output still matches a valid email,
     * the mask failed (it leaked the full address).
     */
    private static final Pattern FULL_EMAIL =
            Pattern.compile("^[\\w.+\\-]+@[\\w\\-]+\\.[a-zA-Z]{2,}$");

    /** A 10-consecutive-digit sequence indicates a raw phone number leaked. */
    private static final Pattern RAW_10_DIGITS = Pattern.compile("\\d{10}");

    /** 13–16 consecutive digits indicates a raw card number leaked. */
    private static final Pattern RAW_CARD_DIGITS = Pattern.compile("\\d{13,16}");

    /** Full PAN regex – must not appear in mask. */
    private static final Pattern FULL_PAN =
            Pattern.compile("[A-Z]{5}\\d{4}[A-Z]");

    /** Full Aadhaar – 12 consecutive digits (with or without spaces). */
    private static final Pattern FULL_AADHAAR =
            Pattern.compile("\\d{4}\\s?\\d{4}\\s?\\d{4}");

    /** Full GST regex. */
    private static final Pattern FULL_GST =
            Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z]\\d[Z][A-Z\\d]");

    // ── Structural must-have patterns ────────────────────────────────────────

    /** EMAIL mask: must contain exactly one '@'. */
    /** CARD mask: must end with exactly 4 digits. */
    private static final Pattern ENDS_WITH_4_DIGITS = Pattern.compile("\\d{4}$");

    /** AADHAAR mask: must end with exactly 4 digits. */
    private static final Pattern ENDS_WITH_4 = Pattern.compile("\\d{4}$");

    /** PAN mask: first 5 chars must match letters (state+entity). */
    private static final Pattern PAN_PREFIX_5_LETTERS = Pattern.compile("^[A-Z]{5}");

    /** GST mask: first 2 chars must be digits (state code must survive). */
    private static final Pattern GST_PREFIX_2_DIGITS = Pattern.compile("^\\d{2}");

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs all invariants applicable to {@code type} and returns a report.
     *
     * @param type   the sensitive type label (EMAIL, PHONE, CARD, PAN, AADHAAR, PASSWORD, GST)
     * @param raw    the original unmasked value
     * @param masked the output produced by MaskingUtil.mask(type, raw)
     * @return an {@link InvariantReport} detailing every invariant checked
     */
    public static InvariantReport check(String type, String raw, String masked) {
        List<String> violations = new ArrayList<>();
        List<String> passed    = new ArrayList<>();

        if (masked == null) {
            violations.add("INVARIANT[NULL_MASK]: masked output must never be null");
            return new InvariantReport(type, raw, masked, passed, violations);
        }

        switch (type) {

            case "EMAIL" -> {
                // 1. Non-leakage: full email must not survive masking
                assertNotMatch(FULL_EMAIL, masked,
                        "EMAIL_NON_LEAKAGE: full email address must not appear in mask",
                        passed, violations);
                // 2. Structural: '@' must still be present (partial reveal OK)
                assertTrue(masked.contains("@"),
                        "EMAIL_STRUCTURE: masked output must still contain '@'",
                        passed, violations);
                // 3. Non-leakage: local-part must be mostly hidden
                int atIdx = raw.indexOf('@');
                if (atIdx > 1) {
                    String localPart = raw.substring(1, atIdx); // chars after first
                    assertFalse(masked.contains(localPart),
                            "EMAIL_LOCAL_PART_HIDDEN: characters 2..@ of local part must not appear verbatim",
                            passed, violations);
                }
                // 4. Non-leakage: domain can survive (it's usually not PII)
            }

            case "PHONE" -> {
                // 1. Non-leakage: full 10-digit sequence must not appear
                assertNotMatch(RAW_10_DIGITS, masked,
                        "PHONE_NON_LEAKAGE: full 10-digit phone must not appear in mask",
                        passed, violations);
                // 2. Must contain at least one '*'
                assertTrue(masked.contains("*"),
                        "PHONE_MASKING_PRESENT: mask must contain at least one '*'",
                        passed, violations);
                // 3. Must end with the last 2 raw digits (reveal policy)
                if (raw != null && raw.length() >= 2) {
                    String expectedSuffix = raw.substring(raw.length() - 2);
                    assertTrue(masked.endsWith(expectedSuffix),
                            "PHONE_SUFFIX_REVEAL: last 2 digits must still appear as suffix",
                            passed, violations);
                }
            }

            case "CARD" -> {
                // 1. Non-leakage: full card digits must not appear
                String rawDigits = raw == null ? "" : raw.replaceAll("\\s", "");
                if (rawDigits.length() >= 13) {
                    assertFalse(masked.contains(rawDigits.substring(0, rawDigits.length() - 4)),
                            "CARD_NON_LEAKAGE: all but last 4 digits must be hidden",
                            passed, violations);
                }
                // 2. Structural: must end with last 4 digits
                assertMatch(ENDS_WITH_4_DIGITS, masked,
                        "CARD_LAST4_VISIBLE: mask must end with 4 digits",
                        passed, violations);
                // 3. Non-leakage: entire raw string must not survive
                assertNotMatch(RAW_CARD_DIGITS, masked,
                        "CARD_FULL_SEQUENCE_HIDDEN: full card digit sequence must not appear",
                        passed, violations);
            }

            case "PAN" -> {
                // 1. Non-leakage: full PAN must not appear
                assertNotMatch(FULL_PAN, masked,
                        "PAN_NON_LEAKAGE: full PAN must not appear in mask",
                        passed, violations);
                // 2. Structural: first 5 letters (structural prefix) must survive
                if (raw != null && raw.length() == 10) {
                    String prefix = raw.substring(0, 5);
                    assertTrue(masked.startsWith(prefix),
                            "PAN_PREFIX_VISIBLE: first 5 structural chars must survive in mask",
                            passed, violations);
                }
                // 3. Must contain mask characters
                assertTrue(masked.contains("*") || masked.contains("X"),
                        "PAN_MASKING_PRESENT: mask must contain '*' or 'X'",
                        passed, violations);
            }

            case "AADHAAR" -> {
                // 1. Non-leakage: full 12-digit Aadhaar sequence must not appear
                assertNotMatch(FULL_AADHAAR, masked,
                        "AADHAAR_NON_LEAKAGE: full Aadhaar must not appear in mask",
                        passed, violations);
                // 2. Structural: must end with last 4 digits
                String rawDigits2 = raw == null ? "" : raw.replaceAll("\\s", "");
                if (rawDigits2.length() >= 4) {
                    String last4 = rawDigits2.substring(rawDigits2.length() - 4);
                    assertTrue(masked.endsWith(last4),
                            "AADHAAR_LAST4_VISIBLE: last 4 digits must be visible in mask",
                            passed, violations);
                }
                // 3. Structural: must contain 'X' placeholder
                assertTrue(masked.contains("X"),
                        "AADHAAR_X_PLACEHOLDER: mask must use 'X' as placeholder",
                        passed, violations);
            }

            case "PASSWORD" -> {
                // 1. Non-leakage: raw value must not appear in mask at all
                if (raw != null && !raw.isBlank()) {
                    assertFalse(masked.contains(raw.trim()),
                            "PASSWORD_NON_LEAKAGE: raw password must not appear in mask",
                            passed, violations);
                }
                // 2. Structural: mask must be all '*'
                assertTrue(masked.matches("\\*+"),
                        "PASSWORD_ALL_STARS: password mask must be entirely '*' characters",
                        passed, violations);
                // 3. Length: must be exactly 8 stars (fixed-length policy hides real length)
                assertTrue(masked.length() == 8,
                        "PASSWORD_FIXED_LENGTH: password mask must be exactly 8 characters",
                        passed, violations);
            }

            case "GST" -> {
                // 1. Non-leakage: full GST must not appear
                assertNotMatch(FULL_GST, masked,
                        "GST_NON_LEAKAGE: full GSTIN must not appear in mask",
                        passed, violations);
                // 2. Structural: state code (first 2 digits) must survive
                assertMatch(GST_PREFIX_2_DIGITS, masked,
                        "GST_STATE_CODE_VISIBLE: first 2 digits (state code) must survive in mask",
                        passed, violations);
                // 3. Must contain mask characters
                assertTrue(masked.contains("*"),
                        "GST_MASKING_PRESENT: mask must contain '*'",
                        passed, violations);
            }

            default ->
                // NON_SENSITIVE or unknown – mask should equal raw value
                assertTrue(raw == null ? masked == null || masked.isEmpty() : masked.equals(raw),
                        "NON_SENSITIVE_PASSTHROUGH: non-sensitive value must pass through unchanged",
                        passed, violations);
        }

        return new InvariantReport(type, raw, masked, passed, violations);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void assertNotMatch(Pattern p, String value, String label,
                                       List<String> passed, List<String> violations) {
        if (p.matcher(value).find()) {
            violations.add("FAIL[" + label + "]");
        } else {
            passed.add("PASS[" + label + "]");
        }
    }

    private static void assertMatch(Pattern p, String value, String label,
                                    List<String> passed, List<String> violations) {
        if (p.matcher(value).find()) {
            passed.add("PASS[" + label + "]");
        } else {
            violations.add("FAIL[" + label + "]");
        }
    }

    private static void assertTrue(boolean condition, String label,
                                   List<String> passed, List<String> violations) {
        if (condition) {
            passed.add("PASS[" + label + "]");
        } else {
            violations.add("FAIL[" + label + "]");
        }
    }

    private static void assertFalse(boolean condition, String label,
                                    List<String> passed, List<String> violations) {
        assertTrue(!condition, label, passed, violations);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable report returned by {@link #check}.
     */
    public static final class InvariantReport {
        private final String       type;
        private final String       raw;
        private final String       masked;
        private final List<String> passedInvariants;
        private final List<String> violations;

        public InvariantReport(String type, String raw, String masked,
                               List<String> passedInvariants, List<String> violations) {
            this.type             = type;
            this.raw              = raw;
            this.masked           = masked;
            this.passedInvariants = List.copyOf(passedInvariants);
            this.violations       = List.copyOf(violations);
        }

        public boolean passed()                  { return violations.isEmpty(); }
        public String  type()                    { return type; }
        public String  raw()                     { return raw; }
        public String  masked()                  { return masked; }
        public List<String> passedInvariants()   { return passedInvariants; }
        public List<String> violations()         { return violations; }

        /** Human-readable one-liner for assertion messages. */
        public String summary() {
            if (passed()) {
                return String.format("OK [%s] raw='%s' -> masked='%s' | %d invariants passed",
                        type, raw, masked, passedInvariants.size());
            }
            return String.format("FAIL [%s] raw='%s' -> masked='%s' | violations: %s",
                    type, raw, masked, violations);
        }
    }
}
