package com.datagocar.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.List;
import java.util.Optional;

/**
 * The whole database design document. Stored as JSON and edited through operations.
 */
public record DataModel(
        String name,
        List<Table> tables,
        List<Relationship> relationships,
        List<EnumType> enums,
        List<View> views
) {
    @JsonCreator
    public DataModel {
        name = name == null ? "Untitled model" : name;
        tables = tables == null ? List.of() : List.copyOf(tables);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        enums = enums == null ? List.of() : List.copyOf(enums);
        views = views == null ? List.of() : List.copyOf(views);
    }

    /** A model without views. */
    public DataModel(String name, List<Table> tables, List<Relationship> relationships, List<EnumType> enums) {
        this(name, tables, relationships, enums, List.of());
    }

    public static DataModel empty(String name) {
        return new DataModel(name, List.of(), List.of(), List.of(), List.of());
    }

    public Optional<Table> table(String id) {
        return tables.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public Optional<View> view(String id) {
        return views.stream().filter(v -> v.id().equals(id)).findFirst();
    }

    public DataModel withName(String newName) {
        return new DataModel(newName, tables, relationships, enums, views);
    }

    public DataModel withTables(List<Table> newTables) {
        return new DataModel(name, newTables, relationships, enums, views);
    }

    public DataModel withRelationships(List<Relationship> newRelationships) {
        return new DataModel(name, tables, newRelationships, enums, views);
    }

    public DataModel withEnums(List<EnumType> newEnums) {
        return new DataModel(name, tables, relationships, newEnums, views);
    }

    public DataModel withViews(List<View> newViews) {
        return new DataModel(name, tables, relationships, enums, newViews);
    }
}
