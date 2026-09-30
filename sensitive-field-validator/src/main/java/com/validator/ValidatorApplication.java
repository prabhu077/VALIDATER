package com.validator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Sensitive-Field Discovery & Masking Validator.
 *
 * Run with: mvn spring-boot:run
 * App will start at: http://localhost:8080
 */
@SpringBootApplication
public class ValidatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValidatorApplication.class, args);
    }

}
