package com.datagocar.service;

import com.datagocar.ddl.PostgresDdlImporter;
import com.datagocar.model.DataModel;
import com.datagocar.model.Model;
import com.datagocar.persistence.DesignModelEntity;
import com.datagocar.persistence.DesignModelRepository;
import com.datagocar.persistence.OperationLogRepository;
import com.datagocar.service.Dtos.HistoryEntry;
import com.datagocar.service.Dtos.ModelSnapshot;
import com.datagocar.service.Dtos.ModelSummary;
import com.datagocar.service.Dtos.SqlImportResponse;
import com.datagocar.service.Dtos.SqlMergeResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ModelService {

    private final DesignModelRepository models;
    private final OperationLogRepository operations;

    public ModelService(DesignModelRepository models, OperationLogRepository operations) {
        this.models = models;
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public List<ModelSummary> list() {
        return models.findAllByOrderByUpdatedAtDesc().stream().map(ModelService::summary).toList();
    }

    @Transactional
    public ModelSummary create(String name) {
        String n = Model.isBlank(name) ? "Untitled model" : name.trim();
        return summary(models.save(new DesignModelEntity(UUID.randomUUID(), DataModel.empty(n))));
    }

    /** Imports a full document (e.g. a JSON export) as a new model. */
    @Transactional
    public ModelSummary importDocument(DataModel document) {
        return summary(models.save(new DesignModelEntity(UUID.randomUUID(), document)));
    }

    /** Parses a PostgreSQL DDL script (hand-written or {@code pg_dump --schema-only}) into a new model. */
    @Transactional
    public SqlImportResponse importSql(String sql, String name) {
        PostgresDdlImporter.Result result = PostgresDdlImporter.parse(sql, name);
        return new SqlImportResponse(importDocument(result.model()), result.warnings());
    }

    /**
     * Merges a DDL script into an existing model and returns the result without saving it; the editor applies
     * the differences as ordinary operations so every collaborator sees them.
     */
    @Transactional(readOnly = true)
    public SqlMergeResponse mergeSql(UUID id, String sql, boolean replaceExisting) {
        PostgresDdlImporter.Result result = PostgresDdlImporter.merge(sql, find(id).getDocument(), replaceExisting);
        return new SqlMergeResponse(result.model(), result.warnings());
    }

    @Transactional(readOnly = true)
    public ModelSnapshot snapshot(UUID id) {
        DesignModelEntity e = find(id);
        return new ModelSnapshot(e.getId(), e.getVersion(), e.getDocument());
    }

    @Transactional(readOnly = true)
    public DataModel document(UUID id) {
        return find(id).getDocument();
    }

    @Transactional
    public void delete(UUID id) {
        models.delete(find(id));
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID id, int limit) {
        find(id);
        return operations.findByModelIdOrderByVersionDesc(id, PageRequest.of(0, Math.min(Math.max(limit, 1), 500)))
                .stream()
                .map(o -> new HistoryEntry(o.getVersion(), o.getAuthor(), o.getCreatedAt(), o.getOperation()))
                .toList();
    }

    private DesignModelEntity find(UUID id) {
        return models.findById(id).orElseThrow(() -> new NotFoundException("Model " + id + " not found"));
    }

    private static ModelSummary summary(DesignModelEntity e) {
        return new ModelSummary(e.getId(), e.getName(), e.getVersion(), e.getUpdatedAt());
    }
}
