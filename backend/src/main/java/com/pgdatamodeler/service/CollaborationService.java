package com.pgdatamodeler.service;

import com.pgdatamodeler.ops.InvalidOperationException;
import com.pgdatamodeler.ops.OperationApplier;
import com.pgdatamodeler.persistence.DesignModelEntity;
import com.pgdatamodeler.persistence.DesignModelRepository;
import com.pgdatamodeler.persistence.OperationLogEntity;
import com.pgdatamodeler.persistence.OperationLogRepository;
import com.pgdatamodeler.service.Dtos.OperationEvent;
import com.pgdatamodeler.service.Dtos.OperationRequest;
import com.pgdatamodeler.service.Dtos.OperationStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies edit operations in a strict per-model order and broadcasts the result.
 *
 * <p>The server is the single source of truth: it assigns consecutive versions, and every client applies
 * broadcast events in version order on top of the last confirmed state, then re-applies its own pending
 * (not yet confirmed) operations. Because operations replace whole objects, all clients converge.
 */
@Service
public class CollaborationService {

    private static final Logger log = LoggerFactory.getLogger(CollaborationService.class);

    private final DesignModelRepository models;
    private final OperationLogRepository operations;
    private final TransactionTemplate tx;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    public CollaborationService(DesignModelRepository models, OperationLogRepository operations,
                                TransactionTemplate tx, SimpMessagingTemplate messaging, ObjectMapper objectMapper) {
        this.models = models;
        this.operations = operations;
        this.tx = tx;
        this.messaging = messaging;
        this.objectMapper = objectMapper;
    }

    public static String topic(UUID modelId) {
        return "/topic/models/" + modelId;
    }

    public OperationEvent submit(UUID modelId, OperationRequest request) {
        if (request == null || request.op() == null) {
            throw new InvalidOperationException("Operation is required");
        }
        Object lock = locks.computeIfAbsent(modelId, k -> new Object());
        // Holding the lock while broadcasting keeps broadcast order equal to version order.
        synchronized (lock) {
            OperationEvent event;
            try {
                event = tx.execute(status -> apply(modelId, request));
            } catch (InvalidOperationException e) {
                long current = models.findById(modelId).map(DesignModelEntity::getVersion).orElse(0L);
                event = new OperationEvent(modelId, current, OperationStatus.REJECTED, request.clientOpId(),
                        request.author(), request.op(), e.getMessage());
                log.debug("Rejected op {} on model {}: {}", request.clientOpId(), modelId, e.getMessage());
            }
            messaging.convertAndSend(topic(modelId), event);
            return event;
        }
    }

    private OperationEvent apply(UUID modelId, OperationRequest request) {
        DesignModelEntity entity = models.findForUpdate(modelId)
                .orElseThrow(() -> new NotFoundException("Model " + modelId + " not found"));
        entity.update(OperationApplier.apply(entity.getDocument(), request.op()));
        operations.save(new OperationLogEntity(modelId, entity.getVersion(), request.author(), toJson(request)));
        return new OperationEvent(modelId, entity.getVersion(), OperationStatus.APPLIED, request.clientOpId(),
                request.author(), request.op(), null);
    }

    private String toJson(OperationRequest request) {
        try {
            return objectMapper.writeValueAsString(request.op());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
