package com.validator.controller;

import com.validator.model.User;
import com.validator.service.UserStore;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Login, registration and logout for the validator tool.
 */
@Controller
public class AuthController {

    private final UserStore userStore;

    public AuthController(UserStore userStore) {
        this.userStore = userStore;
    }

    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "registered", required = false) String registered, Model model) {
        if (registered != null) {
            model.addAttribute("info", "Account created. Please sign in.");
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String name,
                         @RequestParam String password,
                         HttpSession session,
                         Model model) {
        Optional<User> user = userStore.authenticate(name, password);
        if (user.isEmpty()) {
            model.addAttribute("error", "Name or password is incorrect.");
            model.addAttribute("name", name);
            return "login";
        }
        session.setAttribute("validatorName", user.get().getName());
        return "redirect:/home";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String name,
                            @RequestParam String email,
                            @RequestParam String password,
                            @RequestParam String confirmPassword,
                            Model model) {
        name = name == null ? "" : name.trim();

        if (name.isEmpty() || email == null || email.isBlank() || password == null || password.isBlank()) {
            model.addAttribute("error", "Please fill in every field.");
            return "register";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("error", "Passwords do not match.");
            model.addAttribute("name", name);
            model.addAttribute("email", email);
            return "register";
        }
        if (userStore.exists(name)) {
            model.addAttribute("error", "That validator name is already taken.");
            model.addAttribute("email", email);
            return "register";
        }

        userStore.register(name, email, password);
        return "redirect:/login?registered=true";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}
