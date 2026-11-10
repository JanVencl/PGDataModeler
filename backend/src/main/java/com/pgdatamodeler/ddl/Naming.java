package com.pgdatamodeler.ddl;

import com.pgdatamodeler.model.CheckConstraint;
import com.pgdatamodeler.model.Column;
import com.pgdatamodeler.model.DataModel;
import com.pgdatamodeler.model.Index;
import com.pgdatamodeler.model.Model;
import com.pgdatamodeler.model.Relationship;
import com.pgdatamodeler.model.Table;

import java.util.List;
import java.util.stream.Collectors;

/** Default constraint and index names, following PostgreSQL's own conventions where it has them. */
public final class Naming {

    private Naming() {
    }

    public static String primaryKey(Table t) {
        return Identifiers.truncate(t.name() + "_pkey");
    }

    public static String unique(Table t, Column c) {
        return Identifiers.truncate(t.name() + "_" + c.name() + "_key");
    }

    public static String check(Table t, CheckConstraint c, int position) {
        return Model.isBlank(c.name()) ? Identifiers.truncate(t.name() + "_check" + (position == 0 ? "" : position)) : c.name();
    }

    public static String foreignKey(DataModel m, Relationship r) {
        if (!Model.isBlank(r.name())) {
            return r.name();
        }
        Table from = m.table(r.fromTableId()).orElseThrow();
        String cols = r.columns().stream()
                .map(p -> from.column(p.fromColumnId()).map(Column::name).orElse("?"))
                .collect(Collectors.joining("_"));
        return Identifiers.truncate(from.name() + "_" + cols + "_fkey");
    }

    public static String index(Table t, Index i) {
        if (!Model.isBlank(i.name())) {
            return i.name();
        }
        return Identifiers.truncate(t.name() + "_" + String.join("_", columnNames(t, i.columnIds())) + (i.unique() ? "_key" : "_idx"));
    }

    public static List<String> columnNames(Table t, List<String> columnIds) {
        return columnIds.stream().map(id -> t.column(id).map(Column::name).orElse("?")).toList();
    }
}
