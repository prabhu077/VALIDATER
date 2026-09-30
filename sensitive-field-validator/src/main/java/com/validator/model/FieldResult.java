package com.validator.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One entry in a validator's work history -- either a single field
 * verification, or a summary of a CSV upload.
 */
public class FieldResult {

    private final String fieldName;
    private final String detectedType;
    private final boolean sensitive;
    private final String maskedPreview;
    private final String username;
    private final LocalDateTime timestamp;

    public FieldResult(String fieldName, String detectedType, boolean sensitive,
                        String maskedPreview, String username) {
        this.fieldName = fieldName;
        this.detectedType = detectedType;
        this.sensitive = sensitive;
        this.maskedPreview = maskedPreview;
        this.username = username;
        this.timestamp = LocalDateTime.now();
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getDetectedType() {
        return detectedType;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public String getMaskedPreview() {
        return maskedPreview;
    }

    public String getUsername() {
        return username;
    }

    public String getFormattedTimestamp() {
        return timestamp.format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"));
    }
}
