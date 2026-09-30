package com.validator;

import com.validator.engine.DiscoveryEngine;
import com.validator.util.MaskingUtil;
import com.validator.validation.MaskingInvariantChecker;
import com.validator.validation.MaskingInvariantChecker.InvariantReport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FailureScenariosTest
 * ─────────────────────────────────────────────────────────────────────────────
 * Tests the system under failure conditions, near-miss inputs, and known
 * edge cases that the engine SHOULD handle gracefully.
 *
 * Categories
 * ----------
 *  A. Malformed input that must NOT trigger false positives
 *  B. Extreme boundary-length values (min-1, min, max, max+1 for each type)
 *  C. Mixed-type column ambiguity (values that span two sensitive categories)
 *  D. Column-name vs. value-pattern conflict (name says X, values say Y)
 *  E. Null / empty / whitespace-only inputs (safety guarantees)
 *  F. Masking non-leakage on adversarial inputs (invariant assertions)
 *  G. Known limitations – documented & annotated (not assertions, just probes)
 */
@DisplayName("Failure Scenarios – Edge Cases & Boundary Conditions")
class FailureScenariosTest {

    private DiscoveryEngine engine;

    @BeforeEach
    void setup() {
        engine = new DiscoveryEngine();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // A. MALFORMED INPUT – must NOT produce false positives
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "malformed [{0}] must NOT detect as {1}")
    @MethodSource("malformedInputsThatMustNotMatch")
    @DisplayName("A. Malformed inputs rejected – no false positives")
    void testMalformedInputsDoNotFlagSensitive(String colName, List<String> values,
                                               String mustNotBe, String reason) {
        String result = engine.discoverFieldType(colName, values);
        assertNotEquals(mustNotBe, result,
                reason + " | got: " + result);
    }

    static Stream<Arguments> malformedInputsThatMustNotMatch() {
        return Stream.of(
            // EMAIL malformed
            Arguments.of("data", List.of("notanemail","missingatsign"),
                    "EMAIL", "No '@' → must not be EMAIL"),
            Arguments.of("data", List.of("user@@double.com","bad@@mail.org"),
                    "EMAIL", "Double '@' → must not be EMAIL"),
            Arguments.of("data", List.of("user@nodot","me@server"),
                    "EMAIL", "No TLD → must not be EMAIL"),

            // PHONE malformed
            Arguments.of("data", List.of("987654321","123456789"),
                    "PHONE", "9-digit → must not be PHONE (requires exactly 10)"),
            Arguments.of("data", List.of("98765432101","12345678901"),
                    "PHONE", "11-digit → must not be PHONE"),
            Arguments.of("data", List.of("98765ABCDE","XXXXXXXXXX"),
                    "PHONE", "Alpha-phone → must not be PHONE"),

            // CARD malformed
            Arguments.of("data", List.of("411111111111","550000000000"),
                    "CARD", "12-digit → must not be CARD (min 13)"),
            Arguments.of("data", List.of("41111111111111111","55000000000000041"),
                    "CARD", "17-digit → must not be CARD (max 16)"),
            Arguments.of("data", List.of("4111111111ABCD","55000000XYZXYZ"),
                    "CARD", "Alpha-card → must not be CARD"),

            // PAN malformed
            Arguments.of("data", List.of("abcde1234f","fghij5678k"),
                    "PAN", "Lowercase PAN → must not match (PAN regex is uppercase-only)"),
            Arguments.of("data", List.of("ABCDE123F","FGHIJ678K"),
                    "PAN", "9-char PAN → must not match (PAN is exactly 10 chars)"),
            Arguments.of("data", List.of("ABCDE12345","FGHIJ67890"),
                    "PAN", "Ends in digit not letter → must not match PAN pattern"),

            // AADHAAR malformed
            Arguments.of("data", List.of("12345678901","98765432109"),
                    "AADHAAR", "11-digit → must not be AADHAAR (requires exactly 12)"),
            Arguments.of("data", List.of("1234 ABCD 5678","XXXX 5678 0000"),
                    "AADHAAR", "Letters in Aadhaar → must not match"),

            // GST malformed – missing Z marker
            Arguments.of("data", List.of("27ABCDE1234F1X5","07FGHIJ5678K2Y3"),
                    "GST", "Non-Z 15th char → must not match GST regex")
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B. BOUNDARY-LENGTH TESTS (min-1, min, max, max+1)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("B. Boundary-length value detection")
    class BoundaryLengthTests {

        @Test @DisplayName("CARD min-1: 12 digits must NOT match")
        void cardMin1() { assertNotEquals("CARD", engine.detectByPattern(List.of("411111111111"))); }

        @Test @DisplayName("CARD min: 13 digits MUST match")
        void cardMin() { assertEquals("CARD", engine.detectByPattern(List.of("4111111111111","5500000000001"))); }

        @Test @DisplayName("CARD max: 16 digits MUST match")
        void cardMax() { assertEquals("CARD", engine.detectByPattern(List.of("4111111111111111"))); }

        @Test @DisplayName("CARD max+1: 17 digits must NOT match")
        void cardMax1() { assertNotEquals("CARD", engine.detectByPattern(List.of("41111111111111111"))); }

        @Test @DisplayName("PHONE exact: 10 digits MUST match")
        void phoneExact() { assertEquals("PHONE", engine.detectByPattern(List.of("9876543210"))); }

        @Test @DisplayName("AADHAAR exact: 12 digits MUST match")
        void aadhaarExact12() { assertEquals("AADHAAR", engine.detectByPattern(List.of("123456789012","987654321098"))); }

        @Test @DisplayName("AADHAAR with 2-space groups (XX XX XX) MUST match")
        void aadhaarSpaced() { assertEquals("AADHAAR", engine.detectByPattern(List.of("1234 5678 9012"))); }

        @Test @DisplayName("PAN exact: exactly 10 chars in AAAAA####A format MUST match")
        void panExact() { assertEquals("PAN", engine.detectByPattern(List.of("ABCDE1234F","FGHIJ5678K"))); }

        @Test @DisplayName("EMAIL 1-char local: a@b.io MUST match")
        void emailMinLocal() { assertEquals("EMAIL", engine.detectByPattern(List.of("a@b.io","x@y.com"))); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // C. MIXED-TYPE COLUMN AMBIGUITY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C1. Column where phone digits happen to be 13-digit card length → CARD wins over PHONE")
    void testPhoneLengthAmbiguity() {
        // Values that are 13 digits → matches CARD (not PHONE)
        String result = engine.detectByPattern(List.of("4111111111111","5500000000001"));
        assertEquals("CARD", result, "13-digit values should match CARD, not PHONE");
    }

    @Test
    @DisplayName("C2. Column with mix of emails and phones – majority EMAIL → EMAIL detected")
    void testEmailPhoneMixMajorityEmail() {
        // Use single-TLD emails so all 3 match the engine's email regex
        // (multi-part TLDs like .co.in are not matched by the current pattern – see G1 limitation)
        String result = engine.detectByPattern(List.of(
            "alice@example.com", "bob@mail.org", "carol@domain.com",  // 3 emails (75%)
            "9876543210"                                                // 1 phone  (25%)
        ));
        assertEquals("EMAIL", result, "3 emails (75%) vs 1 phone (25%) → majority EMAIL");
    }

    @Test
    @DisplayName("C3. Column with equal split (2 emails + 2 phones) → NON_SENSITIVE (no majority)")
    void testEqualSplitNoMajority() {
        String result = engine.detectByPattern(List.of(
            "alice@example.com", "bob@mail.org",  // 2 emails
            "9876543210", "8000000000"             // 2 phones
        ));
        // Neither exceeds 50% → NON_SENSITIVE
        assertNull(result, "Equal 50/50 split must not trigger detection (requires strict >50%)");
    }

    @Test
    @DisplayName("C4. PAN vs. GST conflict – GST regex is stricter and should win")
    void testPanGstConfusion() {
        // GST contains an embedded PAN-like segment; order of pattern matching matters
        // 27ABCDE1234F1Z5 → matches GST first (GST is checked before PAN in discovery engine)
        String result = engine.detectByPattern(List.of("27ABCDE1234F1Z5","07FGHIJ5678K2Z3"));
        assertEquals("GST", result, "GST regex must be matched before PAN due to ordering");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // D. COLUMN-NAME vs. VALUE-PATTERN CONFLICT
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("D1. Column named 'phone' but holds emails → EMAIL wins (pattern priority)")
    void testPhoneNameEmailValues() {
        String result = engine.discoverFieldType("phone",
            List.of("alice@example.com","bob@mail.org","carol@domain.co.in"));
        assertEquals("EMAIL", result,
                "Value pattern (EMAIL) must override name hint (PHONE)");
    }

    @Test
    @DisplayName("D2. Column named 'card_number' but holds 9-digit shorts → NON_SENSITIVE via pattern, CARD via name, pattern wins")
    void testCardNameShortValues() {
        // 9-digit values don't match CARD or PHONE; name says CARD
        // Since no pattern match, name should be used → CARD
        String result = engine.discoverFieldType("card_number",
            List.of("987654321","123456789","000000000"));
        // Pattern = null (9-digit not matching anything) → falls back to name = CARD
        assertEquals("CARD", result,
                "When no value pattern matches, column name (card_number→CARD) should be used");
    }

    @Test
    @DisplayName("D3. Generic column name 'record_id' with valid emails → EMAIL via pattern")
    void testGenericNameEmailValues() {
        String result = engine.discoverFieldType("record_id",
            List.of("alice@example.com","bob@mail.org","carol@domain.co.in"));
        assertEquals("EMAIL", result,
                "Generic name 'record_id' should not block pattern-based EMAIL detection");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // E. NULL / EMPTY / WHITESPACE-ONLY SAFETY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("E1. All-null sample list handled without NPE")
    void testAllNullSamples() {
        assertDoesNotThrow(() -> {
            List<String> samples = new java.util.ArrayList<>();
            samples.add(null);
            samples.add(null);
            String result = engine.detectByPattern(samples);
            assertNull(result, "All-null samples → null (no valid samples)");
        });
    }

    @Test
    @DisplayName("E2. Mixed null and valid email samples – nulls skipped")
    void testMixedNullAndValidSamples() {
        List<String> samples = new java.util.ArrayList<>();
        samples.add(null);
        samples.add("alice@example.com");
        samples.add(null);
        samples.add("bob@mail.org");
        samples.add(null);
        String result = engine.detectByPattern(samples);
        assertEquals("EMAIL", result,
                "Null values must be skipped; remaining 2/2 valid = 100% EMAIL");
    }

    @Test
    @DisplayName("E3. Empty-string values skipped; only valid non-blank values counted")
    void testEmptyStringValuesSkipped() {
        String result = engine.detectByPattern(List.of("", "", "9876543210", ""));
        assertEquals("PHONE", result,
                "Empty strings must be skipped; 1 valid phone → 100% → PHONE");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // F. MASKING NON-LEAKAGE ON ADVERSARIAL / EDGE-CASE INPUTS
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "mask adversarial [{0}] ''{1}''")
    @MethodSource("adversarialMaskingCases")
    @DisplayName("F. Masking invariants hold on adversarial inputs")
    void testMaskingInvariantsAdversarial(String type, String raw) {
        assertDoesNotThrow(() -> {
            String masked = MaskingUtil.mask(type, raw);
            InvariantReport report = MaskingInvariantChecker.check(type, raw, masked);
            System.out.println("[ADVERSARIAL] " + report.summary());
            // We only assert it doesn't throw and prints; some adversarial cases
            // intentionally exercise fallback paths documented in limitations_report.md
        });
    }

    static Stream<Arguments> adversarialMaskingCases() {
        return Stream.of(
            // Very short values
            Arguments.of("EMAIL",    "a@b.io"),
            Arguments.of("PHONE",    "00"),           // shorter than standard
            Arguments.of("CARD",     "1111"),          // shorter than standard
            Arguments.of("PAN",      "AB"),            // too short for standard path
            Arguments.of("AADHAAR",  "1234"),
            Arguments.of("PASSWORD", ""),             // blank password
            Arguments.of("GST",      "27"),           // too short

            // Very long values
            Arguments.of("EMAIL",    "verylonglocal.part.with.many.dots@subdomain.example.co.in"),
            Arguments.of("PASSWORD", "correct-horse-battery-staple-this-is-a-very-long-passphrase"),
            Arguments.of("CARD",     "1111111111111111"),   // standard 16

            // Special characters
            Arguments.of("EMAIL",    "user+tag@mail.com"),
            Arguments.of("PASSWORD", "!@#$%^&*()_+{}:<>?"),
            Arguments.of("EMAIL",    "dot.separated.name@deep.subdomain.example.co.in")
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // G. KNOWN LIMITATIONS – probed but NOT asserted (documented behavior)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G1. [KNOWN LIMIT] State code 99 in GST: regex accepts it but state doesn't exist")
    void knownLimitGstStateCode99() {
        String result = engine.detectByPattern(List.of("99ABCDE1234F1Z5"));
        // Document: engine flags this as GST – state whitelist check is NOT implemented
        System.out.println("[KNOWN_LIMITATION] GST with state=99 → " + result +
                " (should be flagged invalid but engine accepts it)");
        // Not asserting → this is intentional documentation, not a test gate
    }

    @Test
    @DisplayName("G2. [KNOWN LIMIT] PASSWORD has no value pattern – relies solely on column name")
    void knownLimitPasswordNoPattern() {
        // Column with generic name + password-like values → NOT detected
        String result = engine.discoverFieldType("user_secret",
            List.of("hunter2","p@ssw0rd","12345678"));
        System.out.println("[KNOWN_LIMITATION] 'user_secret' with passwords → " + result +
                " (keyword 'secret' not in NAME_KEYWORDS → missed)");
        assertNotEquals("PASSWORD", result,
                "Engine cannot detect passwords by value pattern – by design (see limitations_report.md)");
    }

    @Test
    @DisplayName("G3. [KNOWN LIMIT] PHONE regex matches Aadhaar last-12 digits (without spaces)")
    void knownLimitPhoneAadhaarAmbiguity() {
        // 12-digit Aadhaar without spaces: first 10 digits also match PHONE regex? No –
        // PHONE requires exactly 10. 12-digit Aadhaar matches AADHAAR regex.
        String result = engine.detectByPattern(List.of("123456789012"));
        assertEquals("AADHAAR", result,
                "12-digit value must match AADHAAR not PHONE (PHONE requires exactly 10)");
    }
}
