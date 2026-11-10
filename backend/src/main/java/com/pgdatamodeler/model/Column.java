package com.pgdatamodeler.model;

/**
 * A table column. {@code type} is a raw PostgreSQL type expression (e.g. {@code varchar(255)},
 * {@code numeric(19,4)}, {@code text[]} or the name of a model enum). {@code defaultValue} is a raw
 * SQL expression (e.g. {@code now()}, {@code 0}, {@code 'active'}).
 */
public record Column(
        String id,
        String name,
        String type,
        boolean nullable,
        boolean primaryKey,
        boolean unique,
        String defaultValue,
        Identity identity,
        String comment
) {
    public Column {
        identity = identity == null ? Identity.NONE : identity;
        type = Model.isBlank(type) ? "text" : type.trim();
    }

    /** Primary key and identity columns can never be null, whatever the flag says. */
    public boolean effectivelyNotNull() {
        return !nullable || primaryKey || identity != Identity.NONE;
    }
}
