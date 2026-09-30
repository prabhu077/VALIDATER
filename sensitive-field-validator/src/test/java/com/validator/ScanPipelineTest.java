package com.validator;

import com.validator.audit.AuditEvent;
import com.validator.audit.AuditLogger;
import com.validator.model.FieldResult;
import com.validator.pipeline.UploadPipeline;
import com.validator.service.HistoryStore;
import com.validator.service.ScanService;
import com.validator.util.MaskingUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the upload pipeline, the shared ScanService and the regression fixes that
 * came with the UI rework. Pure Java - no Spring context needed.
 */
class ScanPipelineTest {

    private static byte[] csv(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    // --- regression: EMAIL mask echoed the raw value when there was no '@' -------------
    @Test
    void emailMaskNeverEchoesAValueWithoutAt() {
        String masked = MaskingUtil.mask("EMAIL", "notanemail");
        assertEquals("***", masked);
        assertFalse(masked.contains("notanemail"));
    }

    // --- ScanService --------------------------------------------------------------------
    @Test
    void scanServiceClassifiesAndNeverExposesSensitiveRawValues() {
        Map<String, List<String>> data = new LinkedHashMap<>();
        data.put("email", List.of("alice@example.com", "bob@mail.org"));
        data.put("city",  List.of("Mumbai", "Delhi"));
        data.put("phone", List.of("9876543210", "9123456789"));

        ScanService.ScanReport r = new ScanService().scan("t.csv", data);

        assertEquals(3, r.columns());
        assertEquals(2, r.rows());
        assertEquals(2, r.sensitiveCount());
        assertEquals(0, r.checksFailed(), "healthy masks must pass every invariant");
        assertEquals("NON_SENSITIVE", r.types().get("city"));
        for (ScanService.ColumnReport c : r.columnReports()) {
            if (c.sensitive()) {
                assertFalse(c.preview().contains("alice@example.com"), "raw email leaked into preview");
                assertFalse(c.preview().contains("9876543210"), "raw phone leaked into preview");
            }
        }
    }

    @Test
    void headerOnlyDetectionIsFlaggedForReview() {
        Map<String, List<String>> data = new LinkedHashMap<>();
        data.put("password", List.of("hunter2", "letmein"));   // no value pattern exists for PASSWORD
        ScanService.ScanReport r = new ScanService().scan("t.csv", data);
        assertEquals(1, r.reviewCount());
        assertEquals("Column name", r.columnReports().get(0).signal());
    }

    // --- UploadPipeline: happy path + input hygiene ---------------------------------------
    @Test
    void pipelineHandlesBomDuplicateAndBlankHeadersAndShortRows() {
        AuditLogger audit = new AuditLogger();
        HistoryStore history = new HistoryStore();
        UploadPipeline p = new UploadPipeline(new ScanService(), audit, history);
        AtomicReference<UploadPipeline.Outcome> stored = new AtomicReference<>();

        String body = "\uFEFFemail,email,,phone\r\nnotanemail,a@b.io,x,9876543210\r\nu@d.com\r\n";
        UploadPipeline.Outcome o = p.run("tester", "x.csv", csv(body), stored::set, () -> stored.set(null));

        assertTrue(o.success());
        assertSame(o, stored.get());
        assertEquals(List.of("email", "email_2", "column_3", "phone"),
                List.copyOf(o.data().keySet()));
        assertEquals(2, o.report().rows());
        assertEquals(1, history.getHistory("tester").size());
        assertEquals(List.of("RECEIVE", "PARSE", "DISCOVER_AND_VALIDATE", "STORE", "RECORD_HISTORY"),
                o.stages().stream().map(UploadPipeline.Stage::name).toList());
    }

    @Test
    void pipelineRejectsBadInputWithFriendlyMessages() {
        UploadPipeline p = new UploadPipeline(new ScanService(), new AuditLogger(), new HistoryStore());
        AtomicReference<UploadPipeline.Outcome> stored = new AtomicReference<>();

        assertEquals("Please choose a CSV file first.",
                p.run("u", "a.csv", new byte[0], stored::set, () -> {}).error());
        assertEquals("Only .csv files are supported.",
                p.run("u", "a.txt", csv("a,b\n1,2\n"), stored::set, () -> {}).error());
        assertEquals("That CSV has a header but no data rows.",
                p.run("u", "a.csv", csv("a,b,c\n"), stored::set, () -> {}).error());
        assertNull(stored.get(), "a rejected upload must never be committed");
    }

    // --- UploadPipeline: rollback when a late stage fails ----------------------------------
    @Test
    void failureAfterStoreRollsTheSessionWriteBack() {
        AuditLogger audit = new AuditLogger();
        HistoryStore failingHistory = new HistoryStore() {
            @Override public void add(String username, FieldResult result) {
                throw new IllegalStateException("history backend down");
            }
        };
        UploadPipeline p = new UploadPipeline(new ScanService(), audit, failingHistory);
        AtomicReference<UploadPipeline.Outcome> stored = new AtomicReference<>();

        UploadPipeline.Outcome o = p.run("tester", "x.csv", csv("email\nalice@example.com\n"),
                stored::set, () -> stored.set(null));

        assertFalse(o.success());
        assertNull(stored.get(), "STORE must be compensated by the RollbackManager");
        assertTrue(audit.all().stream().anyMatch(e ->
                "ROLLBACK_MANAGER".equals(e.stepName()) && e.level() == AuditEvent.Level.INFO));
        assertFalse(audit.errorsForRun(o.runId()).isEmpty());
    }

    // --- privacy: audit trail carries counts and names, never cell values --------------------
    @Test
    void auditLogNeverContainsCellValues() {
        AuditLogger audit = new AuditLogger();
        UploadPipeline p = new UploadPipeline(new ScanService(), audit, new HistoryStore());
        p.run("tester", "x.csv", csv("email,card_number\nalice@example.com,4111111111111111\n"),
                x -> {}, () -> {});
        for (AuditEvent e : audit.all()) {
            assertFalse(e.message().contains("alice@example.com"), e.message());
            assertFalse(e.message().contains("4111111111111111"), e.message());
        }
    }
}
