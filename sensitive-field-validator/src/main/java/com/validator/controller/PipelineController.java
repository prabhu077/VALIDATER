package com.validator.controller;

import com.opencsv.CSVWriter;
import com.validator.audit.AuditLogger;
import com.validator.pipeline.UploadPipeline;
import com.validator.pipeline.UploadPipeline.Outcome;
import com.validator.pipeline.UploadPipeline.Stage;
import com.validator.service.ScanService.ColumnReport;
import com.validator.service.ScanService.ScanReport;
import com.validator.util.EncryptionUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CSV Data Loading page.
 *
 * Upload -> UploadPipeline (parse, discover, validate masking invariants, audit,
 * rollback on failure) -> redirect back to the page, which renders the report held in
 * the session.  The controller only moves data between HTTP and the pipeline; the
 * scan logic lives in ScanService.
 */
@Controller
@RequestMapping("/data-loading")
public class PipelineController {

    /** Session key holding the last successful UploadPipeline.Outcome. */
    static final String SCAN = "scan";

    private final UploadPipeline pipeline;
    private final AuditLogger audit;

    public PipelineController(UploadPipeline pipeline, AuditLogger audit) {
        this.pipeline = pipeline;
        this.audit = audit;
    }

    // --- GET /data-loading ---------------------------------------------------
    @GetMapping
    public String dataLoadingPage(HttpSession session, Model model) {
        String name = (String) session.getAttribute("validatorName");
        if (name == null) return "redirect:/login";

        model.addAttribute("validatorName", name);
        attachReview(session, model);
        return "data-loading";
    }

    // --- POST /data-loading/upload -------------------------------------------
    @PostMapping("/upload")
    public String upload(HttpSession session, Model model,
                         @RequestParam("csvFile") MultipartFile file) {

        String name = (String) session.getAttribute("validatorName");
        if (name == null) return "redirect:/login";
        model.addAttribute("validatorName", name);

        byte[] bytes;
        try {
            bytes = file == null ? new byte[0] : file.getBytes();
        } catch (IOException e) {
            model.addAttribute("uploadError", "Could not read that file. Please try again.");
            return "data-loading";
        }

        Outcome outcome = pipeline.run(
                name,
                file == null ? null : file.getOriginalFilename(),
                bytes,
                o -> session.setAttribute(SCAN, o),
                () -> session.removeAttribute(SCAN));

        if (!outcome.success()) {
            model.addAttribute("uploadError", outcome.error());
            return "data-loading";
        }
        // Post/Redirect/Get: refreshing the page no longer re-submits the upload.
        return "redirect:/data-loading";
    }

    // --- POST /data-loading/clear --------------------------------------------
    @PostMapping("/clear")
    public String clear(HttpSession session) {
        String name = (String) session.getAttribute("validatorName");
        if (name == null) return "redirect:/login";

        Outcome o = (Outcome) session.getAttribute(SCAN);
        session.removeAttribute(SCAN);
        if (o != null) {
            audit.info("DISCARD", "Dataset removed from session by user",
                    o.runId(), name);
        }
        return "redirect:/data-loading";
    }

    // --- GET /data-loading/download-encrypted --------------------------------
    @GetMapping("/download-encrypted")
    public void downloadEncrypted(HttpSession session,
                                  HttpServletResponse response) throws IOException {

        String user = (String) session.getAttribute("validatorName");
        if (user == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Please log in first.");
            return;
        }

        Outcome outcome = (Outcome) session.getAttribute(SCAN);
        if (outcome == null || !outcome.success() || outcome.data() == null || outcome.data().isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "No dataset loaded. Please upload a CSV first.");
            return;
        }

        ScanReport report = outcome.report();
        Map<String, List<String>> data = outcome.data();

        String base = report.fileName() == null ? "dataset" : report.fileName();
        base = base.replaceFirst("(?i)\\.csv$", "").replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) base = "dataset";

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + base + "_encrypted.csv\"");

        // Resolve columns once instead of a map lookup per cell.
        List<String> names = new ArrayList<>(data.keySet());
        List<List<String>> columns = new ArrayList<>(names.size());
        boolean[] encrypt = new boolean[names.size()];
        int encryptedColumns = 0;
        for (int c = 0; c < names.size(); c++) {
            columns.add(data.get(names.get(c)));
            encrypt[c] = !"NON_SENSITIVE".equals(report.types().getOrDefault(names.get(c), "NON_SENSITIVE"));
            if (encrypt[c]) encryptedColumns++;
        }
        int rows = report.rows();

        try (CSVWriter writer = new CSVWriter(new BufferedWriter(
                new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8), 1 << 16))) {

            writer.writeNext(names.toArray(new String[0]));

            String[] out = new String[names.size()];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < out.length; c++) {
                    List<String> vals = columns.get(c);
                    String v = r < vals.size() ? vals.get(r) : "";
                    out[c] = encrypt[c] ? EncryptionUtil.encrypt(v) : v;
                }
                writer.writeNext(out);
            }
        }

        audit.info("DOWNLOAD_ENCRYPTED",
                rows + " rows, " + encryptedColumns + " encrypted column(s)",
                outcome.runId(), user);
    }

    // --- view model ----------------------------------------------------------
    private void attachReview(HttpSession session, Model model) {
        Outcome o = (Outcome) session.getAttribute(SCAN);
        if (o == null || !o.success()) return;

        ScanReport r = o.report();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rows",           r.rows());
        summary.put("columns",        r.columns());
        summary.put("sensitiveCount", r.sensitiveCount());
        summary.put("cleanCount",     r.columns() - r.sensitiveCount());
        summary.put("reviewCount",    r.reviewCount());
        summary.put("checksTotal",    r.checksTotal());
        summary.put("checksFailed",   r.checksFailed());
        summary.put("checksPassed",   r.checksTotal() - r.checksFailed());
        summary.put("elapsedMs",      r.elapsedMs());
        summary.put("runId",          o.runId().substring(0, 8));

        List<Map<String, Object>> rows = new ArrayList<>(r.columnReports().size());
        for (ColumnReport c : r.columnReports()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index",      c.index());
            row.put("column",     c.column());
            row.put("type",       c.type());
            row.put("sensitive",  c.sensitive());
            row.put("signal",     c.signal());
            row.put("preview",    c.preview());
            row.put("checked",    c.checked());
            row.put("failed",     c.failed());
            row.put("violations", String.join("; ", c.violations()));
            row.put("review",     c.review());
            row.put("filled",     c.filled());
            row.put("total",      c.total());
            rows.add(row);
        }

        List<Map<String, Object>> breakdown = new ArrayList<>();
        int sensitive = Math.max(1, r.sensitiveCount());
        r.typeCounts().forEach((type, count) -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("type",  type);
            b.put("count", count);
            b.put("pct",   Math.round(100f * count / sensitive));
            breakdown.add(b);
        });

        List<Map<String, Object>> stages = new ArrayList<>();
        for (Stage s : o.stages()) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("name",   s.name());
            st.put("status", s.status());
            st.put("millis", s.millis());
            st.put("detail", s.detail());
            stages.add(st);
        }

        model.addAttribute("uploadSuccess",  true);
        model.addAttribute("fileName",       r.fileName());
        model.addAttribute("summary",        summary);
        model.addAttribute("reviewRows",     rows);
        model.addAttribute("typeBreakdown",  breakdown);
        model.addAttribute("stages",         stages);
    }
}
