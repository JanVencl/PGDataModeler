package com.pgdatamodeler.web;

import com.pgdatamodeler.model.DataModel;
import com.pgdatamodeler.service.CollaborationService;
import com.pgdatamodeler.service.Dtos.CreateModelRequest;
import com.pgdatamodeler.service.Dtos.HistoryEntry;
import com.pgdatamodeler.service.Dtos.ModelSnapshot;
import com.pgdatamodeler.service.Dtos.ModelSummary;
import com.pgdatamodeler.service.Dtos.OperationEvent;
import com.pgdatamodeler.service.Dtos.OperationRequest;
import com.pgdatamodeler.service.Dtos.SqlImportResponse;
import com.pgdatamodeler.service.Dtos.SqlMergeResponse;
import com.pgdatamodeler.service.ModelService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelService models;
    private final CollaborationService collaboration;

    public ModelController(ModelService models, CollaborationService collaboration) {
        this.models = models;
        this.collaboration = collaboration;
    }

    @GetMapping
    public List<ModelSummary> list() {
        return models.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelSummary create(@RequestBody(required = false) CreateModelRequest request) {
        return models.create(request == null ? null : request.name());
    }

    /** Imports a document previously downloaded via {@code GET /api/models/{id}/export?format=json}. */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ModelSummary importModel(@RequestBody DataModel document) {
        return models.importDocument(document);
    }

    /**
     * Creates a model from a plain PostgreSQL DDL script sent as the request body (text/plain), e.g.
     * {@code curl --data-binary @schema.sql -H 'Content-Type: text/plain' '.../api/models/import-sql?name=Shop'}.
     */
    @PostMapping(value = "/import-sql", consumes = {"text/plain", "application/sql", "application/octet-stream"})
    @ResponseStatus(HttpStatus.CREATED)
    public SqlImportResponse importSql(@RequestBody String sql, @RequestParam(required = false) String name) {
        return models.importSql(sql, name);
    }

    /**
     * Previews merging a DDL script (request body, text/plain) into an existing model. Nothing is saved: the editor
     * submits the resulting changes as operations. {@code existing=keep} skips CREATE statements for objects the
     * model already has; the default {@code replace} redefines them (keeping ids, positions and column ids by name).
     */
    @PostMapping(value = "/{id}/merge-sql", consumes = {"text/plain", "application/sql", "application/octet-stream"})
    public SqlMergeResponse mergeSql(@PathVariable UUID id, @RequestBody String sql,
                                     @RequestParam(defaultValue = "replace") String existing) {
        return models.mergeSql(id, sql, !"keep".equalsIgnoreCase(existing));
    }

    @GetMapping("/{id}")
    public ModelSnapshot get(@PathVariable UUID id) {
        return models.snapshot(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        models.delete(id);
    }

    /** HTTP alternative to the WebSocket channel (scripts, CI); the result is broadcast to live editors too. */
    @PostMapping("/{id}/operations")
    public OperationEvent submit(@PathVariable UUID id, @RequestBody OperationRequest request) {
        return collaboration.submit(id, request);
    }

    @GetMapping("/{id}/history")
    public List<HistoryEntry> history(@PathVariable UUID id, @RequestParam(defaultValue = "100") int limit) {
        return models.history(id, limit);
    }
}
