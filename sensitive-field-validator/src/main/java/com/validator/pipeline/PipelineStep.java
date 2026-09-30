package com.validator.pipeline;

/**
 * PipelineStep
 * ─────────────────────────────────────────────────────────────────────────────
 * Architectural abstraction representing a single discrete stage in a
 * sensitive-field validation pipeline.
 *
 * DESIGN RATIONALE
 * ----------------
 * A pipeline is a sequence of typed transformations:
 *
 *   CSV Load → Column Sampling → Discovery → Masking → Encryption → Audit → Export
 *
 * Encoding each stage as a {@code PipelineStep<I, O>} gives us:
 *   - Composability  : steps chain naturally (output of one feeds the next)
 *   - Testability    : each step tested in isolation with mocked neighbours
 *   - Observability  : the step name and context appear in every audit log entry
 *   - Rollback       : failed steps emit a {@link StepResult#rollbackHint()} that
 *                      the {@link com.validator.pipeline.RollbackManager} uses to
 *                      reverse any side effects
 *
 * IMPLEMENTING A STEP
 * -------------------
 * <pre>{@code
 * public class DiscoveryStep implements PipelineStep<SampledData, DiscoveredFields> {
 *     @Override public String name() { return "DISCOVERY"; }
 *     @Override public StepResult<DiscoveredFields> execute(PipelineContext ctx,
 *                                                           SampledData input) {
 *         Map<String,String> found = engine.discoverFields(input.columnSamples());
 *         ctx.auditLogger().info(name(), "discovered " + found.size() + " fields", ctx);
 *         return StepResult.success(new DiscoveredFields(found));
 *     }
 * }
 * }</pre>
 *
 * @param <I> the type this step accepts as input
 * @param <O> the type this step produces as output
 */
public interface PipelineStep<I, O> {

    /**
     * Unique, human-readable identifier for this step.
     * Used in audit log entries and rollback registrations.
     * Convention: SCREAMING_SNAKE_CASE  (e.g. "CSV_LOAD", "DISCOVERY", "AES_ENCRYPT").
     */
    String name();

    /**
     * Executes this pipeline step.
     *
     * @param ctx   shared pipeline context carrying the audit logger, run ID,
     *              username, and mutable metadata bag
     * @param input the typed input produced by the previous step (or the initial
     *              trigger payload for the first step)
     * @return a {@link StepResult} indicating success or failure; never null
     */
    StepResult<O> execute(PipelineContext ctx, I input);

    /**
     * Human-readable description of what this step does.
     * Shown in audit logs and the rollback manifest.
     * Default implementation returns the step name; override to be more descriptive.
     */
    default String description() { return name(); }
}
