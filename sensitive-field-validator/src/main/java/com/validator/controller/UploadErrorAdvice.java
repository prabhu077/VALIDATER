package com.validator.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Turns "file too large" (spring.servlet.multipart.max-file-size) into a friendly
 * message on the Data Loading page instead of the default error page.
 */
@ControllerAdvice
public class UploadErrorAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView tooLarge(HttpSession session) {
        Object name = session.getAttribute("validatorName");
        if (name == null) {
            return new ModelAndView("redirect:/login");
        }
        ModelAndView mv = new ModelAndView("data-loading");
        mv.addObject("validatorName", name);
        mv.addObject("uploadError", "That file is larger than the 10 MB upload limit.");
        return mv;
    }
}
