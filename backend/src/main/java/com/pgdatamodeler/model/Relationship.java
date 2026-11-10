package com.pgdatamodeler.model;

import java.util.List;

/**
 * A foreign key: {@code fromTableId} is the child (referencing) table, {@code toTableId} the parent.
 */
public record Relationship(
        String id,
        String name,
        String fromTableId,
        String toTableId,
        List<ColumnPair> columns,
        FkAction onDelete,
        FkAction onUpdate
) {
    public Relationship {
        columns = columns == null ? List.of() : List.copyOf(columns);
        onDelete = onDelete == null ? FkAction.NO_ACTION : onDelete;
        onUpdate = onUpdate == null ? FkAction.NO_ACTION : onUpdate;
    }

    public Relationship withColumns(List<ColumnPair> newColumns) {
        return new Relationship(id, name, fromTableId, toTableId, newColumns, onDelete, onUpdate);
    }
}
