package com.pgdatamodeler.service;

import com.pgdatamodeler.model.DataModel;
import com.pgdatamodeler.ops.ModelOperation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Data transfer objects shared by REST and WebSocket endpoints. */
public final class Dtos {
    private Dtos() {
    }

    public record ModelSummary(UUID id, String name, long version, Instant updatedAt) {
    }

    public record ModelSnapshot(UUID id, long version, DataModel document) {
    }

    public record CreateModelRequest(String name) {
    }

    /** Result of a plain-SQL import: the new model plus what could not be imported. */
    public record SqlImportResponse(ModelSummary model, List<String> warnings) {
    }

    /** Preview of merging a SQL script into an existing model: the merged document (not saved) and warnings. */
    public record SqlMergeResponse(DataModel document, List<String> warnings) {
    }

    /** Sent by a client; {@code clientOpId} lets the sender recognise its own op in the broadcast. */
    public record OperationRequest(String clientOpId, String author, ModelOperation op) {
    }

    public enum OperationStatus { APPLIED, REJECTED }

    /**
     * Broadcast to every subscriber of a model. For APPLIED events {@code version} is the new model version
     * (always previous + 1); for REJECTED events it is the unchanged current version.
     */
    public record OperationEvent(UUID modelId, long version, OperationStatus status, String clientOpId,
                                 String author, ModelOperation op, String error) {
    }

    public record HistoryEntry(long version, String author, Instant at, String operation) {
    }
}
