package com.validator.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Landing, home, and the payment showcase page.
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String root(HttpSession session) {
        return session.getAttribute("validatorName") != null ? "redirect:/home" : "redirect:/login";
    }

    @GetMapping("/home")
    public String home(HttpSession session, Model model) {
        String name = (String) session.getAttribute("validatorName");
        if (name == null) {
            return "redirect:/login";
        }
        model.addAttribute("validatorName", name);
        return "home";
    }

    @GetMapping("/payment")
    public String paymentPage(HttpSession session, Model model) {
        String name = (String) session.getAttribute("validatorName");
        if (name == null) {
            return "redirect:/login";
        }
        model.addAttribute("validatorName", name);
        return "payment";
    }

    // Showcase only: nothing entered here is validated, charged, or stored.
    @PostMapping("/payment")
    public String submitPayment(HttpSession session, Model model, @RequestParam String plan) {
        String name = (String) session.getAttribute("validatorName");
        if (name == null) {
            return "redirect:/login";
        }
        model.addAttribute("validatorName", name);
        model.addAttribute("plan", plan);
        model.addAttribute("success", true);
        return "payment";
    }
}
