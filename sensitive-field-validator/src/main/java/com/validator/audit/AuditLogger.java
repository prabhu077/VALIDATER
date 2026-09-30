package com.validator.audit;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * AuditLogger
 * ─────────────────────────────────────────────────────────────────────────────
 * Structured audit logging sink for the sensitive-field validation pipeline.
 *
 * RESPONSIBILITIES
 * ----------------
 * 1. Accept structured {@link AuditEvent} entries from every pipeline step.
 * 2. Persist them in an in-memory store (swap for DB/Pub-Sub in production).
 * 3. Print to stdout so they appear in the Spring Boot log stream.
 * 4. Expose query methods (filter by runId, step, level) used in tests and
 *    the pipeline rollback mechanism.
 *
 * PRODUCTION EXTENSION POINTS
 * ---------------------------
 * - Inject a {@code AuditEventRepository} (Spring Data JPA) and persist each
 *   event to a DB table for durable audit trails.
 * - Publish each event to a Pub/Sub or Kafka topic for SIEM integration.
 * - Add structured JSON formatting (MDC + Logback) for log aggregators.
 *
 * THREAD SAFETY
 * -------------
 * Uses {@link CopyOnWriteArrayList} – safe for concurrent pipeline runs.
 * Each run should use the SAME shared AuditLogger bean (Spring singleton).
 */
@Component
public class AuditLogger {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    // ── Convenience write methods ─────────────────────────────────────────────

    public void info(String stepName, String message, String runId, String username) {
        emit(AuditEvent.Level.INFO, stepName, message, runId, username);
    }

    public void warn(String stepName, String message, String runId, String username) {
        emit(AuditEvent.Level.WARN, stepName, message, runId, username);
    }

    public void error(String stepName, String message, String runId, String username) {
        emit(AuditEvent.Level.ERROR, stepName, message, runId, username);
    }

    private void emit(AuditEvent.Level level, String stepName, String message,
                      String runId, String username) {
        AuditEvent event = new AuditEvent(runId, stepName, level, message, username, Instant.now());
        events.add(event);
        System.out.println(event.format()); // surfaces in Spring Boot logs
    }

    // ── Query methods ─────────────────────────────────────────────────────────

    /** All events, newest-first. */
    public List<AuditEvent> all() {
        List<AuditEvent> copy = new java.util.ArrayList<>(events);
        Collections.reverse(copy);
        return Collections.unmodifiableList(copy);
    }

    /** All events for a specific pipeline run. */
    public List<AuditEvent> forRun(String runId) {
        return events.stream()
                .filter(e -> runId.equals(e.runId()))
                .collect(Collectors.toUnmodifiableList());
    }

    /** All events emitted by a particular step name. */
    public List<AuditEvent> forStep(String stepName) {
        return events.stream()
                .filter(e -> stepName.equals(e.stepName()))
                .collect(Collectors.toUnmodifiableList());
    }

    /** All events at or above a given severity level. */
    public List<AuditEvent> atLevel(AuditEvent.Level level) {
        return events.stream()
                .filter(e -> e.level().ordinal() >= level.ordinal())
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * All ERROR-level events for a run – the rollback manager calls this to
     * determine which steps need reversal.
     */
    public List<AuditEvent> errorsForRun(String runId) {
        return events.stream()
                .filter(e -> runId.equals(e.runId()) && e.level() == AuditEvent.Level.ERROR)
                .collect(Collectors.toUnmodifiableList());
    }

    /** Clears the in-memory store – use only in tests or between test runs. */
    public void clear() { events.clear(); }

    /** Total number of events logged so far. */
    public int size() { return events.size(); }
}
