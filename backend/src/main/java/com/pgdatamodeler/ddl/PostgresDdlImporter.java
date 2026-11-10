package com.pgdatamodeler.ddl;

import com.pgdatamodeler.ddl.SqlLexer.Kind;
import com.pgdatamodeler.ddl.SqlLexer.Statement;
import com.pgdatamodeler.ddl.SqlLexer.Token;
import com.pgdatamodeler.model.CheckConstraint;
import com.pgdatamodeler.model.Column;
import com.pgdatamodeler.model.ColumnPair;
import com.pgdatamodeler.model.DataModel;
import com.pgdatamodeler.model.EnumType;
import com.pgdatamodeler.model.FkAction;
import com.pgdatamodeler.model.Identity;
import com.pgdatamodeler.model.Index;
import com.pgdatamodeler.model.Model;
import com.pgdatamodeler.model.Position;
import com.pgdatamodeler.model.Relationship;
import com.pgdatamodeler.model.Row;
import com.pgdatamodeler.model.Table;
import com.pgdatamodeler.model.TableKind;
import com.pgdatamodeler.model.View;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Builds a {@link DataModel} from a plain PostgreSQL DDL script: hand-written DDL, {@code pg_dump --schema-only}
 * output, PGDataModeler's own SQL / Liquibase formatted-SQL export.
 *
 * <p>Understood: {@code CREATE TABLE} (columns, inline and table constraints), {@code CREATE TYPE ... AS ENUM},
 * {@code ALTER TABLE ... ADD CONSTRAINT / ADD COLUMN / ALTER COLUMN}, {@code CREATE [UNIQUE] INDEX},
 * {@code CREATE [MATERIALIZED] VIEW}, {@code COMMENT ON TABLE / COLUMN / [MATERIALIZED] VIEW}, {@code SET search_path}, {@code INSERT INTO ... VALUES} (rows become seed data
 * and the table a lookup table). Statements that are not part of the data model
 * (views, functions, grants, data, ...) are skipped and reported as warnings. {@code serial} columns and
 * {@code nextval()} defaults become identity columns.
 */
public final class PostgresDdlImporter {

    public record Result(DataModel model, List<String> warnings) {
    }

    /** Statements that carry nothing for the data model and are dropped without a warning. */
    private static final Set<String> SILENT = Set.of(
            "set", "select", "begin", "commit", "rollback", "start", "end", "grant", "revoke", "copy",
            "analyze", "vacuum", "reset", "savepoint", "release", "do", "drop", "lock", "truncate", "update", "delete");
    private static final Set<String> SILENT_CREATE = Set.of("sequence", "extension", "schema", "role", "user");
    private static final Set<String> COLUMN_CONSTRAINT_START = Set.of(
            "constraint", "not", "null", "default", "primary", "unique", "check", "references", "generated",
            "collate", "deferrable", "initially", "compression", "storage");
    private static final Set<String> TABLE_CONSTRAINT_START = Set.of(
            "constraint", "primary", "unique", "check", "foreign", "exclude", "like");
    private static final Pattern CAST_LITERAL = Pattern.compile("^('(?:[^']|'')*')::[\\w .\"\\[\\]]+(\\(\\s*\\d+(\\s*,\\s*\\d+)?\\s*\\))?(\\[])?$");
    private static final Pattern NEXTVAL = Pattern.compile("^nextval\\(.*\\)$", Pattern.CASE_INSENSITIVE);

    private static final class TableBuilder {
        final String id;
        final String schema;
        final String name;
        /** The table already in the model this script is merged into, or null for a new table. */
        final Table base;
        /** True once a CREATE TABLE of the script replaced the base definition. */
        boolean redefined;
        String comment;
        final List<ColumnBuilder> columns = new ArrayList<>();
        final List<Index> indexes = new ArrayList<>();
        final List<CheckConstraint> checks = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();

        TableBuilder(String schema, String name) {
            this(newId(), schema, name, null);
        }

        TableBuilder(String id, String schema, String name, Table base) {
            this.id = id;
            this.schema = schema;
            this.name = name;
            this.base = base;
        }

        /** Builder holding an existing table, so later statements can alter it. */
        static TableBuilder of(Table t) {
            TableBuilder b = new TableBuilder(t.id(), t.schema(), t.name(), t);
            b.comment = t.comment();
            for (Column c : t.columns()) {
                ColumnBuilder col = new ColumnBuilder(c.id(), c.name());
                col.type = c.type();
                col.nullable = c.nullable();
                col.primaryKey = c.primaryKey();
                col.unique = c.unique();
                col.defaultValue = c.defaultValue();
                col.identity = c.identity();
                col.comment = c.comment();
                b.columns.add(col);
            }
            b.indexes.addAll(t.indexes());
            b.checks.addAll(t.checks());
            b.rows.addAll(t.rows());
            return b;
        }

        /** Column id to use for a column of this name: the base table's, so references to it survive. */
        String columnId(String columnName) {
            if (base != null) {
                for (Column c : base.columns()) {
                    if (c.name().equals(columnName)) {
                        return c.id();
                    }
                }
            }
            return newId();
        }

        Optional<ColumnBuilder> column(String columnName) {
            return columns.stream().filter(c -> c.name.equals(columnName)).findFirst();
        }
    }

    private static final class ColumnBuilder {
        final String id;
        final String name;
        String type;
        boolean nullable = true;
        boolean primaryKey;
        boolean unique;
        String defaultValue;
        Identity identity = Identity.NONE;
        String comment;

        ColumnBuilder(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class ViewBuilder {
        final String id;
        final String schema;
        final String name;
        final boolean materialized;
        final String definition;
        /** The view already in the model this script is merged into, or null for a new view. */
        final View base;
        boolean redefined;
        String comment;

        ViewBuilder(String id, String schema, String name, boolean materialized, String definition, View base) {
            this.id = id;
            this.schema = schema;
            this.name = name;
            this.materialized = materialized;
            this.definition = definition;
            this.base = base;
        }
    }

    private record PendingFk(String name, TableBuilder from, List<String> fromColumns, QName parent,
                             List<String> parentColumns, FkAction onDelete, FkAction onUpdate, int line) {
    }

    private record QName(String schema, String name) {
        @Override
        public String toString() {
            return schema == null ? name : schema + "." + name;
        }
    }

    private final String src;
    private final Map<String, TableBuilder> tables = new LinkedHashMap<>();
    private final Map<String, EnumType> enums = new LinkedHashMap<>();
    private final Map<String, ViewBuilder> views = new LinkedHashMap<>();
    private double layoutBottom;
    /** Top-left corner of the grid new tables and views are placed on. */
    private double originX;
    private double originY;
    private final List<PendingFk> foreignKeys = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Integer> skipped = new TreeMap<>();
    private String defaultSchema = Model.DEFAULT_SCHEMA;
    private int convertedToIdentity;
    /** The model the script is merged into, or null when building a new model. */
    private final DataModel base;
    /** Merge mode: whether CREATE statements for objects already in the base model replace them or are skipped. */
    private final boolean replaceExisting;
    private final Set<String> baseEnumKeys = new java.util.HashSet<>();
    private final Set<String> redefinedEnumKeys = new java.util.HashSet<>();

    private PostgresDdlImporter(String src, DataModel base, boolean replaceExisting) {
        this.src = src;
        this.base = base;
        this.replaceExisting = replaceExisting;
    }

    /**
     * @throws IllegalArgumentException when the script contains no table and no enum type at all
     */
    public static Result parse(String sql, String modelName) {
        return new PostgresDdlImporter(sql == null ? "" : sql, null, false).run(modelName);
    }

    /**
     * Applies a DDL script on top of an existing model and returns the merged model; nothing is saved.
     * Tables, views and enums keep their ids (and so their positions, colours and foreign keys); columns keep
     * their ids by name. {@code ALTER TABLE}, {@code COMMENT ON}, {@code INSERT} and foreign keys may refer to
     * objects that are only in the existing model. New tables are placed to the right of the existing ones.
     *
     * @param replaceExisting true: a {@code CREATE} for an object that already exists replaces its definition;
     *                        false: such statements are skipped and the existing definition is kept
     */
    public static Result merge(String sql, DataModel base, boolean replaceExisting) {
        return new PostgresDdlImporter(sql == null ? "" : sql, base, replaceExisting).run(base.name());
    }

    private void seed() {
        for (Table t : base.tables()) {
            tables.put(t.schema() + "." + t.name(), TableBuilder.of(t));
        }
        for (EnumType e : base.enums()) {
            String key = Model.schemaOrDefault(e.schema()) + "." + e.name();
            enums.put(key, e);
            baseEnumKeys.add(key);
        }
        for (View v : base.views()) {
            ViewBuilder b = new ViewBuilder(v.id(), v.schema(), v.name(), v.materialized(), v.definition(), v);
            b.comment = v.comment();
            views.put(v.schema() + "." + v.name(), b);
        }
    }

    private Result run(String modelName) {
        if (base != null) {
            seed();
        }
        for (Statement st : SqlLexer.split(src)) {
            try {
                statement(new Cursor(st));
            } catch (RuntimeException e) {
                warnings.add("Line " + st.line() + ": could not read \"" + preview(st) + "\" (" + e.getMessage() + ")");
            }
        }
        if (base == null && tables.isEmpty() && enums.isEmpty() && views.isEmpty()) {
            throw new IllegalArgumentException("No CREATE TABLE, CREATE VIEW or CREATE TYPE ... AS ENUM statements found"
                    + (warnings.isEmpty() ? "" : ": " + warnings.get(0)));
        }
        List<Relationship> relationships = resolveForeignKeys();
        if (convertedToIdentity > 0) {
            warnings.add(convertedToIdentity + " serial / nextval() column(s) converted to identity columns.");
        }
        if (!skipped.isEmpty()) {
            StringBuilder sb = new StringBuilder("Skipped statements that are not part of the data model: ");
            skipped.forEach((k, v) -> sb.append(k).append(" (").append(v).append("), "));
            warnings.add(sb.substring(0, sb.length() - 2) + ".");
        }
        String name = Model.isBlank(modelName) ? "Imported SQL" : modelName.trim();
        List<Table> laidOut = layout();
        List<View> viewList = new ArrayList<>();
        int i = 0;
        for (ViewBuilder v : views.values()) {
            if (v.base != null) {
                viewList.add(new View(v.id, v.schema, v.name, v.comment, v.materialized, v.definition,
                        v.base.position(), v.base.color()));
                continue;
            }
            viewList.add(new View(v.id, v.schema, v.name, v.comment, v.materialized, v.definition,
                    new Position(originX + (i % 4) * 320.0, layoutBottom + (i / 4) * 140.0), null));
            i++;
        }
        return new Result(new DataModel(name, laidOut, relationships, List.copyOf(enums.values()), viewList),
                List.copyOf(warnings));
    }

    // ---------------------------------------------------------------- statements

    private void statement(Cursor c) {
        String first = c.peek().lower();
        if (c.acceptWords("create")) {
            c.acceptWords("or", "replace");
            if (c.peek().isWord("table") || c.peekWords("unlogged", "table") || c.peekWords("global", "temporary")
                    || c.peekWords("local", "temporary") || c.peekWords("temporary", "table") || c.peekWords("temp", "table")) {
                while (!c.acceptWords("table")) {
                    c.next();
                }
                createTable(c);
            } else if (c.peek().isWord("view") || c.peekWords("materialized", "view")
                    || c.peekWords("temp", "view") || c.peekWords("temporary", "view")) {
                boolean materialized = c.acceptWords("materialized");
                c.acceptWords("temp");
                c.acceptWords("temporary");
                c.acceptWords("view");
                createView(c, materialized);
            } else if (c.peekWords("recursive", "view")) {
                skip("CREATE RECURSIVE VIEW");
            } else if (c.peek().isWord("type")) {
                c.next();
                createType(c);
            } else if (c.peek().isWord("index") || c.peekWords("unique", "index")) {
                createIndex(c);
            } else if (SILENT_CREATE.contains(c.peek().lower())) {
                if (c.peek().isWord("schema")) {
                    c.next();
                }
            } else {
                skip("CREATE " + objectKind(c));
            }
        } else if (c.acceptWords("alter", "table")) {
            alterTable(c);
        } else if (c.acceptWords("insert", "into")) {
            insert(c);
        } else if (c.acceptWords("comment", "on")) {
            comment(c);
        } else if (c.peek().isWord("set") && c.peekWords("set", "search_path")) {
            setSearchPath(c);
        } else if (first.equals("alter")) {
            // ALTER SEQUENCE / TYPE / SCHEMA ... OWNER TO and the like: no model content
        } else if (!SILENT.contains(first)) {
            skip(c.peek().text().toUpperCase(Locale.ROOT));
        }
    }

    private static String objectKind(Cursor c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2 && !c.atEnd(); i++) {
            Token t = c.next();
            if (t.kind() != Kind.WORD) {
                break;
            }
            sb.append(sb.isEmpty() ? "" : " ").append(t.text().toUpperCase(Locale.ROOT));
            if (!Set.of("materialized", "unique", "recursive", "temp", "temporary", "event", "foreign", "text", "constraint")
                    .contains(t.lower())) {
                break;
            }
        }
        return sb.toString();
    }

    private void skip(String what) {
        skipped.merge(what, 1, Integer::sum);
    }

    private void setSearchPath(Cursor c) {
        c.next();
        c.next();
        if (!c.acceptSymbol("=")) {
            c.acceptWords("to");
        }
        if (!c.atEnd() && (c.peek().kind() == Kind.WORD || c.peek().kind() == Kind.QUOTED_IDENT || c.peek().kind() == Kind.STRING)) {
            String s = c.peek().kind() == Kind.WORD ? c.next().lower() : c.next().text();
            if (!s.isBlank() && !s.equals("pg_catalog") && !s.equals("$user")) {
                defaultSchema = s;
            }
        }
    }

    /** {@code CREATE [MATERIALIZED] VIEW [IF NOT EXISTS] name [(cols)] [USING m] [WITH (...)] [TABLESPACE t] AS query [WITH [NO] DATA]}. */
    private void createView(Cursor c, boolean materialized) {
        c.acceptWords("if", "not", "exists");
        QName qn = c.qualifiedName();
        String schema = schemaOf(qn);
        String key = schema + "." + qn.name();
        if (!c.atEnd() && c.peek().isSymbol("(")) {
            warnings.add("View " + key + ": the column name list (" + c.raw(c.parenGroup())
                    + ") was dropped; alias the columns in the query instead.");
        }
        while (!c.atEnd() && !c.peek().isWord("as")) {
            if (c.acceptWords("with")) {
                c.parenGroup();
            } else {
                c.next(); // USING method, TABLESPACE name
            }
        }
        if (!c.acceptWords("as")) {
            throw new IllegalArgumentException("expected AS");
        }
        List<Token> query = new ArrayList<>(c.rest());
        if (materialized) {
            int n = query.size();
            if (n >= 3 && query.get(n - 3).isWord("with") && query.get(n - 2).isWord("no") && query.get(n - 1).isWord("data")) {
                query = query.subList(0, n - 3);
            } else if (n >= 2 && query.get(n - 2).isWord("with") && query.get(n - 1).isWord("data")) {
                query = query.subList(0, n - 2);
            }
        }
        if (query.isEmpty()) {
            throw new IllegalArgumentException("view has no query");
        }
        // Keep the original formatting of the query.
        String definition = src.substring(query.get(0).start(), query.get(query.size() - 1).end());
        ViewBuilder existing = views.get(key);
        if (existing != null && existing.base != null && !existing.redefined) {
            if (!replaceExisting) {
                warnings.add("View " + key + " already exists in the model; kept the existing definition.");
                return;
            }
            ViewBuilder v = new ViewBuilder(existing.id, schema, qn.name(), materialized, definition, existing.base);
            v.comment = existing.comment;
            v.redefined = true;
            views.put(key, v);
            warnings.add("View " + key + " already existed in the model; its definition was replaced.");
            return;
        }
        if (existing != null || tables.containsKey(key)) {
            warnings.add("View " + key + " is defined twice or clashes with a table; the second definition was ignored.");
            return;
        }
        views.put(key, new ViewBuilder(newId(), schema, qn.name(), materialized, definition, null));
    }

    private Optional<ViewBuilder> findView(QName qn) {
        if (qn.schema() != null) {
            return Optional.ofNullable(views.get(qn.schema() + "." + qn.name()));
        }
        ViewBuilder inDefault = views.get(defaultSchema + "." + qn.name());
        if (inDefault != null) {
            return Optional.of(inDefault);
        }
        List<ViewBuilder> byName = views.values().stream().filter(v -> v.name.equals(qn.name())).toList();
        return byName.size() == 1 ? Optional.of(byName.get(0)) : Optional.empty();
    }

    private void createType(Cursor c) {
        QName name = c.qualifiedName();
        if (!c.acceptWords("as", "enum")) {
            skip("CREATE TYPE (non-enum)");
            return;
        }
        List<String> values = new ArrayList<>();
        for (List<Token> item : c.parenList()) {
            if (item.size() != 1 || item.get(0).kind() != Kind.STRING) {
                throw new IllegalArgumentException("enum values must be string literals");
            }
            values.add(item.get(0).text());
        }
        String schema = schemaOf(name);
        String key = schema + "." + name.name();
        if (baseEnumKeys.contains(key) && !redefinedEnumKeys.contains(key)) {
            if (!replaceExisting) {
                warnings.add("Enum " + key + " already exists in the model; kept the existing values.");
                return;
            }
            redefinedEnumKeys.add(key);
            enums.put(key, new EnumType(enums.get(key).id(), schema, name.name(), values));
            warnings.add("Enum " + key + " already existed in the model; its values were replaced.");
            return;
        }
        enums.put(key, new EnumType(newId(), schema, name.name(), values));
    }

    private void createTable(Cursor c) {
        c.acceptWords("if", "not", "exists");
        QName qn = c.qualifiedName();
        if (c.atEnd() || !c.peek().isSymbol("(")) {
            skip(!c.atEnd() && c.peek().isWord("partition") ? "CREATE TABLE ... PARTITION OF" : "CREATE TABLE ... AS / OF");
            return;
        }
        String schema = schemaOf(qn);
        String key = schema + "." + qn.name();
        TableBuilder existing = tables.get(key);
        if (existing != null && existing.base != null && !existing.redefined && !replaceExisting) {
            warnings.add("Table " + key + " already exists in the model; kept the existing definition.");
            return;
        }
        if (existing != null && (existing.base == null || existing.redefined)) {
            warnings.add("Table " + key + " is defined twice; the second definition was ignored.");
            return;
        }
        TableBuilder t = existing == null
                ? new TableBuilder(schema, qn.name())
                : new TableBuilder(existing.id, schema, qn.name(), existing.base);
        // columns first: table constraints may name columns declared after them
        List<List<Token>> constraints = new ArrayList<>();
        for (List<Token> element : c.parenList()) {
            if (element.isEmpty()) {
                continue;
            }
            if (element.get(0).kind() == Kind.WORD && TABLE_CONSTRAINT_START.contains(element.get(0).lower())) {
                constraints.add(element);
            } else {
                t.columns.add(columnDefinition(t, c.sub(element)));
            }
        }
        for (List<Token> element : constraints) {
            try {
                tableConstraint(t, c.sub(element));
            } catch (RuntimeException e) {
                warnings.add("Line " + c.line() + ": constraint \"" + c.raw(element) + "\" of table " + key
                        + " skipped (" + e.getMessage() + ").");
            }
        }
        if (existing != null) {
            // Redefining an existing table: keep what plain DDL cannot carry (comments added in the editor, data rows).
            t.redefined = true;
            t.comment = existing.comment;
            for (ColumnBuilder col : t.columns) {
                if (col.comment == null) {
                    existing.column(col.name).ifPresent(old -> col.comment = old.comment);
                }
            }
            t.rows.addAll(existing.rows);
            warnings.add("Table " + key + " already existed in the model; its definition was replaced.");
        }
        tables.put(key, t);
        // remaining options (INHERITS, PARTITION BY, WITH, TABLESPACE) carry nothing for the model
    }

    private ColumnBuilder columnDefinition(TableBuilder t, Cursor c) {
        String columnName = c.identifier();
        ColumnBuilder col = new ColumnBuilder(t.columnId(columnName), columnName);
        List<Token> typeTokens = c.takeUntil(tok -> tok.kind() == Kind.WORD && COLUMN_CONSTRAINT_START.contains(tok.lower()));
        if (typeTokens.isEmpty()) {
            throw new IllegalArgumentException("column " + col.name + " has no type");
        }
        col.type = normalizeType(typeTokens);
        String lower = col.type.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "serial", "serial4" -> serialToIdentity(col, "integer");
            case "bigserial", "serial8" -> serialToIdentity(col, "bigint");
            case "smallserial", "serial2" -> serialToIdentity(col, "smallint");
            default -> {
            }
        }
        columnConstraints(t, col, c);
        return col;
    }

    private void serialToIdentity(ColumnBuilder col, String type) {
        col.type = type;
        col.identity = Identity.BY_DEFAULT;
        convertedToIdentity++;
    }

    private void columnConstraints(TableBuilder t, ColumnBuilder col, Cursor c) {
        String constraintName = null;
        while (!c.atEnd()) {
            if (c.acceptWords("constraint")) {
                constraintName = c.identifier();
                continue;
            }
            if (c.acceptWords("not", "null")) {
                col.nullable = false;
            } else if (c.acceptWords("null")) {
                col.nullable = true;
            } else if (c.acceptWords("default")) {
                Token first = c.next();
                List<Token> rest = c.takeUntil(tok -> tok.kind() == Kind.WORD && COLUMN_CONSTRAINT_START.contains(tok.lower()));
                List<Token> expr = new ArrayList<>();
                expr.add(first);
                expr.addAll(rest);
                setDefault(col, c.raw(expr));
            } else if (c.acceptWords("primary", "key")) {
                col.primaryKey = true;
                skipIndexParameters(c);
            } else if (c.acceptWords("unique")) {
                c.acceptWords("nulls", "not", "distinct");
                c.acceptWords("nulls", "distinct");
                col.unique = true;
                skipIndexParameters(c);
            } else if (c.acceptWords("check")) {
                addCheck(t, constraintName, c.raw(c.parenGroup()));
                c.acceptWords("no", "inherit");
            } else if (c.acceptWords("references")) {
                foreignKeys.add(references(c, constraintName, t, List.of(col.name)));
            } else if (c.acceptWords("generated")) {
                generated(col, c);
            } else if (c.acceptWords("collate")) {
                c.qualifiedName();
            } else if (c.acceptWords("compression") || c.acceptWords("storage")) {
                c.next();
            } else if (c.acceptWords("deferrable") || c.acceptWords("not", "deferrable")) {
                // ignored
            } else if (c.acceptWords("initially")) {
                c.next();
            } else {
                throw new IllegalArgumentException("unexpected \"" + c.peek().text() + "\" in column " + col.name);
            }
            constraintName = null;
        }
    }

    private void generated(ColumnBuilder col, Cursor c) {
        Identity identity;
        if (c.acceptWords("always")) {
            identity = Identity.ALWAYS;
        } else if (c.acceptWords("by", "default")) {
            identity = Identity.BY_DEFAULT;
        } else {
            throw new IllegalArgumentException("expected ALWAYS or BY DEFAULT after GENERATED");
        }
        if (!c.acceptWords("as")) {
            throw new IllegalArgumentException("expected AS after GENERATED");
        }
        if (c.acceptWords("identity")) {
            col.identity = identity;
            col.defaultValue = null;
            if (!c.atEnd() && c.peek().isSymbol("(")) {
                c.parenGroup(); // sequence options
            }
        } else {
            String expr = c.raw(c.parenGroup());
            c.acceptWords("stored");
            c.acceptWords("virtual");
            warnings.add("Column " + col.name + " is a generated column (" + expr + "); imported as a plain column.");
        }
    }

    private void setDefault(ColumnBuilder col, String expression) {
        if (NEXTVAL.matcher(expression).matches() && isIntegerType(col.type)) {
            col.identity = Identity.BY_DEFAULT;
            col.defaultValue = null;
            convertedToIdentity++;
            return;
        }
        var m = CAST_LITERAL.matcher(expression);
        col.defaultValue = m.matches() ? m.group(1) : expression;
    }

    private static boolean isIntegerType(String type) {
        return Set.of("integer", "int", "int4", "bigint", "int8", "smallint", "int2").contains(type.toLowerCase(Locale.ROOT));
    }

    /** Skips {@code INCLUDE (...)}, {@code WITH (...)}, {@code USING INDEX TABLESPACE x} after PRIMARY KEY / UNIQUE. */
    private static void skipIndexParameters(Cursor c) {
        while (!c.atEnd()) {
            if (c.acceptWords("include") || c.acceptWords("with")) {
                c.parenGroup();
            } else if (c.acceptWords("using", "index", "tablespace")) {
                c.next();
            } else {
                return;
            }
        }
    }

    private void tableConstraint(TableBuilder t, Cursor c) {
        String name = c.acceptWords("constraint") ? c.identifier() : null;
        if (c.acceptWords("primary", "key")) {
            for (String colName : identifierList(c)) {
                ColumnBuilder col = t.column(colName).orElseThrow(() -> unknownColumn(t, colName));
                col.primaryKey = true;
            }
            skipIndexParameters(c);
        } else if (c.acceptWords("unique")) {
            c.acceptWords("nulls", "not", "distinct");
            c.acceptWords("nulls", "distinct");
            List<String> cols = identifierList(c);
            skipIndexParameters(c);
            if (cols.size() == 1) {
                t.column(cols.get(0)).orElseThrow(() -> unknownColumn(t, cols.get(0))).unique = true;
            } else {
                addIndex(t, name, cols, true, "btree", null);
            }
        } else if (c.acceptWords("check")) {
            addCheck(t, name, c.raw(c.parenGroup()));
            c.acceptWords("no", "inherit");
        } else if (c.acceptWords("foreign", "key")) {
            List<String> cols = identifierList(c);
            if (!c.acceptWords("references")) {
                throw new IllegalArgumentException("expected REFERENCES");
            }
            foreignKeys.add(references(c, name, t, cols));
        } else if (c.peek().isWord("exclude")) {
            warnings.add("Table " + t.schema + "." + t.name + ": EXCLUDE constraint " + (name == null ? "" : name + " ") + "skipped.");
            c.rest();
        } else if (c.peek().isWord("like")) {
            warnings.add("Table " + t.schema + "." + t.name + ": LIKE clause skipped.");
            c.rest();
        } else {
            throw new IllegalArgumentException("unsupported constraint \"" + c.peek().text() + "\"");
        }
        c.acceptWords("not", "valid");
    }

    private PendingFk references(Cursor c, String name, TableBuilder from, List<String> fromColumns) {
        int line = c.line();
        QName parent = c.qualifiedName();
        List<String> parentColumns = !c.atEnd() && c.peek().isSymbol("(") ? identifierList(c) : List.of();
        FkAction onDelete = FkAction.NO_ACTION;
        FkAction onUpdate = FkAction.NO_ACTION;
        while (!c.atEnd()) {
            if (c.acceptWords("match")) {
                c.next();
            } else if (c.acceptWords("on", "delete")) {
                onDelete = fkAction(c);
            } else if (c.acceptWords("on", "update")) {
                onUpdate = fkAction(c);
            } else if (c.acceptWords("deferrable") || c.acceptWords("not", "deferrable") || c.acceptWords("not", "valid")) {
                // ignored
            } else if (c.acceptWords("initially")) {
                c.next();
            } else {
                break;
            }
        }
        return new PendingFk(name, from, fromColumns, parent, parentColumns, onDelete, onUpdate, line);
    }

    private static FkAction fkAction(Cursor c) {
        if (c.acceptWords("cascade")) {
            return FkAction.CASCADE;
        }
        if (c.acceptWords("restrict")) {
            return FkAction.RESTRICT;
        }
        if (c.acceptWords("no", "action")) {
            return FkAction.NO_ACTION;
        }
        if (c.acceptWords("set", "null")) {
            skipOptionalColumnList(c);
            return FkAction.SET_NULL;
        }
        if (c.acceptWords("set", "default")) {
            skipOptionalColumnList(c);
            return FkAction.SET_DEFAULT;
        }
        throw new IllegalArgumentException("unknown referential action \"" + c.peek().text() + "\"");
    }

    private static void skipOptionalColumnList(Cursor c) {
        if (!c.atEnd() && c.peek().isSymbol("(")) {
            c.parenGroup();
        }
    }

    private void alterTable(Cursor c) {
        c.acceptWords("if", "exists");
        c.acceptWords("only");
        QName qn = c.qualifiedName();
        c.acceptSymbol("*");
        if (c.atEnd() || c.peek().isWord("owner") || c.peek().isWord("set") || c.peek().isWord("enable")
                || c.peek().isWord("disable") || c.peek().isWord("replica") || c.peek().isWord("cluster")
                || c.peek().isWord("attach") || c.peek().isWord("reset")) {
            return;
        }
        TableBuilder t = findTable(qn).orElseThrow(() -> new IllegalArgumentException("unknown table " + qn));
        for (List<Token> action : c.splitTopLevel(c.rest())) {
            Cursor a = c.sub(action);
            if (a.acceptWords("add")) {
                if (a.peek().kind() == Kind.WORD && TABLE_CONSTRAINT_START.contains(a.peek().lower())) {
                    tableConstraint(t, a);
                } else {
                    a.acceptWords("column");
                    a.acceptWords("if", "not", "exists");
                    ColumnBuilder col = columnDefinition(t, a);
                    if (t.column(col.name).isEmpty()) {
                        t.columns.add(col);
                    }
                }
            } else if (a.acceptWords("alter")) {
                a.acceptWords("column");
                String colName = a.identifier();
                ColumnBuilder col = t.column(colName).orElseThrow(() -> unknownColumn(t, colName));
                alterColumn(t, col, a);
            } else if (a.acceptWords("owner", "to")) {
                // ignored
            } else {
                warnings.add("Line " + c.line() + ": ALTER TABLE " + qn + " " + a.peek().text().toUpperCase(Locale.ROOT)
                        + " ... skipped.");
            }
        }
    }

    private void alterColumn(TableBuilder t, ColumnBuilder col, Cursor a) {
        if (a.acceptWords("set", "default")) {
            setDefault(col, a.raw(a.rest()));
        } else if (a.acceptWords("drop", "default")) {
            col.defaultValue = null;
        } else if (a.acceptWords("set", "not", "null")) {
            col.nullable = false;
        } else if (a.acceptWords("drop", "not", "null")) {
            col.nullable = true;
        } else if (a.acceptWords("add", "generated")) {
            generated(col, a);
        } else if (a.acceptWords("drop", "identity")) {
            col.identity = Identity.NONE;
        } else if (a.acceptWords("set", "data", "type") || a.acceptWords("type")) {
            col.type = normalizeType(a.takeUntil(tok -> tok.isWord("collate") || tok.isWord("using")));
        } else if (a.acceptWords("set") || a.acceptWords("reset")) {
            // statistics, storage, options: nothing for the model
        } else {
            warnings.add("ALTER COLUMN " + t.name + "." + col.name + " " + a.peek().text() + " ... skipped.");
        }
    }

    private void createIndex(Cursor c) {
        boolean unique = c.acceptWords("unique");
        c.acceptWords("index");
        c.acceptWords("concurrently");
        c.acceptWords("if", "not", "exists");
        String name = c.peek().isWord("on") ? null : c.identifier();
        if (!c.acceptWords("on")) {
            throw new IllegalArgumentException("expected ON");
        }
        c.acceptWords("only");
        QName qn = c.qualifiedName();
        TableBuilder t = findTable(qn).orElseThrow(() -> new IllegalArgumentException("unknown table " + qn));
        String method = c.acceptWords("using") ? c.next().lower() : "btree";
        List<String> columns = new ArrayList<>();
        for (List<Token> element : c.parenList()) {
            Token first = element.get(0);
            boolean plainColumn = (first.kind() == Kind.WORD || first.kind() == Kind.QUOTED_IDENT)
                    && (element.size() == 1 || element.get(1).kind() == Kind.WORD);
            if (!plainColumn) {
                warnings.add("Index " + (name == null ? "on " + t.name : name) + " uses an expression ("
                        + c.raw(element) + ") and was skipped.");
                return;
            }
            String colName = first.kind() == Kind.WORD ? first.lower() : first.text();
            if (t.column(colName).isEmpty()) {
                throw unknownColumn(t, colName);
            }
            columns.add(colName);
        }
        String where = null;
        while (!c.atEnd()) {
            if (c.acceptWords("include") || c.acceptWords("with")) {
                c.parenGroup();
            } else if (c.acceptWords("nulls", "not", "distinct") || c.acceptWords("nulls", "distinct")) {
                // ignored
            } else if (c.acceptWords("tablespace")) {
                c.next();
            } else if (c.acceptWords("where")) {
                where = c.raw(c.rest());
            } else {
                c.next();
            }
        }
        addIndex(t, name, columns, unique, method, where);
    }

    private void comment(Cursor c) {
        if (c.acceptWords("table")) {
            QName qn = c.qualifiedName();
            TableBuilder t = findTable(qn).orElseThrow(() -> new IllegalArgumentException("unknown table " + qn));
            t.comment = commentText(c);
        } else if (c.acceptWords("view") || c.acceptWords("materialized", "view")) {
            QName qn = c.qualifiedName();
            ViewBuilder v = findView(qn).orElseThrow(() -> new IllegalArgumentException("unknown view " + qn));
            v.comment = commentText(c);
        } else if (c.acceptWords("column")) {
            List<String> parts = c.dottedName();
            if (parts.size() < 2) {
                throw new IllegalArgumentException("expected table.column");
            }
            String colName = parts.get(parts.size() - 1);
            QName qn = new QName(parts.size() > 2 ? parts.get(parts.size() - 3) : null, parts.get(parts.size() - 2));
            TableBuilder t = findTable(qn).orElseThrow(() -> new IllegalArgumentException("unknown table " + qn));
            t.column(colName).orElseThrow(() -> unknownColumn(t, colName)).comment = commentText(c);
        }
        // comments on other objects are not part of the model
    }

    /** {@code INSERT INTO t [(cols)] [OVERRIDING ... VALUE] VALUES (...), ... [ON CONFLICT ...]}: rows become seed data. */
    private void insert(Cursor c) {
        QName qn = c.qualifiedName();
        TableBuilder t = findTable(qn).orElse(null);
        if (t == null) {
            skip("INSERT (unknown table)");
            return;
        }
        if (c.acceptWords("as")) {
            c.next();
        }
        List<String> columnNames = !c.atEnd() && c.peek().isSymbol("(") ? identifierList(c)
                : t.columns.stream().map(col -> col.name).toList();
        List<ColumnBuilder> cols = new ArrayList<>();
        for (String n : columnNames) {
            cols.add(t.column(n).orElseThrow(() -> unknownColumn(t, n)));
        }
        if (c.acceptWords("overriding")) {
            c.next();
            c.acceptWords("value");
        }
        if (!c.acceptWords("values")) {
            skip("INSERT ... SELECT");
            return;
        }
        int unreadable = 0;
        while (!c.atEnd() && c.peek().isSymbol("(")) {
            List<List<Token>> items = c.parenList();
            if (items.size() != cols.size()) {
                throw new IllegalArgumentException("VALUES row has " + items.size() + " values for " + cols.size() + " columns");
            }
            if (t.rows.size() >= Table.MAX_ROWS) {
                warnings.add("Table " + t.schema + "." + t.name + ": only the first " + Table.MAX_ROWS + " rows were imported.");
                return;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int k = 0; k < items.size(); k++) {
                Optional<String> v = literalValue(items.get(k));
                if (v == null) {
                    unreadable++;
                } else if (v.isPresent()) {
                    values.put(cols.get(k).id, v.get());
                }
            }
            t.rows.add(new Row(newId(), values));
            if (!c.acceptSymbol(",")) {
                break;
            }
        }
        if (unreadable > 0) {
            warnings.add("Table " + t.schema + "." + t.name + ": " + unreadable
                    + " value(s) were expressions, not literals, and were left empty.");
        }
    }

    /**
     * The text of a literal value: {@code Optional.empty()} for NULL / DEFAULT (left to the column default),
     * {@code null} when the value is an expression that cannot be stored as data.
     */
    private Optional<String> literalValue(List<Token> item) {
        if (item.size() == 1) {
            Token t = item.get(0);
            return switch (t.kind()) {
                case STRING, NUMBER -> Optional.of(t.text());
                case WORD -> switch (t.lower()) {
                    case "null", "default" -> Optional.empty();
                    case "true", "false" -> Optional.of(t.lower());
                    default -> null;
                };
                default -> null;
            };
        }
        if (item.size() == 2 && item.get(1).kind() == Kind.NUMBER && (item.get(0).isSymbol("-") || item.get(0).isSymbol("+"))) {
            return Optional.of(item.get(0).text() + item.get(1).text());
        }
        // 'value'::type
        if (item.size() >= 3 && item.get(0).kind() == Kind.STRING && item.get(1).isSymbol("::")) {
            return Optional.of(item.get(0).text());
        }
        return null;
    }

    private static String commentText(Cursor c) {
        if (!c.acceptWords("is")) {
            throw new IllegalArgumentException("expected IS");
        }
        Token t = c.next();
        return t.kind() == Kind.STRING ? t.text() : null;
    }

    // ---------------------------------------------------------------- model helpers

    private void addCheck(TableBuilder t, String name, String expression) {
        String expr = stripOuterParens(expression);
        CheckConstraint check = new CheckConstraint(newId(), null, expr);
        if (name != null && !name.equals(Naming.check(new Table(null, t.schema, t.name, null, null, null, null, null, null),
                check, t.checks.size()))) {
            check = new CheckConstraint(check.id(), name, expr);
        }
        t.checks.add(check);
    }

    private static void addIndex(TableBuilder t, String name, List<String> columnNames, boolean unique, String method, String where) {
        List<String> ids = columnNames.stream().map(n -> t.column(n).orElseThrow().id).toList();
        Index index = new Index(newId(), null, ids, unique, method, where == null ? null : stripOuterParens(where));
        String defaultName = Naming.index(snapshot(t), index);
        if (name != null && !name.equals(defaultName)) {
            index = new Index(index.id(), name, ids, unique, method, index.where());
        }
        t.indexes.add(index);
    }

    private List<Relationship> resolveForeignKeys() {
        List<Relationship> result = new ArrayList<>();
        Map<String, Table> built = new LinkedHashMap<>();
        tables.values().forEach(t -> built.put(t.id, snapshot(t)));
        if (base != null) {
            // Existing foreign keys stay, minus column pairs whose columns a redefinition removed.
            for (Relationship r : base.relationships()) {
                Table from = built.get(r.fromTableId());
                Table to = built.get(r.toTableId());
                List<ColumnPair> pairs = from == null || to == null ? List.of() : r.columns().stream()
                        .filter(p -> from.column(p.fromColumnId()).isPresent() && to.column(p.toColumnId()).isPresent())
                        .toList();
                if (pairs.isEmpty()) {
                    warnings.add("Foreign key " + (from == null ? "?" : from.name()) + " → " + (to == null ? "?" : to.name())
                            + " was removed: its columns no longer exist.");
                } else {
                    result.add(pairs.size() == r.columns().size() ? r : r.withColumns(pairs));
                }
            }
        }
        for (PendingFk fk : foreignKeys) {
            Optional<TableBuilder> parent = findTable(fk.parent());
            if (parent.isEmpty()) {
                warnings.add("Line " + fk.line() + ": foreign key of " + fk.from().name + " references unknown table "
                        + fk.parent() + "; skipped.");
                continue;
            }
            TableBuilder to = parent.get();
            List<String> parentColumns = fk.parentColumns().isEmpty()
                    ? to.columns.stream().filter(col -> col.primaryKey).map(col -> col.name).toList()
                    : fk.parentColumns();
            if (parentColumns.size() != fk.fromColumns().size()) {
                warnings.add("Line " + fk.line() + ": foreign key of " + fk.from().name + " has mismatched column lists; skipped.");
                continue;
            }
            List<ColumnPair> pairs = new ArrayList<>();
            for (int i = 0; i < parentColumns.size(); i++) {
                Optional<ColumnBuilder> fromCol = fk.from().column(fk.fromColumns().get(i));
                Optional<ColumnBuilder> toCol = to.column(parentColumns.get(i));
                if (fromCol.isEmpty() || toCol.isEmpty()) {
                    pairs = null;
                    break;
                }
                pairs.add(new ColumnPair(fromCol.get().id, toCol.get().id));
            }
            if (pairs == null) {
                warnings.add("Line " + fk.line() + ": foreign key of " + fk.from().name + " uses unknown columns; skipped.");
                continue;
            }
            List<ColumnPair> finalPairs = pairs;
            Optional<Relationship> same = result.stream()
                    .filter(e -> e.fromTableId().equals(fk.from().id) && e.toTableId().equals(to.id) && e.columns().equals(finalPairs))
                    .findFirst();
            if (same.isPresent()) {
                continue; // the model already has this foreign key
            }
            Relationship r = new Relationship(newId(), null, fk.from().id, to.id, pairs, fk.onDelete(), fk.onUpdate());
            DataModel probe = new DataModel("", List.copyOf(built.values()), List.of(), List.of());
            if (fk.name() != null && !fk.name().equals(Naming.foreignKey(probe, r))) {
                r = new Relationship(r.id(), fk.name(), r.fromTableId(), r.toTableId(), pairs, r.onDelete(), r.onUpdate());
            }
            result.add(r);
        }
        return result;
    }

    /** Places tables on a grid, roughly square, rows as tall as their tallest table. */
    private List<Table> layout() {
        List<Table> result = new ArrayList<>();
        List<TableBuilder> list = new ArrayList<>();
        for (TableBuilder t : tables.values()) {
            if (t.base == null) {
                list.add(t);
            } else {
                // Existing tables keep their place and colour; seed rows turn an entity into a lookup table.
                Table s = snapshot(t);
                TableKind kind = t.base.rows().isEmpty() && !t.rows.isEmpty() ? TableKind.LOOKUP : t.base.kind();
                result.add(new Table(s.id(), s.schema(), s.name(), s.comment(), s.columns(), s.indexes(), s.checks(),
                        t.base.position(), t.base.color(), kind, s.rows()));
            }
        }
        if (base != null && (!base.tables().isEmpty() || !base.views().isEmpty())) {
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            for (Table t : base.tables()) {
                maxX = Math.max(maxX, t.position().x());
                minY = Math.min(minY, t.position().y());
            }
            for (View v : base.views()) {
                maxX = Math.max(maxX, v.position().x());
                minY = Math.min(minY, v.position().y());
            }
            originX = Math.round(maxX + 400);
            originY = Math.round(minY);
        }
        int perRow = Math.max(1, (int) Math.ceil(Math.sqrt(list.size())));
        double y = originY;
        for (int row = 0; row * perRow < list.size(); row++) {
            int tallest = 0;
            for (int col = 0; col < perRow && row * perRow + col < list.size(); col++) {
                TableBuilder t = list.get(row * perRow + col);
                tallest = Math.max(tallest, t.columns.size());
                result.add(snapshot(t).withPosition(new Position(originX + col * 320.0, y)));
            }
            y += 80 + tallest * 26.0;
        }
        layoutBottom = y;
        return result;
    }

    private static Table snapshot(TableBuilder t) {
        List<Column> columns = t.columns.stream()
                .map(c -> new Column(c.id, c.name, c.type, c.nullable, c.primaryKey, c.unique, c.defaultValue, c.identity, c.comment))
                .toList();
        return new Table(t.id, t.schema, t.name, t.comment, columns, t.indexes, t.checks, null, null,
                t.rows.isEmpty() ? TableKind.ENTITY : TableKind.LOOKUP, t.rows);
    }

    private Optional<TableBuilder> findTable(QName qn) {
        if (qn.schema() != null) {
            return Optional.ofNullable(tables.get(qn.schema() + "." + qn.name()));
        }
        TableBuilder inDefault = tables.get(defaultSchema + "." + qn.name());
        if (inDefault != null) {
            return Optional.of(inDefault);
        }
        List<TableBuilder> byName = tables.values().stream().filter(t -> t.name.equals(qn.name())).toList();
        return byName.size() == 1 ? Optional.of(byName.get(0)) : Optional.empty();
    }

    private String schemaOf(QName qn) {
        return qn.schema() == null ? defaultSchema : qn.schema();
    }

    private static IllegalArgumentException unknownColumn(TableBuilder t, String column) {
        return new IllegalArgumentException("table " + t.name + " has no column " + column);
    }

    private List<String> identifierList(Cursor c) {
        List<String> names = new ArrayList<>();
        for (List<Token> item : c.parenList()) {
            names.add(c.sub(item).identifier());
        }
        return names;
    }

    private static final Pattern WS = Pattern.compile("\\s+");

    /** Lower-cases keywords and maps verbose SQL-standard spellings to the short PostgreSQL ones the editor offers. */
    private String normalizeType(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (i > 0 && t.start() > tokens.get(i - 1).end()) {
                sb.append(' ');
            }
            sb.append(t.kind() == Kind.WORD ? t.lower() : src.substring(t.start(), t.end()));
        }
        String type = WS.matcher(sb.toString()).replaceAll(" ").trim()
                .replace(" (", "(")
                .replaceAll("^pg_catalog\\.", "")
                .replaceAll("^public\\.", "")
                .replaceAll("^character varying", "varchar")
                .replaceAll("^character\\b", "char")
                .replaceAll("^timestamp(\\(\\d+\\))? with time zone", "timestamptz$1")
                .replaceAll("^timestamp(\\(\\d+\\))? without time zone", "timestamp$1")
                .replaceAll("^time(\\(\\d+\\))? with time zone", "timetz$1")
                .replaceAll("^time(\\(\\d+\\))? without time zone", "time$1");
        return type.replace("\"", "").equals(type) ? type : stripSimpleQuotes(type);
    }

    /** {@code "order_status"} → {@code order_status} when quoting is not needed. */
    private static String stripSimpleQuotes(String type) {
        if (type.matches("\"[a-z_][a-z0-9_$]*\"")) {
            return type.substring(1, type.length() - 1);
        }
        return type;
    }

    private static String stripOuterParens(String expression) {
        String e = expression.trim();
        while (e.startsWith("(") && e.endsWith(")") && closingParen(e, 0) == e.length() - 1) {
            e = e.substring(1, e.length() - 1).trim();
        }
        return e;
    }

    /** Index of the parenthesis closing the one at {@code open}, ignoring parentheses inside string literals. */
    private static int closingParen(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') {
                inString = !inString;
            } else if (!inString && ch == '(') {
                depth++;
            } else if (!inString && ch == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private String preview(Statement st) {
        Token first = st.tokens().get(0);
        Token last = st.tokens().get(st.tokens().size() - 1);
        String text = WS.matcher(src.substring(first.start(), last.end())).replaceAll(" ");
        return text.length() > 60 ? text.substring(0, 57) + "..." : text;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    // ---------------------------------------------------------------- token cursor

    private final class Cursor {
        private final List<Token> tokens;
        private final int line;
        private int i;

        Cursor(Statement st) {
            this(st.tokens(), st.line());
        }

        private Cursor(List<Token> tokens, int line) {
            this.tokens = tokens;
            this.line = line;
        }

        Cursor sub(List<Token> part) {
            return new Cursor(part, line);
        }

        int line() {
            return line;
        }

        boolean atEnd() {
            return i >= tokens.size();
        }

        Token peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of statement");
            }
            return tokens.get(i);
        }

        Token next() {
            Token t = peek();
            i++;
            return t;
        }

        boolean peekWords(String... words) {
            for (int k = 0; k < words.length; k++) {
                if (i + k >= tokens.size() || !tokens.get(i + k).isWord(words[k])) {
                    return false;
                }
            }
            return true;
        }

        /** Consumes the whole keyword sequence if it is next, otherwise nothing. */
        boolean acceptWords(String... words) {
            if (!peekWords(words)) {
                return false;
            }
            i += words.length;
            return true;
        }

        boolean acceptSymbol(String symbol) {
            if (!atEnd() && tokens.get(i).isSymbol(symbol)) {
                i++;
                return true;
            }
            return false;
        }

        String identifier() {
            Token t = next();
            return switch (t.kind()) {
                case WORD -> t.lower();
                case QUOTED_IDENT -> t.text();
                default -> throw new IllegalArgumentException("expected a name but found \"" + t.text() + "\"");
            };
        }

        List<String> dottedName() {
            List<String> parts = new ArrayList<>();
            parts.add(identifier());
            while (acceptSymbol(".")) {
                parts.add(identifier());
            }
            return parts;
        }

        QName qualifiedName() {
            List<String> parts = dottedName();
            return parts.size() == 1 ? new QName(null, parts.get(0))
                    : new QName(parts.get(parts.size() - 2), parts.get(parts.size() - 1));
        }

        /** The tokens inside the parenthesised group that starts here (parentheses excluded). */
        List<Token> parenGroup() {
            if (!next().isSymbol("(")) {
                throw new IllegalArgumentException("expected (");
            }
            int start = i;
            int depth = 1;
            while (true) {
                Token t = next();
                if (t.isSymbol("(")) {
                    depth++;
                } else if (t.isSymbol(")") && --depth == 0) {
                    return tokens.subList(start, i - 1);
                }
            }
        }

        /** The comma-separated items of the parenthesised group that starts here. */
        List<List<Token>> parenList() {
            return splitTopLevel(parenGroup());
        }

        List<List<Token>> splitTopLevel(List<Token> part) {
            List<List<Token>> items = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int k = 0; k < part.size(); k++) {
                Token t = part.get(k);
                if (t.isSymbol("(") || t.isSymbol("[")) {
                    depth++;
                } else if (t.isSymbol(")") || t.isSymbol("]")) {
                    depth--;
                } else if (t.isSymbol(",") && depth == 0) {
                    items.add(part.subList(start, k));
                    start = k + 1;
                }
            }
            if (start < part.size()) {
                items.add(part.subList(start, part.size()));
            }
            return items;
        }

        /** Consumes tokens up to (not including) the first top-level token matching {@code stop}. */
        List<Token> takeUntil(Predicate<Token> stop) {
            int start = i;
            int depth = 0;
            while (!atEnd()) {
                Token t = tokens.get(i);
                if (depth == 0 && stop.test(t)) {
                    break;
                }
                if (t.isSymbol("(") || t.isSymbol("[")) {
                    depth++;
                } else if (t.isSymbol(")") || t.isSymbol("]")) {
                    depth--;
                }
                i++;
            }
            return tokens.subList(start, i);
        }

        List<Token> rest() {
            List<Token> r = tokens.subList(i, tokens.size());
            i = tokens.size();
            return r;
        }

        /** Original source text of the tokens, with whitespace between them collapsed to one space. */
        String raw(List<Token> part) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < part.size(); k++) {
                Token t = part.get(k);
                if (k > 0 && t.start() > part.get(k - 1).end()) {
                    sb.append(' ');
                }
                sb.append(src, t.start(), t.end());
            }
            return sb.toString();
        }
    }
}
