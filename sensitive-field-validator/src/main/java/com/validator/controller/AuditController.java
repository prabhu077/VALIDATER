package com.validator.controller;

import com.validator.audit.AuditEvent;
import com.validator.audit.AuditLogger;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shows the signed-in validator's own pipeline audit events, newest first.
 * The AuditLogger is one shared sink for every user, so events are filtered by
 * username here - one validator never sees another's activity.
 */
@Controller
public class AuditController {

    private static final int MAX_ROWS = 300;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AuditLogger audit;

    public AuditController(AuditLogger audit) {
        this.audit = audit;
    }

    @GetMapping("/audit-log")
    public String auditLog(HttpSession session, Model model,
                           @RequestParam(name = "level", defaultValue = "ALL") String level) {
        String name = (String) session.getAttribute("validatorName");
        if (name == null) return "redirect:/login";

        String wanted = level.toUpperCase(Locale.ROOT);
        List<Map<String, String>> rows = new ArrayList<>();
        int info = 0, warn = 0, error = 0;

        for (AuditEvent e : audit.all()) {                 // newest first
            if (!name.equals(e.username())) continue;
            switch (e.level()) {
                case INFO  -> info++;
                case WARN  -> warn++;
                case ERROR -> error++;
            }
            if (!"ALL".equals(wanted) && !e.level().name().equals(wanted)) continue;
            if (rows.size() >= MAX_ROWS) continue;          // keep counting, stop collecting

            Map<String, String> row = new LinkedHashMap<>();
            row.put("time",    TIME.format(e.timestamp()));
            row.put("level",   e.level().name());
            row.put("step",    e.stepName());
            row.put("message", e.message());
            row.put("run",     e.runId() == null ? "-" : e.runId().substring(0, Math.min(8, e.runId().length())));
            rows.add(row);
        }

        model.addAttribute("validatorName", name);
        model.addAttribute("events", rows);
        model.addAttribute("level", "ALL".equals(wanted) || "INFO".equals(wanted)
                || "WARN".equals(wanted) || "ERROR".equals(wanted) ? wanted : "ALL");
        model.addAttribute("infoCount", info);
        model.addAttribute("warnCount", warn);
        model.addAttribute("errorCount", error);
        return "audit-log";
    }
}
