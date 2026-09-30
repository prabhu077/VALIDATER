package com.validator;

import com.validator.util.MaskingUtil;
import com.validator.validation.MaskingInvariantChecker;
import com.validator.validation.MaskingInvariantChecker.InvariantReport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MaskingEngineTest
 * ─────────────────────────────────────────────────────────────────────────────
 * Validates MaskingUtil using INVARIANT CHECKING via {@link MaskingInvariantChecker}.
 *
 * DESIGN PRINCIPLE
 * ----------------
 * Tests do NOT assert exact masked strings (e.g. "j***@mail.com").
 * Instead, they assert non-leakage invariants and structural invariants:
 *
 *   ✓ "The masked EMAIL must not contain the full email address"
 *   ✓ "The masked EMAIL must still contain '@'"
 *   ✓ "The masked CARD must end with the last 4 raw digits"
 *   ✓ "The masked PASSWORD must be exactly 8 '*' characters"
 *
 * This makes the test suite robust to masking style changes (e.g. switching
 * from "j***@mail.com" to "j...@mail.com") without sacrificing safety coverage.
 *
 * Each test prints the InvariantReport summary for readable CI output.
 */
@DisplayName("MaskingUtil – Invariant-Based Non-Leakage Tests")
class MaskingEngineTest {

    // ═══════════════════════════════════════════════════════════════════════
    // 1. PARAMETERIZED INVARIANT SWEEP – happy-path representative values
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "[{0}] raw=''{1}''")
    @MethodSource("happyPathCases")
    @DisplayName("Happy-path invariants satisfied for all 7 types")
    void testInvariantsSatisfiedHappyPath(String type, String rawValue) {
        String masked = MaskingUtil.mask(type, rawValue);
        InvariantReport report = MaskingInvariantChecker.check(type, rawValue, masked);

        System.out.println(report.summary());
        assertTrue(report.passed(), "Invariant violations: " + report.violations());
    }

    static Stream<Arguments> happyPathCases() {
        return Stream.of(
            // EMAIL
            Arguments.of("EMAIL", "alice@example.com"),
            Arguments.of("EMAIL", "bob.smith+filter@mail.co.uk"),
            Arguments.of("EMAIL", "x@y.io"),              // min local-part
            // PHONE
            Arguments.of("PHONE", "9876543210"),
            Arguments.of("PHONE", "0000000000"),
            Arguments.of("PHONE", "1234567890"),
            // CARD (13-digit minimum)
            Arguments.of("CARD",  "4111111111111"),
            // CARD (16-digit standard)
            Arguments.of("CARD",  "4111111111111111"),
            Arguments.of("CARD",  "5500000000000004"),
            // PAN
            Arguments.of("PAN",   "ABCDE1234F"),
            Arguments.of("PAN",   "VWXYZ2222V"),
            // AADHAAR (with spaces)
            Arguments.of("AADHAAR", "1234 5678 9012"),
            // AADHAAR (without spaces)
            Arguments.of("AADHAAR", "123456789012"),
            // PASSWORD
            Arguments.of("PASSWORD", "Secret@123"),
            Arguments.of("PASSWORD", "hunter2"),
            Arguments.of("PASSWORD", "correct-horse-battery-staple"),
            // GST
            Arguments.of("GST",  "27ABCDE1234F1Z5"),
            Arguments.of("GST",  "07FGHIJ5678K2Z3")
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. EMAIL-SPECIFIC INVARIANTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EMAIL: '@' must survive masking")
    void testEmailAtSignPreserved() {
        String masked = MaskingUtil.mask("EMAIL", "alice@example.com");
        assertTrue(masked.contains("@"), "@ must be preserved in EMAIL mask");
    }

    @Test
    @DisplayName("EMAIL: local part (chars 2..@) must be hidden")
    void testEmailLocalPartHidden() {
        String raw    = "alice@example.com";
        String masked = MaskingUtil.mask("EMAIL", raw);
        // "lice" (chars 2-5 of local part) must not appear
        assertFalse(masked.contains("lice"),
                "EMAIL mask must not expose inner local-part characters: " + masked);
    }

    @Test
    @DisplayName("EMAIL: single-char local part handled without exception")
    void testEmailSingleCharLocal() {
        assertDoesNotThrow(() -> {
            String masked = MaskingUtil.mask("EMAIL", "a@b.io");
            InvariantReport report = MaskingInvariantChecker.check("EMAIL", "a@b.io", masked);
            System.out.println(report.summary());
            assertTrue(report.passed(), report.summary());
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. PHONE-SPECIFIC INVARIANTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PHONE: last 2 digits must remain visible")
    void testPhoneLast2Visible() {
        String raw    = "9876543210";
        String masked = MaskingUtil.mask("PHONE", raw);
        assertTrue(masked.endsWith("10"),
                "PHONE mask must end with last 2 raw digits '10', got: " + masked);
    }

    @Test
    @DisplayName("PHONE: full 10-digit sequence must not survive masking")
    void testPhoneFullNotLeaked() {
        String raw    = "9876543210";
        String masked = MaskingUtil.mask("PHONE", raw);
        assertFalse(masked.matches(".*\\d{10}.*"),
                "Full phone number must not appear in mask: " + masked);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. CARD-SPECIFIC INVARIANTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CARD: last 4 digits must remain visible")
    void testCardLast4Visible() {
        String raw    = "4111111111111111";
        String masked = MaskingUtil.mask("CARD", raw);
        assertTrue(masked.endsWith("1111"),
                "CARD mask must end with last 4 digits '1111', got: " + masked);
    }

    @Test
    @DisplayName("CARD: first 12 digits must be hidden")
    void testCardFirst12Hidden() {
        String raw    = "4111111111111111";
        String masked = MaskingUtil.mask("CARD", raw);
        assertFalse(masked.contains("411111111111"),
                "CARD mask must not expose the first 12 digits: " + masked);
    }

    @Test
    @DisplayName("CARD: spaces in raw value are stripped before masking")
    void testCardWithSpacesHandled() {
        // Some card formats include spaces; MaskingUtil strips them
        String masked = MaskingUtil.mask("CARD", "4111 1111 1111 1111");
        assertFalse(masked.matches(".*\\d{13,16}.*"),
                "Full card digit sequence must not survive after space-stripped masking");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. PAN-SPECIFIC INVARIANTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PAN: first 5 structural characters must survive masking")
    void testPanPrefixSurvives() {
        String raw    = "ABCDE1234F";
        String masked = MaskingUtil.mask("PAN", raw);
        assertTrue(masked.startsWith("ABCDE"),
                "PAN mask must preserve first 5 structural chars (entity type + holder code): " + masked);
    }

    @Test
    @DisplayName("PAN: digits 6-9 must be hidden")
    void testPanMidDigitsHidden() {
        String raw    = "ABCDE1234F";
        String masked = MaskingUtil.mask("PAN", raw);
        assertFalse(masked.contains("1234"),
                "PAN mask must hide digit portion '1234': " + masked);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. AADHAAR-SPECIFIC INVARIANTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AADHAAR: last 4 digits must be visible")
    void testAadhaarLast4Visible() {
        String raw    = "1234 5678 9012";
        String masked = MaskingUtil.mask("AADHAAR", raw);
        assertTrue(masked.endsWith("9012"),
                "AADHAAR mask must show last 4 digits '9012': " + masked);
    }

    @Test
    @DisplayName("AADHAAR: first 8 digits must not appear in mask")
    void testAadhaarFirst8Hidden() {
        String raw    = "123456789012";
        String masked = MaskingUtil.mask("AADHAAR", raw);
        assertFalse(masked.contains("12345678"),
                "AADHAAR mask must hide the first 8 digits: " + masked);
    }

    @Test
    @DisplayName("AADHAAR: mask must use 'X' as placeholder (not '*')")
    void testAadhaarXPlaceholder() {
        String masked = MaskingUtil.mask("AADHAAR", "123456789012");
        assertTrue(masked.contains("X"),
                "AADHAAR mask must use 'X' as its placeholder character");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. PASSWORD-SPECIFIC INVARIANTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PASSWORD: raw value must not appear anywhere in mask")
    void testPasswordRawHidden() {
        String raw    = "Secret@123";
        String masked = MaskingUtil.mask("PASSWORD", raw);
        assertFalse(masked.contains(raw),
                "Password mask must not contain the raw password: " + masked);
    }

    @Test
    @DisplayName("PASSWORD: mask is exactly 8 '*' characters (fixed-length policy)")
    void testPasswordFixedLength8Stars() {
        for (String pwd : java.util.List.of("a", "short", "ThisIsAVeryLongPasswordThatExceedsEightChars")) {
            String masked = MaskingUtil.mask("PASSWORD", pwd);
            assertEquals("********", masked,
                    "PASSWORD mask must always be exactly 8 stars regardless of raw length: " + masked);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. GST-SPECIFIC INVARIANTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GST: state code (first 2 digits) must survive masking")
    void testGstStateCodeVisible() {
        String raw    = "27ABCDE1234F1Z5";
        String masked = MaskingUtil.mask("GST", raw);
        assertTrue(masked.startsWith("27"),
                "GST mask must preserve the 2-digit state code '27': " + masked);
    }

    @Test
    @DisplayName("GST: middle entity PAN portion must be hidden")
    void testGstEntityHidden() {
        String raw    = "27ABCDE1234F1Z5";
        String masked = MaskingUtil.mask("GST", raw);
        assertFalse(masked.contains("ABCDE"),
                "GST mask must hide the embedded PAN entity section: " + masked);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. NULL / BLANK SAFETY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Null value returns empty string (no NPE)")
    void testNullValueSafe() {
        assertDoesNotThrow(() -> {
            String result = MaskingUtil.mask("EMAIL", null);
            assertEquals("", result, "null value must return empty string");
        });
    }

    @Test
    @DisplayName("Blank value returns empty string (no NPE)")
    void testBlankValueSafe() {
        String result = MaskingUtil.mask("CARD", "   ");
        assertEquals("", result, "Blank value must return empty string");
    }

    @Test
    @DisplayName("Null type returns value unchanged (passthrough)")
    void testNullTypeSafe() {
        String result = MaskingUtil.mask(null, "hello world");
        assertEquals("hello world", result, "null type must pass value through unchanged");
    }

    @Test
    @DisplayName("Unknown type returns value unchanged (passthrough)")
    void testUnknownTypeSafe() {
        String result = MaskingUtil.mask("UNKNOWN_TYPE", "some value");
        assertEquals("some value", result, "Unknown type must pass value through unchanged");
    }

}
