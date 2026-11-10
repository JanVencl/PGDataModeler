package com.pgdatamodeler.model;

import java.util.List;

/**
 * Index on a table. {@code method} is the access method (btree, hash, gin, gist, brin);
 * {@code where} is an optional partial-index predicate.
 */
public record Index(String id, String name, List<String> columnIds, boolean unique, String method, String where) {
    public Index {
        columnIds = columnIds == null ? List.of() : List.copyOf(columnIds);
        method = Model.isBlank(method) ? "btree" : method.trim().toLowerCase();
    }

    public Index withColumnIds(List<String> ids) {
        return new Index(id, name, ids, unique, method, where);
    }
}
