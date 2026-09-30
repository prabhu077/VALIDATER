package com.validator.audit;

import java.time.Instant;

/**
 * AuditEvent
 * ─────────────────────────────────────────────────────────────────────────────
 * Immutable record of a single audit log entry produced during a pipeline run.
 *
 * Fields
 * ------
 *  runId      – the pipeline execution this event belongs to
 *  stepName   – the {@link com.validator.pipeline.PipelineStep#name()} that emitted it
 *  level      – INFO | WARN | ERROR
 *  message    – free-text description of what happened
 *  username   – the operator who triggered the run
 *  timestamp  – UTC instant of the event
 */
public record AuditEvent(
        String  runId,
        String  stepName,
        Level   level,
        String  message,
        String  username,
        Instant timestamp
) {
    public enum Level { INFO, WARN, ERROR }

    /** Convenience one-liner for log output. */
    public String format() {
        return String.format("[%s] %s | run=%s step=%-18s user=%-12s | %s",
                timestamp, level, runId, stepName, username, message);
    }
}
