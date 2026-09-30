package com.validator.service;

import com.validator.model.FieldResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory work history, keyed by validator name. Newest entries first.
 */
@Component
public class HistoryStore {

    private final Map<String, List<FieldResult>> historyByUser = new ConcurrentHashMap<>();

    public void add(String username, FieldResult result) {
        List<FieldResult> list = historyByUser.computeIfAbsent(username,
                k -> Collections.synchronizedList(new ArrayList<>()));
        list.add(0, result);
    }

    public List<FieldResult> getHistory(String username) {
        return historyByUser.getOrDefault(username, Collections.emptyList());
    }
}
