package com.validator;

import com.validator.audit.AuditEvent;
import com.validator.audit.AuditLogger;
import com.validator.pipeline.PipelineContext;
import com.validator.pipeline.RollbackManager;
import com.validator.pipeline.StepResult;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AccessControlTest
 * ─────────────────────────────────────────────────────────────────────────────
 * Tests for the architectural abstractions:
 *
 *  1. {@link AuditLogger}       – structured event logging, query, isolation
 *  2. {@link PipelineContext}   – run ID uniqueness, metadata bag, immutable views
 *  3. {@link RollbackManager}   – LIFO ordering, fault isolation, audit trail
 *  4. {@link StepResult}        – success/failure factories, value access guards
 *  5. Pipeline integrity        – ensures context and rollback manager interact correctly
 *
 * These tests validate the behavioral contracts of the architectural layer,
 * independent of the discovery or masking engine logic.
 */
@DisplayName("Architectural Abstractions – AuditLogger, PipelineContext, RollbackManager")
class AccessControlTest {

    private AuditLogger     auditLogger;
    private RollbackManager rollbackManager;
    private PipelineContext ctx;

    @BeforeEach
    void setup() {
        auditLogger     = new AuditLogger();
        rollbackManager = new RollbackManager();
        ctx             = new PipelineContext("test_user", auditLogger);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. AUDIT LOGGER TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AuditLogger: INFO event is recorded with correct fields")
    void testAuditInfoRecorded() {
        auditLogger.info("DISCOVERY", "Detected 3 sensitive fields", ctx.runId(), ctx.username());

        List<AuditEvent> events = auditLogger.forRun(ctx.runId());
        assertEquals(1, events.size());

        AuditEvent event = events.get(0);
        assertEquals("DISCOVERY",          event.stepName());
        assertEquals(AuditEvent.Level.INFO, event.level());
        assertEquals("test_user",           event.username());
        assertEquals(ctx.runId(),            event.runId());
        assertNotNull(event.timestamp());
    }

    @Test
    @DisplayName("AuditLogger: WARN and ERROR levels are recorded distinctly")
    void testAuditLevelsDistinct() {
        auditLogger.info("STEP_A",  "all good",   ctx.runId(), "u1");
        auditLogger.warn("STEP_B",  "slow run",   ctx.runId(), "u1");
        auditLogger.error("STEP_C", "crash",      ctx.runId(), "u1");

        assertEquals(3, auditLogger.all().size());
        assertEquals(2, auditLogger.atLevel(AuditEvent.Level.WARN).size(),
                "WARN level query should return WARN + ERROR");
        assertEquals(1, auditLogger.atLevel(AuditEvent.Level.ERROR).size(),
                "ERROR level query should return only ERROR");
    }

    @Test
    @DisplayName("AuditLogger: forRun isolates events by runId")
    void testAuditRunIsolation() {
        String run1 = "run-aaa";
        String run2 = "run-bbb";

        auditLogger.info("STEP_X", "event for run1", run1, "alice");
        auditLogger.info("STEP_X", "event for run2", run2, "bob");
        auditLogger.warn("STEP_Y", "warn for run1",  run1, "alice");

        assertEquals(2, auditLogger.forRun(run1).size(), "run1 should have 2 events");
        assertEquals(1, auditLogger.forRun(run2).size(), "run2 should have 1 event");
    }

    @Test
    @DisplayName("AuditLogger: forStep returns only events from that step name")
    void testAuditStepFilter() {
        auditLogger.info("DISCOVERY",  "found fields", ctx.runId(), "u");
        auditLogger.info("MASKING",    "masked ok",    ctx.runId(), "u");
        auditLogger.error("DISCOVERY", "retried",      ctx.runId(), "u");

        List<AuditEvent> discoveryEvents = auditLogger.forStep("DISCOVERY");
        assertEquals(2, discoveryEvents.size(), "forStep('DISCOVERY') must return 2 events");
        assertTrue(discoveryEvents.stream().allMatch(e -> "DISCOVERY".equals(e.stepName())));
    }

    @Test
    @DisplayName("AuditLogger: errorsForRun returns only ERRORs for that run")
    void testAuditErrorsForRun() {
        String run = ctx.runId();
        auditLogger.info("STEP_A",  "ok",    run, "u");
        auditLogger.error("STEP_B", "boom",  run, "u");
        auditLogger.error("STEP_C", "crash", run, "u");
        auditLogger.error("STEP_D", "other", "different-run", "u");

        List<AuditEvent> errors = auditLogger.errorsForRun(run);
        assertEquals(2, errors.size(), "Only 2 errors belong to this run");
        assertTrue(errors.stream().allMatch(e -> e.level() == AuditEvent.Level.ERROR));
    }

    @Test
    @DisplayName("AuditLogger: format() produces human-readable non-empty output")
    void testAuditEventFormat() {
        auditLogger.info("CSV_LOAD", "loaded 500 rows", ctx.runId(), "alice");
        AuditEvent event = auditLogger.forRun(ctx.runId()).get(0);

        String formatted = event.format();
        assertNotNull(formatted);
        assertTrue(formatted.contains("CSV_LOAD"),  "format must include step name");
        assertTrue(formatted.contains("alice"),     "format must include username");
        assertTrue(formatted.contains("INFO"),      "format must include log level");
    }

    @Test
    @DisplayName("AuditLogger: clear() removes all events")
    void testAuditClear() {
        auditLogger.info("STEP", "msg", ctx.runId(), "u");
        assertEquals(1, auditLogger.size());
        auditLogger.clear();
        assertEquals(0, auditLogger.size());
        assertTrue(auditLogger.all().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. PIPELINE CONTEXT TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PipelineContext: runId is unique per context instance")
    void testContextRunIdUnique() {
        PipelineContext ctx1 = new PipelineContext("u", auditLogger);
        PipelineContext ctx2 = new PipelineContext("u", auditLogger);
        assertNotEquals(ctx1.runId(), ctx2.runId(),
                "Two contexts must have different runIds (UUID-based)");
    }

    @Test
    @DisplayName("PipelineContext: username is preserved")
    void testContextUsername() {
        PipelineContext ctx = new PipelineContext("priya", auditLogger);
        assertEquals("priya", ctx.username());
    }

    @Test
    @DisplayName("PipelineContext: auditLogger reference is correctly threaded")
    void testContextAuditLoggerReference() {
        assertSame(auditLogger, ctx.auditLogger(),
                "Context must expose the same auditLogger instance it was constructed with");
    }

    @Test
    @DisplayName("PipelineContext: metadata bag put/get works correctly")
    void testContextMetadataBag() {
        ctx.put("fileName", "test.csv");
        ctx.put("rowCount", 500);
        ctx.put("sensitiveCount", 3L);

        assertEquals("test.csv", ctx.get("fileName"));
        assertEquals(500, ctx.get("rowCount", Integer.class));
        assertEquals(3L, ctx.get("sensitiveCount", Long.class));
        assertTrue(ctx.has("fileName"));
        assertFalse(ctx.has("nonExistentKey"));
    }

    @Test
    @DisplayName("PipelineContext: metadata() returns unmodifiable view")
    void testContextMetadataUnmodifiable() {
        ctx.put("k", "v");
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.metadata().put("other", "value"),
                "metadata() must return an unmodifiable view");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. ROLLBACK MANAGER TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RollbackManager: actions executed in LIFO order")
    void testRollbackLifoOrder() {
        List<String> executionOrder = new java.util.ArrayList<>();

        rollbackManager.register("STEP_1", "rollback step 1", () -> executionOrder.add("STEP_1"));
        rollbackManager.register("STEP_2", "rollback step 2", () -> executionOrder.add("STEP_2"));
        rollbackManager.register("STEP_3", "rollback step 3", () -> executionOrder.add("STEP_3"));

        rollbackManager.rollback(ctx.runId(), auditLogger, ctx.username());

        assertEquals(List.of("STEP_3","STEP_2","STEP_1"), executionOrder,
                "Rollback must execute in LIFO order (last registered = first executed)");
    }

    @Test
    @DisplayName("RollbackManager: failed action does not block subsequent rollbacks")
    void testRollbackFaultIsolation() {
        AtomicBoolean step1Ran = new AtomicBoolean(false);
        AtomicBoolean step3Ran = new AtomicBoolean(false);

        rollbackManager.register("STEP_1", "good",    () -> step1Ran.set(true));
        rollbackManager.register("STEP_2", "throws",  () -> { throw new RuntimeException("simulated failure"); });
        rollbackManager.register("STEP_3", "good",    () -> step3Ran.set(true));

        List<String> failed = rollbackManager.rollback(ctx.runId(), auditLogger, ctx.username());

        // STEP_3 ran first (LIFO), then STEP_2 threw, then STEP_1 ran
        assertTrue(step3Ran.get(),  "STEP_3 must still execute (ran before the failure)");
        assertTrue(step1Ran.get(),  "STEP_1 must still execute (fault isolation)");
        assertEquals(List.of("STEP_2"), failed, "Only STEP_2 should be in the failed list");
    }

    @Test
    @DisplayName("RollbackManager: rollback writes audit trail for each action")
    void testRollbackAuditTrail() {
        rollbackManager.register("STEP_A", "clean session", () -> {});
        rollbackManager.register("STEP_B", "remove file",   () -> {});

        rollbackManager.rollback(ctx.runId(), auditLogger, ctx.username());

        // Expect at least: 1 WARN (starting) + 2 INFO (each step) + 1 WARN (summary)
        List<AuditEvent> events = auditLogger.forRun(ctx.runId());
        assertTrue(events.size() >= 4, "Rollback must produce ≥4 audit events");
        assertTrue(events.stream().anyMatch(e -> e.level() == AuditEvent.Level.WARN),
                "At least 1 WARN event expected from rollback manager");
    }

    @Test
    @DisplayName("RollbackManager: empty stack rollback completes with no failures")
    void testRollbackEmptyStack() {
        List<String> failed = rollbackManager.rollback(ctx.runId(), auditLogger, ctx.username());
        assertTrue(failed.isEmpty(), "Rollback with no registered actions must succeed");
    }

    @Test
    @DisplayName("RollbackManager: pendingActions returns correct LIFO snapshot")
    void testRollbackPendingActionsSnapshot() {
        rollbackManager.register("A", "a", () -> {});
        rollbackManager.register("B", "b", () -> {});
        rollbackManager.register("C", "c", () -> {});

        List<RollbackManager.RollbackAction> pending = rollbackManager.pendingActions();
        assertEquals(3, pending.size());
        // LIFO order: C is first (top of stack)
        assertEquals("C", pending.get(0).stepName());
        assertEquals("A", pending.get(2).stepName());
    }

    @Test
    @DisplayName("RollbackManager: clear() removes all actions without executing them")
    void testRollbackClear() {
        AtomicInteger callCount = new AtomicInteger(0);
        rollbackManager.register("STEP", "desc", callCount::incrementAndGet);
        assertEquals(1, rollbackManager.size());

        rollbackManager.clear();
        assertEquals(0, rollbackManager.size());
        assertEquals(0, callCount.get(), "clear() must not execute actions");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. STEP RESULT TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("StepResult: success factory provides correct state")
    void testStepResultSuccess() {
        StepResult<String> result = StepResult.success("output-value");
        assertTrue(result.isSuccess());
        assertFalse(result.isFailed());
        assertEquals("output-value", result.value());
        assertTrue(result.errorMessage().isEmpty());
        assertTrue(result.rollbackHint().isEmpty());
    }

    @Test
    @DisplayName("StepResult: failure factory provides correct state")
    void testStepResultFailure() {
        StepResult<String> result = StepResult.failure("CSV parse error", "DELETE_TEMP:/tmp/x.csv");
        assertFalse(result.isSuccess());
        assertTrue(result.isFailed());
        assertTrue(result.errorMessage().isPresent());
        assertEquals("CSV parse error", result.errorMessage().get());
        assertTrue(result.rollbackHint().isPresent());
        assertEquals("DELETE_TEMP:/tmp/x.csv", result.rollbackHint().get());
    }

    @Test
    @DisplayName("StepResult: calling value() on a failed result throws IllegalStateException")
    void testStepResultValueOnFailureThrows() {
        StepResult<String> result = StepResult.failure("error");
        assertThrows(IllegalStateException.class, result::value,
                "value() on a failed result must throw IllegalStateException");
    }

    @Test
    @DisplayName("StepResult: optionalValue() on failure returns empty Optional")
    void testStepResultOptionalValueOnFailure() {
        StepResult<String> result = StepResult.failure("error");
        assertTrue(result.optionalValue().isEmpty(),
                "optionalValue() on failure must return empty Optional");
    }

    @Test
    @DisplayName("StepResult: failure without rollback hint has empty hint Optional")
    void testStepResultFailureNoHint() {
        StepResult<Integer> result = StepResult.failure("plain failure");
        assertTrue(result.rollbackHint().isEmpty(),
                "Failure without hint must return empty Optional for rollbackHint()");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. PIPELINE INTEGRITY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Pipeline integrity: context + audit logger + rollback work together end-to-end")
    void testPipelineIntegrationScenario() {
        // Simulate a 3-step pipeline where step 2 fails and triggers rollback

        AtomicBoolean step1RolledBack = new AtomicBoolean(false);
        AtomicBoolean step3Executed   = new AtomicBoolean(false);

        // Step 1 succeeds → registers rollback
        auditLogger.info("CSV_LOAD", "loaded 100 rows", ctx.runId(), ctx.username());
        rollbackManager.register("CSV_LOAD", "clear loaded data", () -> step1RolledBack.set(true));
        ctx.put("rowCount", 100);

        // Step 2 (DISCOVERY) fails
        String errorMsg = "Schema mismatch: column count inconsistent";
        auditLogger.error("DISCOVERY", errorMsg, ctx.runId(), ctx.username());
        StepResult<Void> discoveryResult = StepResult.failure(errorMsg, "RESTORE_SESSION:uploadedFullData");

        // Step 3 is NOT executed (pipeline aborted)
        assertTrue(discoveryResult.isFailed());
        assertFalse(step3Executed.get());

        // Trigger rollback
        List<String> failures = rollbackManager.rollback(ctx.runId(), auditLogger, ctx.username());

        // Verify rollback ran step 1's compensating action
        assertTrue(step1RolledBack.get(), "Step 1's compensating action must have run");
        assertTrue(failures.isEmpty(), "No rollback action should have failed");

        // Verify audit trail has all expected events
        List<AuditEvent> allEvents = auditLogger.forRun(ctx.runId());
        assertTrue(allEvents.stream().anyMatch(e -> "CSV_LOAD".equals(e.stepName())));
        assertTrue(allEvents.stream().anyMatch(e -> "DISCOVERY".equals(e.stepName())));
        assertTrue(allEvents.stream().anyMatch(e -> "ROLLBACK_MANAGER".equals(e.stepName())));

        // Verify metadata bag was populated correctly
        assertEquals(100, ctx.get("rowCount", Integer.class));
    }
}
