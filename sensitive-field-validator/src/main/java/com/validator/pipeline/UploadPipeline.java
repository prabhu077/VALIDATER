package com.validator.pipeline;

import com.validator.audit.AuditLogger;
import com.validator.model.FieldResult;
import com.validator.service.HistoryStore;
import com.validator.service.ScanService;
import com.validator.util.CsvLoader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * UploadPipeline
 * --------------
 * Runs one CSV upload as named stages, using the PipelineContext, AuditLogger and
 * RollbackManager abstractions:
 *
 *   RECEIVE -> PARSE -> DISCOVER_AND_VALIDATE -> STORE -> RECORD_HISTORY
 *
 * Every stage writes an audit event and is timed for the UI trace. STORE registers
 * a compensating action; if a later stage fails, RollbackManager runs it (LIFO) so a
 * half-finished run never leaves a dataset behind in the session.
 *
 * The web layer stays thin: it passes in how to commit / undo the session write.
 * Audit messages carry counts and file names only - never cell values.
 */
@Service
public class UploadPipeline {

    public record Stage(String name, String status, long millis, String detail) implements Serializable {}

    /** Everything a finished (or failed) run produced. Held in the session on success. */
    public record Outcome(boolean success, String error, ScanService.ScanReport report,
                          Map<String, List<String>> data, List<Stage> stages,
                          String runId) implements Serializable {}

    /** Message is safe to show to the user as-is. */
    private static final class UserFacing extends RuntimeException {
        UserFacing(String message) { super(message); }
    }

    private final ScanService scanService;
    private final AuditLogger audit;
    private final HistoryStore history;

    public UploadPipeline(ScanService scanService, AuditLogger audit, HistoryStore history) {
        this.scanService = scanService;
        this.audit = audit;
        this.history = history;
    }

    /**
     * @param commit     stores a successful Outcome (e.g. into the HTTP session)
     * @param undoCommit reverses commit; registered with the RollbackManager
     */
    public Outcome run(String username, String fileName, byte[] bytes,
                       Consumer<Outcome> commit, Runnable undoCommit) {

        PipelineContext ctx = new PipelineContext(username, audit);
        ctx.put("fileName", fileName);
        RollbackManager rollback = new RollbackManager();
        List<Stage> stages = new ArrayList<>();
        String step = "RECEIVE";
        long t = System.nanoTime();

        try {
            if (bytes == null || bytes.length == 0) {
                throw new UserFacing("Please choose a CSV file first.");
            }
            if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                throw new UserFacing("Only .csv files are supported.");
            }
            t = done(ctx, stages, step, t, bytes.length + " bytes received");

            step = "PARSE";
            Map<String, List<String>> data = CsvLoader.loadAll(new ByteArrayInputStream(bytes));
            if (data.isEmpty()) {
                throw new UserFacing("That CSV has no header row.");
            }
            int rows = data.values().iterator().next().size();
            if (rows == 0) {
                throw new UserFacing("That CSV has a header but no data rows.");
            }
            t = done(ctx, stages, step, t, rows + " rows x " + data.size() + " columns");

            step = "DISCOVER_AND_VALIDATE";
            ScanService.ScanReport report = scanService.scan(fileName, data);
            String detail = report.sensitiveCount() + " sensitive column(s), "
                    + report.checksFailed() + "/" + report.checksTotal() + " mask checks failed";
            t = done(ctx, stages, step, t, detail);
            if (report.checksFailed() > 0) {
                audit.warn(step, "Masking invariants violated in " + report.reviewCount()
                        + " column(s) - review before sharing", ctx.runId(), username);
            }

            step = "STORE";
            Outcome ok = new Outcome(true, null, report, data, stages, ctx.runId());
            commit.accept(ok);
            rollback.register(step, "Remove uploaded dataset from session", undoCommit);
            t = done(ctx, stages, step, t, "dataset held for this session only");

            step = "RECORD_HISTORY";
            history.add(username, new FieldResult(
                    "CSV upload: " + fileName,
                    report.sensitiveCount() + " sensitive / " + report.columns() + " fields",
                    report.sensitiveCount() > 0,
                    "-",
                    username));
            done(ctx, stages, step, t, "history entry written");
            audit.info("PIPELINE", "Run completed", ctx.runId(), username);
            return ok; // 'stages' is the same list instance, so the stored copy sees RECORD_HISTORY too

        } catch (UserFacing e) {
            return fail(ctx, rollback, stages, step, t, e.getMessage(), e.getMessage());
        } catch (Exception e) {
            return fail(ctx, rollback, stages, step, t,
                    "Could not process that file. Please upload a valid UTF-8 CSV.",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private long done(PipelineContext ctx, List<Stage> stages, String step, long startNanos, String detail) {
        long now = System.nanoTime();
        stages.add(new Stage(step, "OK", (now - startNanos) / 1_000_000, detail));
        audit.info(step, detail, ctx.runId(), ctx.username());
        return now;
    }

    private Outcome fail(PipelineContext ctx, RollbackManager rollback, List<Stage> stages,
                         String step, long startNanos, String userMessage, String auditMessage) {
        stages.add(new Stage(step, "FAILED", (System.nanoTime() - startNanos) / 1_000_000, auditMessage));
        audit.error(step, auditMessage, ctx.runId(), ctx.username());
        if (rollback.size() > 0) {
            rollback.rollback(ctx.runId(), audit, ctx.username());
        }
        return new Outcome(false, userMessage, null, null, stages, ctx.runId());
    }
}
