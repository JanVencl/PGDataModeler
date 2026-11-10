package com.pgdatamodeler.ops;

import com.pgdatamodeler.model.EnumType;
import com.pgdatamodeler.model.Relationship;
import com.pgdatamodeler.model.Table;
import com.pgdatamodeler.model.View;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * An edit to a {@link com.pgdatamodeler.model.DataModel}. Operations replace whole objects
 * (last writer wins per object) and are idempotent, so they can be safely re-sent after a reconnect.
 * The same set of operations is implemented by the frontend reducer ({@code frontend/src/model/reducer.ts}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ModelOperation.UpsertTable.class, name = "upsertTable"),
        @JsonSubTypes.Type(value = ModelOperation.DeleteTable.class, name = "deleteTable"),
        @JsonSubTypes.Type(value = ModelOperation.MoveTable.class, name = "moveTable"),
        @JsonSubTypes.Type(value = ModelOperation.UpsertRelationship.class, name = "upsertRelationship"),
        @JsonSubTypes.Type(value = ModelOperation.DeleteRelationship.class, name = "deleteRelationship"),
        @JsonSubTypes.Type(value = ModelOperation.UpsertEnum.class, name = "upsertEnum"),
        @JsonSubTypes.Type(value = ModelOperation.DeleteEnum.class, name = "deleteEnum"),
        @JsonSubTypes.Type(value = ModelOperation.UpsertView.class, name = "upsertView"),
        @JsonSubTypes.Type(value = ModelOperation.DeleteView.class, name = "deleteView"),
        @JsonSubTypes.Type(value = ModelOperation.MoveView.class, name = "moveView"),
        @JsonSubTypes.Type(value = ModelOperation.RenameModel.class, name = "renameModel")
})
public sealed interface ModelOperation {

    record UpsertTable(Table table) implements ModelOperation {
    }

    record DeleteTable(String tableId) implements ModelOperation {
    }

    record MoveTable(String tableId, double x, double y) implements ModelOperation {
    }

    record UpsertRelationship(Relationship relationship) implements ModelOperation {
    }

    record DeleteRelationship(String relationshipId) implements ModelOperation {
    }

    record UpsertEnum(EnumType enumType) implements ModelOperation {
    }

    record DeleteEnum(String enumId) implements ModelOperation {
    }

    record UpsertView(View view) implements ModelOperation {
    }

    record DeleteView(String viewId) implements ModelOperation {
    }

    record MoveView(String viewId, double x, double y) implements ModelOperation {
    }

    record RenameModel(String name) implements ModelOperation {
    }
}
