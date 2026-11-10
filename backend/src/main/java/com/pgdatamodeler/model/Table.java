package com.pgdatamodeler.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.List;
import java.util.Optional;

public record Table(
        String id,
        String schema,
        String name,
        String comment,
        List<Column> columns,
        List<Index> indexes,
        List<CheckConstraint> checks,
        Position position,
        String color,
        TableKind kind,
        List<Row> rows
) {
    /** Most rows a table may carry: the whole design is one document shared live with every editor. */
    public static final int MAX_ROWS = 1000;

    @JsonCreator
    public Table {
        schema = Model.schemaOrDefault(schema);
        columns = columns == null ? List.of() : List.copyOf(columns);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        checks = checks == null ? List.of() : List.copyOf(checks);
        position = position == null ? new Position(0, 0) : position;
        kind = kind == null ? TableKind.ENTITY : kind;
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** A table without seed data, as before lookup tables existed. */
    public Table(String id, String schema, String name, String comment, List<Column> columns, List<Index> indexes,
                 List<CheckConstraint> checks, Position position, String color) {
        this(id, schema, name, comment, columns, indexes, checks, position, color, TableKind.ENTITY, List.of());
    }

    public Optional<Column> column(String columnId) {
        return columns.stream().filter(c -> c.id().equals(columnId)).findFirst();
    }

    public List<Column> primaryKeyColumns() {
        return columns.stream().filter(Column::primaryKey).toList();
    }

    public Table withPosition(Position p) {
        return new Table(id, schema, name, comment, columns, indexes, checks, p, color, kind, rows);
    }

    public Table withIndexes(List<Index> newIndexes) {
        return new Table(id, schema, name, comment, columns, newIndexes, checks, position, color, kind, rows);
    }

    public Table withRows(List<Row> newRows) {
        return new Table(id, schema, name, comment, columns, indexes, checks, position, color, kind, newRows);
    }

    public Table withKind(TableKind newKind) {
        return new Table(id, schema, name, comment, columns, indexes, checks, position, color, newKind, rows);
    }
}
