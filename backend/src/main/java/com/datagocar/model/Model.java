package com.datagocar.model;

/**
 * Marker holder for small shared helpers of the design model.
 * The design document itself is {@link DataModel}; all parts are immutable records.
 */
public final class Model {
    public static final String DEFAULT_SCHEMA = "public";

    private Model() {
    }

    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String schemaOrDefault(String schema) {
        return isBlank(schema) ? DEFAULT_SCHEMA : schema.trim();
    }
}
