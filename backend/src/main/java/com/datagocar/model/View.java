package com.datagocar.model;

/**
 * A SQL view ({@code CREATE VIEW}) or materialized view. {@code definition} is the raw query after {@code AS}
 * (e.g. {@code SELECT ... FROM ...}), without a trailing semicolon. Its columns come from the query, so the model
 * does not list them.
 */
public record View(
        String id,
        String schema,
        String name,
        String comment,
        boolean materialized,
        String definition,
        Position position,
        String color
) {
    public View {
        schema = Model.schemaOrDefault(schema);
        definition = definition == null ? "" : stripTrailingSemicolons(definition.strip());
        position = position == null ? new Position(0, 0) : position;
    }

    public View withPosition(Position p) {
        return new View(id, schema, name, comment, materialized, definition, p, color);
    }

    private static String stripTrailingSemicolons(String s) {
        String r = s;
        while (r.endsWith(";")) {
            r = r.substring(0, r.length() - 1).stripTrailing();
        }
        return r;
    }
}
