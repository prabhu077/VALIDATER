package com.validator.pipeline;

import com.validator.audit.AuditEvent;
import com.validator.audit.AuditLogger;

import java.util.*;

/**
 * RollbackManager
 * ─────────────────────────────────────────────────────────────────────────────
 * Registers and executes compensating actions for failed pipeline steps.
 *
 * DESIGN
 * ------
 * When a step succeeds it registers a {@link RollbackAction} with this manager
 * via {@link #register}.  If a later step fails, the pipeline runner calls
 * {@link #rollback} to execute all registered compensating actions in LIFO
 * order (last step rolled back first – classic saga pattern).
 *
 * This is the in-process, synchronous version sufficient for the current
 * in-memory implementation.  See "PRODUCTION EXTENSION POINTS" below for
 * how to scale this to durable distributed sagas.
 *
 * USAGE IN A PIPELINE STEP
 * ------------------------
 * <pre>{@code
 * // On success, register a rollback action:
 * ctx.rollbackManager().register(name(), "Remove uploaded data from session",
 *     () -> session.removeAttribute("uploadedFullData"));
 *
 * // In the runner, on failure:
 * ctx.rollbackManager().rollback(ctx.runId(), ctx.auditLogger(), ctx.username());
 * }</pre>
 *
 * PRODUCTION EXTENSION POINTS
 * ---------------------------
 * 1. Persist the rollback manifest to a DB table on every {@link #register}
 *    call so rollback survives JVM restarts.
 * 2. Replace {@link RollbackAction} with a serialisable command object
 *    (e.g. a JPA entity with a discriminator column per action type).
 * 3. Publish rollback commands to a queue (SQS, Pub/Sub) and have a
 *    separate worker execute them for long-running distributed steps.
 */
public class RollbackManager {

    /**
     * A single compensating action registered by a pipeline step.
     *
     * @param stepName    the step that registered this action
     * @param description human-readable description (appears in audit log)
     * @param action      the lambda that performs the reversal
     */
    public record RollbackAction(String stepName, String description, Runnable action) {}

    /** LIFO stack: last-registered action is rolled back first. */
    private final Deque<RollbackAction> stack = new ArrayDeque<>();

    /**
     * Registers a compensating action for the given step.
     * Call this AFTER the step succeeds (not before).
     *
     * @param stepName    the {@link PipelineStep#name()} registering the action
     * @param description short human-readable summary of what the rollback does
     * @param action      the lambda to execute on rollback
     */
    public void register(String stepName, String description, Runnable action) {
        stack.push(new RollbackAction(stepName, description, action));
    }

    /**
     * Executes all registered rollback actions in LIFO order.
     * Each action is attempted independently – a failure in one does NOT
     * abort the remaining rollbacks (best-effort).
     *
     * @param runId       pipeline run ID (for audit log correlation)
     * @param auditLogger audit logger to record each rollback attempt
     * @param username    operator username (for audit records)
     * @return list of step names whose rollback actions FAILED
     */
    public List<String> rollback(String runId, AuditLogger auditLogger, String username) {
        List<String> failedSteps = new ArrayList<>();
        auditLogger.warn("ROLLBACK_MANAGER",
                String.format("Starting rollback of %d action(s)", stack.size()),
                runId, username);

        while (!stack.isEmpty()) {
            RollbackAction ra = stack.pop();
            try {
                ra.action().run();
                auditLogger.info("ROLLBACK_MANAGER",
                        String.format("Rolled back [%s]: %s", ra.stepName(), ra.description()),
                        runId, username);
            } catch (Exception e) {
                failedSteps.add(ra.stepName());
                auditLogger.error("ROLLBACK_MANAGER",
                        String.format("Rollback FAILED for [%s] (%s): %s",
                                ra.stepName(), ra.description(), e.getMessage()),
                        runId, username);
            }
        }

        String outcome = failedSteps.isEmpty()
                ? "Rollback completed successfully"
                : "Rollback completed with failures in: " + failedSteps;
        auditLogger.warn("ROLLBACK_MANAGER", outcome, runId, username);
        return failedSteps;
    }

    /**
     * Returns an unmodifiable snapshot of the current rollback stack
     * (first element = next to be rolled back).
     */
    public List<RollbackAction> pendingActions() {
        return Collections.unmodifiableList(new ArrayList<>(stack));
    }

    /** How many rollback actions are currently registered. */
    public int size() { return stack.size(); }

    /** Clears all registered actions without executing them. Use in tests only. */
    public void clear() { stack.clear(); }
}
