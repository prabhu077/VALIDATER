package com.validator.pipeline;

import java.util.Collections;
import java.util.Optional;

/**
 * StepResult&lt;T&gt;
 * ─────────────────────────────────────────────────────────────────────────────
 * Typed outcome of a {@link PipelineStep#execute} call.
 *
 * Carries:
 *   - {@code status}       – SUCCESS or FAILED
 *   - {@code value}        – the produced output (present only on success)
 *   - {@code errorMessage} – human-readable failure description (on failure)
 *   - {@code rollbackHint} – free-text directive the RollbackManager uses to
 *                            reverse any side effects of this step
 *
 * Constructing results:
 * <pre>
 *   return StepResult.success(outputValue);
 *   return StepResult.failure("Could not parse CSV: " + e.getMessage(), "DELETE_TEMP_FILE:/tmp/x.csv");
 * </pre>
 *
 * @param <T> the output type this step produces on success
 */
public final class StepResult<T> {

    public enum Status { SUCCESS, FAILED }

    private final Status          status;
    private final T               value;
    private final String          errorMessage;
    private final String          rollbackHint;

    private StepResult(Status status, T value, String errorMessage, String rollbackHint) {
        this.status       = status;
        this.value        = value;
        this.errorMessage = errorMessage;
        this.rollbackHint = rollbackHint;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /** Creates a successful result carrying {@code value}. */
    public static <T> StepResult<T> success(T value) {
        return new StepResult<>(Status.SUCCESS, value, null, null);
    }

    /**
     * Creates a failure result with an error message.
     * No rollback hint – use when the step had no side effects.
     */
    public static <T> StepResult<T> failure(String errorMessage) {
        return new StepResult<>(Status.FAILED, null, errorMessage, null);
    }

    /**
     * Creates a failure result with an error message AND a rollback hint.
     *
     * @param rollbackHint a directive parseable by {@link RollbackManager},
     *                     e.g. "DELETE_SESSION_ATTRIBUTE:uploadedFullData"
     *                     or "RESTORE_SNAPSHOT:history-2024-01-01T12:00:00"
     */
    public static <T> StepResult<T> failure(String errorMessage, String rollbackHint) {
        return new StepResult<>(Status.FAILED, null, errorMessage, rollbackHint);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean  isSuccess()               { return status == Status.SUCCESS; }
    public boolean  isFailed()                { return status == Status.FAILED; }
    public Status   status()                  { return status; }

    /**
     * Returns the output value.
     * @throws IllegalStateException if called on a failed result
     */
    public T value() {
        if (isFailed()) {
            throw new IllegalStateException(
                    "Cannot access value of a failed StepResult. Error: " + errorMessage);
        }
        return value;
    }

    public Optional<T>     optionalValue()   { return Optional.ofNullable(value); }
    public Optional<String> errorMessage()   { return Optional.ofNullable(errorMessage); }
    public Optional<String> rollbackHint()   { return Optional.ofNullable(rollbackHint); }

    @Override
    public String toString() {
        return isSuccess()
                ? "StepResult[SUCCESS value=" + value + "]"
                : "StepResult[FAILED error='" + errorMessage + "' hint='" + rollbackHint + "']";
    }
}
