package com.pgdatamodeler.ops;

import com.pgdatamodeler.model.ColumnPair;
import com.pgdatamodeler.model.DataModel;
import com.pgdatamodeler.model.EnumType;
import com.pgdatamodeler.model.Index;
import com.pgdatamodeler.model.Model;
import com.pgdatamodeler.model.Position;
import com.pgdatamodeler.model.Relationship;
import com.pgdatamodeler.model.Row;
import com.pgdatamodeler.model.Table;
import com.pgdatamodeler.model.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure function applying an operation to a model. Keep in sync with {@code frontend/src/model/reducer.ts}.
 */
public final class OperationApplier {

    private OperationApplier() {
    }

    public static DataModel apply(DataModel model, ModelOperation op) {
        Objects.requireNonNull(op, "operation");
        return switch (op) {
            case ModelOperation.UpsertTable u -> upsertTable(model, u.table());
            case ModelOperation.DeleteTable d -> deleteTable(model, d.tableId());
            case ModelOperation.MoveTable m -> moveTable(model, m);
            case ModelOperation.UpsertRelationship u -> upsertRelationship(model, u.relationship());
            case ModelOperation.DeleteRelationship d -> model.withRelationships(
                    model.relationships().stream().filter(r -> !r.id().equals(d.relationshipId())).toList());
            case ModelOperation.UpsertEnum u -> upsertEnum(model, u.enumType());
            case ModelOperation.DeleteEnum d -> model.withEnums(
                    model.enums().stream().filter(e -> !e.id().equals(d.enumId())).toList());
            case ModelOperation.UpsertView u -> upsertView(model, u.view());
            case ModelOperation.DeleteView d -> model.withViews(
                    model.views().stream().filter(v -> !v.id().equals(d.viewId())).toList());
            case ModelOperation.MoveView m -> model.withViews(model.views().stream()
                    .map(v -> v.id().equals(m.viewId()) ? v.withPosition(new Position(m.x(), m.y())) : v)
                    .toList());
            case ModelOperation.RenameModel r -> {
                if (Model.isBlank(r.name())) {
                    throw new InvalidOperationException("Model name must not be blank");
                }
                yield model.withName(r.name().trim());
            }
        };
    }

    private static DataModel upsertTable(DataModel model, Table table) {
        if (table == null || Model.isBlank(table.id())) {
            throw new InvalidOperationException("Table id is required");
        }
        if (Model.isBlank(table.name())) {
            throw new InvalidOperationException("Table name is required");
        }
        Set<String> columnIds = new HashSet<>();
        for (var c : table.columns()) {
            if (Model.isBlank(c.id()) || Model.isBlank(c.name())) {
                throw new InvalidOperationException("Every column needs an id and a name");
            }
            if (!columnIds.add(c.id())) {
                throw new InvalidOperationException("Duplicate column id " + c.id());
            }
        }
        // Drop index references to columns that no longer exist; drop indexes left with no columns.
        List<Index> indexes = table.indexes().stream()
                .map(i -> i.withColumnIds(i.columnIds().stream().filter(columnIds::contains).toList()))
                .filter(i -> !i.columnIds().isEmpty())
                .toList();
        if (table.rows().size() > Table.MAX_ROWS) {
            throw new InvalidOperationException("A table can carry at most " + Table.MAX_ROWS + " data rows");
        }
        Set<String> rowIds = new HashSet<>();
        for (Row r : table.rows()) {
            if (Model.isBlank(r.id()) || !rowIds.add(r.id())) {
                throw new InvalidOperationException("Every data row needs a unique id");
            }
        }
        // Drop row values of columns that no longer exist.
        List<Row> rows = table.rows().stream()
                .map(r -> r.values().keySet().stream().allMatch(columnIds::contains) ? r
                        : new Row(r.id(), r.values().entrySet().stream()
                        .filter(e -> columnIds.contains(e.getKey()))
                        .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll)))
                .toList();
        Table cleaned = table.withIndexes(indexes).withRows(rows);

        DataModel updated = model.withTables(replaceOrAppend(model.tables(), cleaned, Table::id));
        // Drop foreign-key column pairs that reference removed columns; drop FKs left empty.
        List<Relationship> rels = updated.relationships().stream()
                .map(r -> {
                    if (!r.fromTableId().equals(table.id()) && !r.toTableId().equals(table.id())) {
                        return r;
                    }
                    List<ColumnPair> pairs = r.columns().stream()
                            .filter(p -> columnExists(updated, r.fromTableId(), p.fromColumnId())
                                    && columnExists(updated, r.toTableId(), p.toColumnId()))
                            .toList();
                    return r.withColumns(pairs);
                })
                .filter(r -> !r.columns().isEmpty())
                .toList();
        return updated.withRelationships(rels);
    }

    private static DataModel deleteTable(DataModel model, String tableId) {
        return model
                .withTables(model.tables().stream().filter(t -> !t.id().equals(tableId)).toList())
                .withRelationships(model.relationships().stream()
                        .filter(r -> !r.fromTableId().equals(tableId) && !r.toTableId().equals(tableId))
                        .toList());
    }

    private static DataModel moveTable(DataModel model, ModelOperation.MoveTable m) {
        // Moving a table someone else just deleted is harmless: ignore it.
        return model.withTables(model.tables().stream()
                .map(t -> t.id().equals(m.tableId()) ? t.withPosition(new Position(m.x(), m.y())) : t)
                .toList());
    }

    private static DataModel upsertRelationship(DataModel model, Relationship rel) {
        if (rel == null || Model.isBlank(rel.id())) {
            throw new InvalidOperationException("Relationship id is required");
        }
        var from = model.table(rel.fromTableId())
                .orElseThrow(() -> new InvalidOperationException("Unknown child table " + rel.fromTableId()));
        var to = model.table(rel.toTableId())
                .orElseThrow(() -> new InvalidOperationException("Unknown parent table " + rel.toTableId()));
        if (rel.columns().isEmpty()) {
            throw new InvalidOperationException("A relationship needs at least one column pair");
        }
        for (ColumnPair p : rel.columns()) {
            if (from.column(p.fromColumnId()).isEmpty()) {
                throw new InvalidOperationException("Unknown column " + p.fromColumnId() + " in " + from.name());
            }
            if (to.column(p.toColumnId()).isEmpty()) {
                throw new InvalidOperationException("Unknown column " + p.toColumnId() + " in " + to.name());
            }
        }
        return model.withRelationships(replaceOrAppend(model.relationships(), rel, Relationship::id));
    }

    private static DataModel upsertEnum(DataModel model, EnumType e) {
        if (e == null || Model.isBlank(e.id()) || Model.isBlank(e.name())) {
            throw new InvalidOperationException("Enum id and name are required");
        }
        return model.withEnums(replaceOrAppend(model.enums(), e, EnumType::id));
    }

    private static DataModel upsertView(DataModel model, View v) {
        if (v == null || Model.isBlank(v.id()) || Model.isBlank(v.name())) {
            throw new InvalidOperationException("View id and name are required");
        }
        return model.withViews(replaceOrAppend(model.views(), v, View::id));
    }

    private static boolean columnExists(DataModel model, String tableId, String columnId) {
        return model.table(tableId).flatMap(t -> t.column(columnId)).isPresent();
    }

    private static <T> List<T> replaceOrAppend(List<T> list, T item, Function<T, String> id) {
        List<T> result = new ArrayList<>(list.size() + 1);
        boolean replaced = false;
        for (T existing : list) {
            if (id.apply(existing).equals(id.apply(item))) {
                result.add(item);
                replaced = true;
            } else {
                result.add(existing);
            }
        }
        if (!replaced) {
            result.add(item);
        }
        return result;
    }
}
