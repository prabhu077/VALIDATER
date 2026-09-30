package com.validator.model;

/**
 * Represents a registered validator (the person logging into the tool).
 * Kept in memory only -- this is a showcase prototype, not a production
 * user store.
 */
public class User {

    private final String name;
    private final String email;
    private final String passwordHash;

    public User(String name, String email, String passwordHash) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
