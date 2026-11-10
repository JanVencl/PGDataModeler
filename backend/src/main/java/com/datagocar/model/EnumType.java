package com.datagocar.model;

import java.util.List;

/** A PostgreSQL enum type ({@code CREATE TYPE ... AS ENUM}). */
public record EnumType(String id, String schema, String name, List<String> values) {
    public EnumType {
        schema = Model.schemaOrDefault(schema);
        values = values == null ? List.of() : List.copyOf(values);
    }
}
