package com.datagocar.ddl;

import com.datagocar.model.Column;
import com.datagocar.model.DataModel;
import com.datagocar.model.EnumType;
import com.datagocar.model.Identity;
import com.datagocar.model.Index;
import com.datagocar.model.Model;
import com.datagocar.model.Relationship;
import com.datagocar.model.Row;
import com.datagocar.model.Table;
import com.datagocar.model.TableKind;
import com.datagocar.model.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Design lint: problems that would break the DDL (ERROR) or are likely mistakes (WARNING). */
public final class ModelValidator {

    public enum Level { ERROR, WARNING }

    public record Issue(Level level, String objectId, String message) {
    }

    private static final Pattern SNAKE = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern QUERY_START = Pattern.compile("\\(*\\s*(select|with|values|table)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\{?[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}}?");
    private static final Pattern SIZED_CHAR = Pattern.compile("(varchar|character varying|char|character)\\((\\d+)\\)");
    private static final Set<String> BOOLEANS = Set.of("true", "false", "t", "f", "yes", "no", "y", "n", "on", "off", "1", "0");
    /** Row problems reported per table, so one broken column does not flood the list. */
    private static final int MAX_ROW_ISSUES = 20;

    public List<Issue> validate(DataModel model) {
        List<Issue> issues = new ArrayList<>();
        Set<String> tableNames = new HashSet<>();
        Set<String> enumNames = new HashSet<>();
        for (EnumType e : model.enums()) {
            checkName(issues, e.id(), "Enum", e.name());
            if (!enumNames.add(e.schema() + "." + e.name())) {
                issues.add(new Issue(Level.ERROR, e.id(), "Duplicate enum " + e.schema() + "." + e.name()));
            }
            if (e.values().isEmpty()) {
                issues.add(new Issue(Level.WARNING, e.id(), "Enum " + e.name() + " has no values"));
            }
            if (new HashSet<>(e.values()).size() != e.values().size()) {
                issues.add(new Issue(Level.ERROR, e.id(), "Enum " + e.name() + " has duplicate values"));
            }
        }
        for (Table t : model.tables()) {
            checkName(issues, t.id(), "Table", t.name());
            if (!tableNames.add(t.schema() + "." + t.name())) {
                issues.add(new Issue(Level.ERROR, t.id(), "Duplicate table " + t.schema() + "." + t.name()));
            }
            if (t.columns().isEmpty()) {
                issues.add(new Issue(Level.WARNING, t.id(), "Table " + t.name() + " has no columns"));
            }
            if (t.primaryKeyColumns().isEmpty()) {
                issues.add(new Issue(Level.WARNING, t.id(), "Table " + t.name() + " has no primary key"));
            }
            Set<String> columnNames = new HashSet<>();
            for (Column c : t.columns()) {
                checkName(issues, t.id(), "Column " + t.name() + ".", c.name());
                if (!columnNames.add(c.name())) {
                    issues.add(new Issue(Level.ERROR, t.id(), "Duplicate column " + t.name() + "." + c.name()));
                }
                if (c.identity() != Identity.NONE && !Model.isBlank(c.defaultValue())) {
                    issues.add(new Issue(Level.WARNING, t.id(),
                            t.name() + "." + c.name() + ": identity columns cannot have a default (ignored)"));
                }
                if (c.identity() != Identity.NONE && !isIntegerType(c.type())) {
                    issues.add(new Issue(Level.ERROR, t.id(),
                            t.name() + "." + c.name() + ": identity requires smallint, integer or bigint"));
                }
                String lower = c.type().toLowerCase(Locale.ROOT);
                if (lower.startsWith("serial") || lower.startsWith("bigserial") || lower.startsWith("smallserial")) {
                    issues.add(new Issue(Level.WARNING, t.id(),
                            t.name() + "." + c.name() + ": prefer an identity column over serial"));
                }
                if (lower.equals("timestamp") || lower.equals("timestamp without time zone")) {
                    issues.add(new Issue(Level.WARNING, t.id(),
                            t.name() + "." + c.name() + ": consider timestamptz instead of timestamp"));
                }
            }
            for (Index i : t.indexes()) {
                if (i.columnIds().isEmpty()) {
                    issues.add(new Issue(Level.ERROR, t.id(), "Index on " + t.name() + " has no columns"));
                }
            }
            validateRows(model, t, issues);
        }
        for (View v : model.views()) {
            checkName(issues, v.id(), v.materialized() ? "Materialized view" : "View", v.name());
            if (!tableNames.add(v.schema() + "." + v.name())) {
                issues.add(new Issue(Level.ERROR, v.id(), "View " + v.schema() + "." + v.name()
                        + " has the same name as another table or view"));
            }
            if (Model.isBlank(v.definition())) {
                issues.add(new Issue(Level.ERROR, v.id(), "View " + v.name() + " has no query"));
            } else if (!QUERY_START.matcher(v.definition()).lookingAt()) {
                issues.add(new Issue(Level.WARNING, v.id(), "View " + v.name()
                        + ": the definition should be a query (SELECT, WITH, VALUES or TABLE)"));
            } else if (ViewDependencies.of(model, v).isEmpty()) {
                issues.add(new Issue(Level.WARNING, v.id(), "View " + v.name()
                        + " reads no table or view of this model (renamed table?)"));
            }
        }
        for (Relationship r : model.relationships()) {
            validateRelationship(model, r, issues);
            validateRowReferences(model, r, issues);
        }
        return issues;
    }

    private void validateRelationship(DataModel model, Relationship r, List<Issue> issues) {
        var from = model.table(r.fromTableId());
        var to = model.table(r.toTableId());
        if (from.isEmpty() || to.isEmpty()) {
            issues.add(new Issue(Level.ERROR, r.id(), "Relationship references a missing table"));
            return;
        }
        Set<String> referenced = new HashSet<>();
        for (var p : r.columns()) {
            var fc = from.get().column(p.fromColumnId());
            var tc = to.get().column(p.toColumnId());
            if (fc.isEmpty() || tc.isEmpty()) {
                issues.add(new Issue(Level.ERROR, r.id(), "Relationship references a missing column"));
                return;
            }
            referenced.add(tc.get().id());
            if (!normalizeType(fc.get().type()).equals(normalizeType(tc.get().type()))) {
                issues.add(new Issue(Level.WARNING, r.id(), "FK " + from.get().name() + "." + fc.get().name()
                        + " (" + fc.get().type() + ") does not match " + to.get().name() + "." + tc.get().name()
                        + " (" + tc.get().type() + ")"));
            }
        }
        Set<String> pk = new HashSet<>(to.get().primaryKeyColumns().stream().map(Column::id).toList());
        boolean referencesKey = referenced.equals(pk)
                || (referenced.size() == 1 && to.get().column(referenced.iterator().next()).map(Column::unique).orElse(false))
                || to.get().indexes().stream().anyMatch(i -> i.unique() && Model.isBlank(i.where())
                && new HashSet<>(i.columnIds()).equals(referenced));
        if (!referencesKey) {
            issues.add(new Issue(Level.ERROR, r.id(), "FK to " + to.get().name()
                    + " must reference its primary key or a unique constraint"));
        }
        boolean childIndexed = from.get().primaryKeyColumns().stream().map(Column::id).toList()
                .equals(r.columns().stream().map(p -> p.fromColumnId()).toList())
                || from.get().indexes().stream().anyMatch(i -> i.columnIds().size() >= r.columns().size()
                && i.columnIds().subList(0, r.columns().size()).equals(r.columns().stream().map(p -> p.fromColumnId()).toList()));
        if (!childIndexed) {
            issues.add(new Issue(Level.WARNING, r.id(), "FK columns on " + from.get().name()
                    + " are not indexed (slow deletes on " + to.get().name() + ")"));
        }
    }

    private void validateRows(DataModel model, Table t, List<Issue> issues) {
        if (t.kind() == TableKind.LOOKUP && t.rows().isEmpty()) {
            issues.add(new Issue(Level.WARNING, t.id(), "Lookup table " + t.name() + " has no data rows"));
        }
        if (t.rows().isEmpty()) {
            return;
        }
        int reported = 0;
        for (int n = 0; n < t.rows().size() && reported < MAX_ROW_ISSUES; n++) {
            Row row = t.rows().get(n);
            for (Column c : t.columns()) {
                String v = row.value(c.id());
                String where = t.name() + " row " + (n + 1) + ", " + c.name();
                if (v == null) {
                    if (c.effectivelyNotNull() && c.identity() == Identity.NONE && Model.isBlank(c.defaultValue())) {
                        issues.add(new Issue(Level.ERROR, t.id(), where + ": a value is required (NOT NULL, no default)"));
                        reported++;
                    }
                    continue;
                }
                String problem = valueProblem(model, c, v);
                if (problem != null) {
                    issues.add(new Issue(Level.ERROR, t.id(), where + ": " + problem));
                    reported++;
                }
            }
        }
        List<List<Column>> keys = new ArrayList<>();
        if (!t.primaryKeyColumns().isEmpty()) {
            keys.add(t.primaryKeyColumns());
        }
        t.columns().stream().filter(c -> c.unique() && !(c.primaryKey() && t.primaryKeyColumns().size() == 1))
                .forEach(c -> keys.add(List.of(c)));
        for (List<Column> key : keys) {
            Set<List<String>> seen = new HashSet<>();
            for (Row row : t.rows()) {
                List<String> k = key.stream().map(c -> row.value(c.id())).toList();
                if (k.contains(null)) {
                    continue;
                }
                if (!seen.add(k)) {
                    issues.add(new Issue(Level.ERROR, t.id(), t.name() + ": duplicate value " + String.join(", ", k) + " in "
                            + key.stream().map(Column::name).collect(Collectors.joining(", "))));
                    break;
                }
            }
        }
    }

    /** Child rows whose FK value has no matching row in the parent's seed data (when the parent has seed data). */
    private void validateRowReferences(DataModel model, Relationship r, List<Issue> issues) {
        var from = model.table(r.fromTableId());
        var to = model.table(r.toTableId());
        if (from.isEmpty() || to.isEmpty() || from.get().rows().isEmpty() || to.get().rows().isEmpty()) {
            return;
        }
        List<String> fromCols = r.columns().stream().map(p -> p.fromColumnId()).toList();
        List<String> toCols = r.columns().stream().map(p -> p.toColumnId()).toList();
        Set<List<String>> parentKeys = new HashSet<>();
        for (Row row : to.get().rows()) {
            parentKeys.add(toCols.stream().map(row::value).toList());
        }
        for (int n = 0; n < from.get().rows().size(); n++) {
            Row row = from.get().rows().get(n);
            List<String> k = fromCols.stream().map(row::value).toList();
            if (!k.contains(null) && !parentKeys.contains(k)) {
                issues.add(new Issue(Level.ERROR, r.id(), from.get().name() + " row " + (n + 1) + ": "
                        + String.join(", ", k) + " has no matching row in " + to.get().name()));
                return;
            }
        }
    }

    /** Why {@code v} cannot be stored in column {@code c}, or {@code null} if it can (or the type is not checked). */
    static String valueProblem(DataModel model, Column c, String v) {
        String type = normalizeType(c.type());
        String trimmed = v.trim();
        String quoted = "\"" + v + "\"";
        try {
            switch (type) {
                case "smallint", "integer", "bigint" -> {
                    long n = Long.parseLong(trimmed.startsWith("+") ? trimmed.substring(1) : trimmed);
                    long max = type.equals("smallint") ? Short.MAX_VALUE : type.equals("integer") ? Integer.MAX_VALUE : Long.MAX_VALUE;
                    long min = type.equals("smallint") ? Short.MIN_VALUE : type.equals("integer") ? Integer.MIN_VALUE : Long.MIN_VALUE;
                    return n < min || n > max ? quoted + " is out of range for " + type : null;
                }
                case "boolean" -> {
                    return BOOLEANS.contains(trimmed.toLowerCase(Locale.ROOT)) ? null : quoted + " is not a boolean";
                }
                case "uuid" -> {
                    return UUID_PATTERN.matcher(trimmed).matches() ? null : quoted + " is not a uuid";
                }
                case "date" -> {
                    java.time.LocalDate.parse(trimmed);
                    return null;
                }
                default -> {
                }
            }
            if (type.startsWith("numeric") || type.startsWith("decimal") || type.equals("real") || type.equals("double precision")) {
                new java.math.BigDecimal(trimmed);
                return null;
            }
            Matcher len = SIZED_CHAR.matcher(type);
            if (len.matches() && v.length() > Integer.parseInt(len.group(2))) {
                return quoted + " is longer than " + len.group(2) + " characters";
            }
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return quoted + " is not a valid " + type;
        }
        for (EnumType e : model.enums()) {
            if (type.equals(e.name()) || type.equals(e.schema() + "." + e.name())) {
                return e.values().contains(v) ? null : quoted + " is not a value of enum " + e.name();
            }
        }
        return null;
    }

    private static void checkName(List<Issue> issues, String id, String kind, String name) {
        if (Model.isBlank(name)) {
            issues.add(new Issue(Level.ERROR, id, kind + " name is empty"));
            return;
        }
        if (name.length() > Identifiers.MAX_LENGTH) {
            issues.add(new Issue(Level.ERROR, id, kind + " " + name + " is longer than 63 characters"));
        }
        if (!SNAKE.matcher(name).matches()) {
            issues.add(new Issue(Level.WARNING, id, kind + " " + name + " is not lower snake_case (will need quoting)"));
        } else if (Identifiers.isReserved(name)) {
            issues.add(new Issue(Level.WARNING, id, kind + " " + name + " is a reserved word (will need quoting)"));
        }
    }

    private static boolean isIntegerType(String type) {
        String t = normalizeType(type);
        return t.equals("smallint") || t.equals("integer") || t.equals("bigint");
    }

    static String normalizeType(String type) {
        String t = type.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (t) {
            case "int", "int4" -> "integer";
            case "int8" -> "bigint";
            case "int2" -> "smallint";
            case "bool" -> "boolean";
            case "serial" -> "integer";
            case "bigserial" -> "bigint";
            case "smallserial" -> "smallint";
            default -> t;
        };
    }
}
