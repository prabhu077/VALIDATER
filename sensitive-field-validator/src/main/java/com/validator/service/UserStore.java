package com.validator.service;

import com.validator.model.User;
import com.validator.util.PasswordUtil;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registered-user store. Resets whenever the app restarts --
 * this is a demo/showcase, not a persistence layer.
 */
@Component
public class UserStore {

    private final Map<String, User> usersByName = new ConcurrentHashMap<>();

    public boolean exists(String name) {
        return name != null && usersByName.containsKey(name.trim().toLowerCase());
    }

    public User register(String name, String email, String rawPassword) {
        User user = new User(name.trim(), email.trim(), PasswordUtil.hash(rawPassword));
        usersByName.put(name.trim().toLowerCase(), user);
        return user;
    }

    public Optional<User> authenticate(String name, String rawPassword) {
        if (name == null || rawPassword == null) {
            return Optional.empty();
        }
        User user = usersByName.get(name.trim().toLowerCase());
        if (user != null && user.getPasswordHash().equals(PasswordUtil.hash(rawPassword))) {
            return Optional.of(user);
        }
        return Optional.empty();
    }
}
