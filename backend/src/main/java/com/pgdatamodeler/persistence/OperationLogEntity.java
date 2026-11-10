package com.pgdatamodeler.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "model_operation")
public class OperationLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false)
    private UUID modelId;

    @Column(nullable = false)
    private long version;

    private String author;

    /** The operation as JSON (kept as text so the polymorphic type tag is preserved verbatim). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String operation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OperationLogEntity() {
    }

    public OperationLogEntity(UUID modelId, long version, String author, String operation) {
        this.modelId = modelId;
        this.version = version;
        this.author = author;
        this.operation = operation;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getModelId() {
        return modelId;
    }

    public long getVersion() {
        return version;
    }

    public String getAuthor() {
        return author;
    }

    public String getOperation() {
        return operation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
