package com.datagocar.ddl;

import java.util.Set;
import java.util.regex.Pattern;

/** PostgreSQL identifier and literal helpers. */
public final class Identifiers {

    public static final int MAX_LENGTH = 63;

    private static final Pattern SIMPLE = Pattern.compile("[a-z_][a-z0-9_$]*");

    /** PostgreSQL reserved key words (including those reserved "can be function or type"). */
    static final Set<String> RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "authorization",
            "binary", "both", "case", "cast", "check", "collate", "collation", "column", "concurrently",
            "constraint", "create", "cross", "current_catalog", "current_date", "current_role", "current_schema",
            "current_time", "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct", "do",
            "else", "end", "except", "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant", "group",
            "having", "ilike", "in", "initially", "inner", "intersect", "into", "is", "isnull", "join", "lateral",
            "leading", "left", "like", "limit", "localtime", "localtimestamp", "natural", "not", "notnull", "null",
            "offset", "on", "only", "or", "order", "outer", "overlaps", "placing", "primary", "references",
            "returning", "right", "select", "session_user", "similar", "some", "symmetric", "system_user", "table",
            "tablesample", "then", "to", "trailing", "true", "union", "unique", "user", "using", "variadic",
            "verbose", "when", "where", "window", "with");

    private Identifiers() {
    }

    public static boolean needsQuoting(String name) {
        return !SIMPLE.matcher(name).matches() || RESERVED.contains(name);
    }

    public static boolean isReserved(String name) {
        return RESERVED.contains(name.toLowerCase());
    }

    public static String quote(String name) {
        return needsQuoting(name) ? '"' + name.replace("\"", "\"\"") + '"' : name;
    }

    public static String qualified(String schema, String name) {
        return quote(schema) + "." + quote(name);
    }

    public static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** Truncates generated names to PostgreSQL's 63-byte identifier limit. */
    public static String truncate(String name) {
        return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH);
    }
}
