package com.pgdatamodeler.ddl;

import com.pgdatamodeler.model.Column;
import com.pgdatamodeler.model.Identity;
import com.pgdatamodeler.model.Row;
import com.pgdatamodeler.model.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL for a table's seed rows: one idempotent {@code INSERT ... ON CONFLICT ... DO UPDATE} (an upsert on the
 * primary key, or on a unique column when the key is generated) plus, for identity columns given explicit values,
 * a {@code setval} so later inserts do not collide with the seeded ids.
 */
final class SeedData {

    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?");
    private static final Pattern NUMERIC_TYPE = Pattern.compile(
            "(smallint|integer|int|int2|int4|int8|bigint|numeric|decimal|real|double precision|float4|float8)(\\(.*\\))?");

    private SeedData() {
    }

    static boolean has(Table t) {
        return !insertColumns(t).isEmpty();
    }

    /** Columns that have a value in at least one row, in table order. */
    static List<Column> insertColumns(Table t) {
        return t.columns().stream()
                .filter(c -> t.rows().stream().anyMatch(r -> r.value(c.id()) != null))
                .toList();
    }

    /** The key the upsert matches on: the primary key, else a unique column, provided in every row. */
    static Optional<List<Column>> conflictKey(Table t) {
        List<Column> pk = t.primaryKeyColumns();
        if (!pk.isEmpty() && pk.stream().allMatch(c -> inEveryRow(t, c))) {
            return Optional.of(pk);
        }
        return t.columns().stream().filter(c -> c.unique() && inEveryRow(t, c)).findFirst().map(List::of);
    }

    private static boolean inEveryRow(Table t, Column c) {
        return t.rows().stream().allMatch(r -> r.value(c.id()) != null);
    }

    static String insert(Table t) {
        List<Column> cols = insertColumns(t);
        String table = Identifiers.qualified(t.schema(), t.name());
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (")
                .append(cols.stream().map(c -> Identifiers.quote(c.name())).collect(Collectors.joining(", ")))
                .append(')');
        if (cols.stream().anyMatch(c -> c.identity() == Identity.ALWAYS)) {
            sb.append(" OVERRIDING SYSTEM VALUE");
        }
        sb.append("\nVALUES\n");
        List<String> tuples = new ArrayList<>();
        for (Row r : t.rows()) {
            if (r.values().isEmpty()) {
                continue;
            }
            tuples.add("    (" + cols.stream().map(c -> value(c, r.value(c.id()))).collect(Collectors.joining(", ")) + ")");
        }
        sb.append(String.join(",\n", tuples));
        Optional<List<Column>> key = conflictKey(t);
        if (key.isPresent()) {
            sb.append("\nON CONFLICT (")
                    .append(key.get().stream().map(c -> Identifiers.quote(c.name())).collect(Collectors.joining(", ")))
                    .append(')');
            List<Column> rest = cols.stream().filter(c -> !key.get().contains(c)).toList();
            if (rest.isEmpty()) {
                sb.append(" DO NOTHING");
            } else {
                sb.append(" DO UPDATE SET ").append(rest.stream()
                        .map(c -> Identifiers.quote(c.name()) + " = EXCLUDED." + Identifiers.quote(c.name()))
                        .collect(Collectors.joining(", ")));
            }
        }
        sb.append(';');
        for (Column c : cols) {
            if (c.identity() != Identity.NONE) {
                sb.append("\nSELECT setval(pg_get_serial_sequence(").append(Identifiers.literal(table)).append(", ")
                        .append(Identifiers.literal(c.name())).append("), (SELECT max(").append(Identifiers.quote(c.name()))
                        .append(") FROM ").append(table).append("));");
            }
        }
        return sb.toString();
    }

    /** Deletes the seeded rows by their key; {@code null} when there is no key to delete by. */
    static String delete(Table t) {
        Optional<List<Column>> key = conflictKey(t);
        if (key.isEmpty()) {
            return null;
        }
        List<Column> k = key.get();
        String target = k.size() == 1 ? Identifiers.quote(k.get(0).name())
                : "(" + k.stream().map(c -> Identifiers.quote(c.name())).collect(Collectors.joining(", ")) + ")";
        String values = t.rows().stream()
                .filter(r -> !r.values().isEmpty())
                .map(r -> {
                    String v = k.stream().map(c -> value(c, r.value(c.id()))).collect(Collectors.joining(", "));
                    return k.size() == 1 ? v : "(" + v + ")";
                })
                .collect(Collectors.joining(", "));
        return "DELETE FROM " + Identifiers.qualified(t.schema(), t.name()) + " WHERE " + target + " IN (" + values + ");";
    }

    /**
     * A value as SQL. Numbers and booleans in matching columns stay bare for readability; everything else is a
     * string literal, which PostgreSQL coerces to the column type in {@code INSERT ... VALUES}.
     */
    static String value(Column c, String v) {
        if (v == null) {
            return "DEFAULT";
        }
        String type = ModelValidator.normalizeType(c.type());
        if (NUMERIC_TYPE.matcher(type).matches() && NUMBER.matcher(v.trim()).matches()) {
            return v.trim();
        }
        String lower = v.trim().toLowerCase(Locale.ROOT);
        if (type.equals("boolean") && (lower.equals("true") || lower.equals("false"))) {
            return lower;
        }
        return Identifiers.literal(v);
    }
}
