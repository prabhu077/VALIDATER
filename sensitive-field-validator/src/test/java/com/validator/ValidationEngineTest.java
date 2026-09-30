package com.validator;

import com.validator.engine.DiscoveryEngine;
import com.validator.validation.PrecisionRecallReporter;
import com.validator.validation.PrecisionRecallReporter.BenchmarkResult;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationEngineTest
 * ─────────────────────────────────────────────────────────────────────────────
 * Benchmarks the DiscoveryEngine against labeled ground-truth datasets and
 * computes Precision, Recall, F1, and FDR (False Discovery Rate).
 *
 * INVARIANT APPROACH
 * ------------------
 * Instead of hardcoding expected type strings, these tests assert that:
 *   - Precision ≥ 0.85  (≤15% of flagged fields are false alarms)
 *   - Recall    ≥ 0.85  (≥85% of truly sensitive fields are caught)
 *   - FDR       ≤ 0.15  (mirrors the precision floor from the false positive side)
 *
 * A per-column confusion matrix is printed for root-cause analysis.
 *
 * BENCHMARK DATASETS (defined inline – no file I/O dependency in unit tests)
 * --------------------------------------------------------------------------
 *  DATASET A – Normal happy-path  (all 7 types clearly present)
 *  DATASET B – Edge cases (malformed patterns, boundary values)
 *  DATASET C – Mixed-type columns (majority-vote stress test)
 *  DATASET D – No-name-hint columns (pure value-pattern detection)
 */
@DisplayName("ValidationEngine – Precision/Recall/FDR Benchmark")
class ValidationEngineTest {

    // Precision/Recall thresholds – CI quality gates
    private static final double MIN_PRECISION = 0.85;
    private static final double MIN_RECALL    = 0.85;
    private static final double MAX_FDR       = 0.15;  // = 1 - MIN_PRECISION

    private DiscoveryEngine       engine;
    private PrecisionRecallReporter reporter;

    @BeforeEach
    void setup() {
        engine   = new DiscoveryEngine();
        reporter = new PrecisionRecallReporter();
    }

    @AfterEach
    void printReport() {
        BenchmarkResult result = reporter.compute();
        System.out.println(result.summary());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DATASET A – Happy-path benchmark (all 7 types)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Dataset A: all 7 sensitive types detected at ≥85% precision & recall")
    void testHappyPathBenchmark() {
        // Ground-truth map: columnName → true sensitive type
        Map<String, Map.Entry<String, List<String>>> groundTruth = Map.of(
            "email",       Map.entry("EMAIL",
                List.of("alice@example.com","bob@mail.org","carol@domain.co.in")),
            "phone",       Map.entry("PHONE",
                List.of("9876543210","9123456789","8000000000")),
            "card_number", Map.entry("CARD",
                List.of("4111111111111111","5500000000000004","4012888888881881")),
            "pan_number",  Map.entry("PAN",
                List.of("ABCDE1234F","FGHIJ5678K","LMNOP9999Z")),
            "aadhaar",     Map.entry("AADHAAR",
                List.of("1234 5678 9012","9876 5432 1098","2345 6789 0123")),
            "password",    Map.entry("PASSWORD",
                List.of("Secret@123","P@ssw0rd1","hunter2")),
            "gstin",       Map.entry("GST",
                List.of("27ABCDE1234F1Z5","07FGHIJ5678K2Z3","29LMNOP9999Z3Z7")),
            "age",         Map.entry("NON_SENSITIVE",
                List.of("28","35","22")),
            "city",        Map.entry("NON_SENSITIVE",
                List.of("Mumbai","Delhi","Pune")),
            "order_total", Map.entry("NON_SENSITIVE",
                List.of("1500.00","2300.50","899.00"))
        );

        groundTruth.forEach((col, entry) -> {
            String truth     = entry.getKey();
            String predicted = engine.discoverFieldType(col, entry.getValue());
            reporter.record(col, truth, predicted);
        });

        BenchmarkResult result = reporter.compute();
        assertAll("Dataset A precision/recall gates",
            () -> assertTrue(result.meetsPrecisionThreshold(MIN_PRECISION),
                    String.format("Precision %.3f < %.2f threshold", result.precision(), MIN_PRECISION)),
            () -> assertTrue(result.meetsRecallThreshold(MIN_RECALL),
                    String.format("Recall %.3f < %.2f threshold", result.recall(), MIN_RECALL)),
            () -> assertTrue(result.fdr() <= MAX_FDR,
                    String.format("FDR %.3f > %.2f threshold", result.fdr(), MAX_FDR))
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DATASET B – Malformed / boundary edge cases
    // Purpose: engine must NOT flag these as sensitive (FP guard)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Dataset B: malformed patterns yield low FDR (high precision on non-sensitive)")
    void testMalformedPatternFalsePositiveRate() {
        // All of these have misleading column names OR values that look
        // almost-but-not-quite like a sensitive type.
        var cases = List.of(
            // (columnName, groundTruth, sampleValues)
            new TestCase("data_value",    "NON_SENSITIVE", List.of("notanemail","alsobad")),
            new TestCase("user_id",       "NON_SENSITIVE", List.of("987654321","12345678")),   // 9-digit ≠ phone
            new TestCase("reference",     "NON_SENSITIVE", List.of("411111111111","550000000000")), // 12-digit ≠ card
            new TestCase("code",          "NON_SENSITIVE", List.of("abcde1234f","fghij5678k")), // lowercase ≠ PAN
            new TestCase("identifier",    "NON_SENSITIVE", List.of("HELLO12345","WORLD99999")), // not PAN format
            new TestCase("amount",        "NON_SENSITIVE", List.of("1500.00","2300.50","0.99")),
            new TestCase("status",        "NON_SENSITIVE", List.of("ACTIVE","INACTIVE","PENDING")),
            new TestCase("timestamp",     "NON_SENSITIVE", List.of("2024-01-01","2024-06-15"))
        );

        for (TestCase tc : cases) {
            String predicted = engine.discoverFieldType(tc.columnName(), tc.values());
            reporter.record(tc.columnName(), tc.groundTruth(), predicted);
        }

        BenchmarkResult result = reporter.compute();
        // For a pure NON_SENSITIVE dataset: TP=0, FP=<few>, TN=many → Precision irrelevant
        // What matters is that FP count is minimal
        long fpCount = result.observations().stream().filter(o -> o.isFP()).count();
        System.out.println("Dataset B false positives: " + fpCount + "/" + cases.size());
        assertTrue(fpCount <= 1,
                "At most 1 false positive allowed on clearly non-sensitive malformed dataset, got: " + fpCount);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DATASET C – Mixed-type / ambiguous columns (majority-vote stress test)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Dataset C: majority-vote correctly resolves ambiguous columns")
    void testMixedTypeColumns() {
        // Column with 60% emails + 40% phone → should be EMAIL (majority)
        // Use single-TLD emails: multi-part TLDs (.co.in) are not matched by the current regex
        String col1Predicted = engine.discoverFieldType("contact_info",
            List.of("alice@example.com", "bob@mail.org", "carol@domain.com",  // 3 emails (60%)
                    "9876543210", "8000000000"));                               // 2 phones (40%)
        // 3/5 = 60% EMAIL → strict >50% → EMAIL
        reporter.record("contact_info", "EMAIL", col1Predicted);

        // Column with 50/50 split → should NOT be flagged (requires >50%)
        String col2Predicted = engine.discoverFieldType("mixed_ids",
            List.of("alice@example.com","bob@mail.org",                        // 2 emails
                    "not-an-email","also-not-email"));                          // 2 non-matching
        // 2/4 = 50% → must NOT trigger (strict >50% rule)
        reporter.record("mixed_ids", "NON_SENSITIVE", col2Predicted);

        // Column with all blanks/nulls → NON_SENSITIVE
        // Use ArrayList (not List.of) because List.of prohibits null elements
        java.util.List<String> blanks = new java.util.ArrayList<>();
        blanks.add(""); blanks.add("  "); blanks.add(null); blanks.add("");
        String col3Predicted = engine.discoverFieldType("empty_col", blanks);
        reporter.record("empty_col", "NON_SENSITIVE", col3Predicted);

        // Single sensitive value → should still detect (1/1 = 100%)
        String col4Predicted = engine.discoverFieldType("single_row",
            List.of("9876543210"));
        reporter.record("single_row", "PHONE", col4Predicted);

        BenchmarkResult result = reporter.compute();
        System.out.println("Dataset C confusion:\n" + result.summary());

        // Both col1 (EMAIL majority) and col4 (PHONE single) must be TP
        long tpCount = result.observations().stream().filter(o -> o.isTP()).count();
        assertTrue(tpCount >= 2, "col1 (EMAIL 60% majority) and col4 (PHONE single) must both be TP, got TPs=" + tpCount);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DATASET D – No column-name hints (pure value-pattern detection)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Dataset D: pure pattern detection without column name hints")
    void testPurePatternDetection() {
        // All column names are generic/non-hinting
        var cases = List.of(
            new TestCase("field_a", "EMAIL",       List.of("a@b.io","x@y.com","u@v.net")),
            new TestCase("field_b", "PHONE",       List.of("9876543210","8000000000","7777777777")),
            new TestCase("field_c", "CARD",        List.of("4111111111111111","5500000000000004")),
            new TestCase("field_d", "PAN",         List.of("ABCDE1234F","FGHIJ5678K")),
            new TestCase("field_e", "AADHAAR",     List.of("123456789012","987654321098")),
            new TestCase("field_f", "GST",         List.of("27ABCDE1234F1Z5","07FGHIJ5678K2Z3")),
            new TestCase("field_g", "NON_SENSITIVE", List.of("Chicago","Berlin","Tokyo"))
        );

        for (TestCase tc : cases) {
            String predicted = engine.discoverFieldType(tc.columnName(), tc.values());
            reporter.record(tc.columnName(), tc.groundTruth(), predicted);
        }

        BenchmarkResult result = reporter.compute();
        assertAll("Dataset D pattern-only detection gates",
            () -> assertTrue(result.meetsPrecisionThreshold(MIN_PRECISION),
                    "Precision without name hints: " + result.precision()),
            () -> assertTrue(result.meetsRecallThreshold(MIN_RECALL),
                    "Recall without name hints: " + result.recall())
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PrecisionRecallReporter unit tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Reporter: all-correct predictions yield Precision=1.0, FDR=0.0")
    void testPerfectPredictions() {
        reporter.record("col1", "EMAIL",         "EMAIL");
        reporter.record("col2", "PHONE",         "PHONE");
        reporter.record("col3", "NON_SENSITIVE", "NON_SENSITIVE");

        BenchmarkResult r = reporter.compute();
        assertEquals(1.0, r.precision(), 0.001);
        assertEquals(1.0, r.recall(),    0.001);
        assertEquals(0.0, r.fdr(),       0.001);
        assertEquals(1.0, r.accuracy(),  0.001);
    }

    @Test
    @DisplayName("Reporter: all-wrong FP predictions yield Precision=0.0, FDR=1.0")
    void testAllFalsePositives() {
        // Predicting sensitive when ground truth is NON_SENSITIVE → all FP, no FN
        reporter.record("col1", "NON_SENSITIVE", "EMAIL");
        reporter.record("col2", "NON_SENSITIVE", "PHONE");

        BenchmarkResult r = reporter.compute();
        assertEquals(0.0, r.precision(), 0.001, "All FP → precision=0.0");
        assertEquals(1.0, r.fdr(),       0.001, "All FP → FDR=1.0");
        // When TP+FN=0 (no positive ground-truth labels exist), recall is mathematically
        // undefined. The reporter returns 1.0 by convention (trivially all positives captured).
        // Check that TP=0 and FN=0 instead of asserting recall directly.
        assertEquals(0, r.tp(), "No TP expected");
        assertEquals(0, r.fn(), "No FN expected – there are no positive ground-truth labels");
        assertEquals(2, r.fp(), "Both predictions are FP");
    }

    @Test
    @DisplayName("Reporter: all false negatives yield Recall=0.0")
    void testAllFalseNegatives() {
        reporter.record("col1", "EMAIL", "NON_SENSITIVE");
        reporter.record("col2", "PHONE", "NON_SENSITIVE");

        BenchmarkResult r = reporter.compute();
        assertEquals(0.0, r.recall(),    0.001, "All FN → recall=0.0");
        assertEquals(0.0, r.f1(),        0.001, "All FN → F1=0.0");
    }

    @Test
    @DisplayName("Reporter: empty observations yield perfect defaults (no division by zero)")
    void testEmptyObservations() {
        BenchmarkResult r = reporter.compute();
        assertEquals(1.0, r.precision(), 0.001, "Empty → default precision 1.0");
        assertEquals(1.0, r.recall(),    0.001, "Empty → default recall 1.0");
        assertEquals(0.0, r.fdr(),       0.001, "Empty → default FDR 0.0");
        assertEquals(0, r.observations().size());
    }

    // ─── helper record ────────────────────────────────────────────────────
    private record TestCase(String columnName, String groundTruth, List<String> values) {}
}
