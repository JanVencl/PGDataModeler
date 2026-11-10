package com.datagocar.model;

/** One column mapping of a foreign key: child (referencing) column to parent (referenced) column. */
public record ColumnPair(String fromColumnId, String toColumnId) {
}
