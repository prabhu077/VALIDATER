package com.validator.util;

import com.opencsv.CSVReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a CSV into the shape DiscoveryEngine expects (column name -> values).
 *
 * loadAll() parses the stream ONCE and streams row by row (no readAll() list of
 * String[] held next to the column lists). It also normalises headers:
 *   - strips a UTF-8 BOM (Excel exports put one on the first header),
 *   - names blank headers column_N,
 *   - de-duplicates repeated headers (email, email_2, ...) so no column
 *     silently overwrites another in the map,
 *   - pads short rows with "" so every column list has the same length.
 */
public final class CsvLoader {

    private CsvLoader() {
    }

    /** Whole file, all rows, column-oriented. Empty map if there is no header. */
    public static Map<String, List<String>> loadAll(InputStream inputStream) throws IOException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String[] header = reader.readNext();
            if (header == null) {
                return new LinkedHashMap<>();
            }

            String[] names = normalizeHeader(header);
            List<List<String>> columns = new ArrayList<>(names.length);
            for (int i = 0; i < names.length; i++) {
                columns.add(new ArrayList<>());
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length == 1 && row[0].isBlank()) {
                    continue; // blank line
                }
                for (int c = 0; c < names.length; c++) {
                    columns.get(c).add(c < row.length ? row[c] : "");
                }
            }

            Map<String, List<String>> result = new LinkedHashMap<>();
            for (int c = 0; c < names.length; c++) {
                result.put(names[c], columns.get(c));
            }
            return result;
        } catch (Exception e) {
            throw new IOException("Could not parse CSV file", e);
        }
    }

    /** Header plus at most maxSampleRows data rows (kept for existing callers). */
    public static Map<String, List<String>> loadColumnSamples(InputStream inputStream, int maxSampleRows) throws IOException {
        Map<String, List<String>> all = loadAll(inputStream);
        if (maxSampleRows >= Integer.MAX_VALUE || all.isEmpty()) {
            return all;
        }
        Map<String, List<String>> sampled = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : all.entrySet()) {
            List<String> v = e.getValue();
            sampled.put(e.getKey(), new ArrayList<>(v.subList(0, Math.min(v.size(), Math.max(0, maxSampleRows)))));
        }
        return sampled;
    }

    public static int countDataRows(InputStream inputStream) throws IOException {
        Map<String, List<String>> all = loadAll(inputStream);
        return all.isEmpty() ? 0 : all.values().iterator().next().size();
    }

    private static String[] normalizeHeader(String[] header) {
        String[] out = new String[header.length];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < header.length; i++) {
            String name = header[i] == null ? "" : header[i];
            if (i == 0 && name.startsWith("\uFEFF")) {
                name = name.substring(1);
            }
            name = name.trim();
            if (name.isEmpty()) {
                name = "column_" + (i + 1);
            }
            String unique = name;
            int n = 2;
            while (!seen.add(unique)) {
                unique = name + "_" + n++;
            }
            out[i] = unique;
        }
        return out;
    }
}
