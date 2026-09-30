package com.validator.pipeline;

import com.validator.audit.AuditLogger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PipelineContext
 * ─────────────────────────────────────────────────────────────────────────────
 * Shared mutable context threaded through every {@link PipelineStep} in a
 * single pipeline run.
 *
 * Carries:
 *   - {@code runId}       – unique identifier for this pipeline execution (UUID)
 *   - {@code username}    – the operator who triggered the run
 *   - {@code auditLogger} – the logging sink all steps must use
 *   - {@code metadata}    – open key-value bag for inter-step state that does
 *                           not warrant its own typed field (e.g. file name,
 *                           row count, detected column count)
 *
 * The context is intentionally NOT thread-safe (one context per run).
 */
public final class PipelineContext {

    private final String      runId;
    private final String      username;
    private final AuditLogger auditLogger;
    private final Map<String, Object> metadata = new HashMap<>();

    public PipelineContext(String username, AuditLogger auditLogger) {
        this.runId       = UUID.randomUUID().toString();
        this.username    = username;
        this.auditLogger = auditLogger;
    }

    /** @return unique UUID string for this pipeline run */
    public String runId()       { return runId; }

    /** @return the operator username who triggered this run */
    public String username()    { return username; }

    /** @return the audit logger all steps should write to */
    public AuditLogger auditLogger() { return auditLogger; }

    // ── Metadata bag ─────────────────────────────────────────────────────────

    public void   put(String key, Object value) { metadata.put(key, value); }
    public Object get(String key)               { return metadata.get(key); }
    public boolean has(String key)              { return metadata.containsKey(key); }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = metadata.get(key);
        return (v != null && type.isInstance(v)) ? type.cast(v) : null;
    }

    public Map<String, Object> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public String toString() {
        return "PipelineContext[runId=" + runId + ", user=" + username + "]";
    }
}
