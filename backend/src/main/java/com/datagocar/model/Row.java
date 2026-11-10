package com.datagocar.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One seed-data row of a table. {@code values} maps column id to the value as text, written as the user would type
 * it ({@code 42}, {@code true}, {@code Female}, {@code 2024-01-31}); a column without a value gets its DEFAULT
 * (NULL when it has none). Keyed by column id so renaming a column keeps the data.
 */
public record Row(String id, Map<String, String> values) {
    public Row {
        Map<String, String> copy = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(k, v);
                }
            });
        }
        values = java.util.Collections.unmodifiableMap(copy);
    }

    public String value(String columnId) {
        return values.get(columnId);
    }
}
