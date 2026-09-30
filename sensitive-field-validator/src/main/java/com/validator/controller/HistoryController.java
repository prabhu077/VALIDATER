package com.validator.controller;

import com.validator.service.HistoryStore;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Shows the current validator's own past work -- field checks and CSV
 * uploads -- newest first.
 */
@Controller
public class HistoryController {

    private final HistoryStore historyStore;

    public HistoryController(HistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    @GetMapping("/history")
    public String history(HttpSession session, Model model) {
        String name = (String) session.getAttribute("validatorName");
        if (name == null) {
            return "redirect:/login";
        }
        model.addAttribute("validatorName", name);
        model.addAttribute("results", historyStore.getHistory(name));
        return "history";
    }
}
