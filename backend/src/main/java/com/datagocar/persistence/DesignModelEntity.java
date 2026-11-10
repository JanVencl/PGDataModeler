package com.datagocar.persistence;

import com.datagocar.model.DataModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "design_model")
public class DesignModelEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Incremented by exactly one for every applied operation. */
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private DataModel document;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DesignModelEntity() {
    }

    public DesignModelEntity(UUID id, DataModel document) {
        this.id = id;
        this.name = document.name();
        this.document = document;
        this.version = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(DataModel newDocument) {
        this.document = newDocument;
        this.name = newDocument.name();
        this.version++;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getVersion() {
        return version;
    }

    public DataModel getDocument() {
        return document;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
