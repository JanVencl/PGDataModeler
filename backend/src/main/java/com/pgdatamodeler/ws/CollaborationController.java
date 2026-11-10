package com.pgdatamodeler.ws;

import com.pgdatamodeler.ops.InvalidOperationException;
import com.pgdatamodeler.service.CollaborationService;
import com.pgdatamodeler.service.Dtos.OperationRequest;
import com.pgdatamodeler.service.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class CollaborationController {

    private static final Logger log = LoggerFactory.getLogger(CollaborationController.class);

    private final CollaborationService collaboration;
    private final PresenceService presence;

    public CollaborationController(CollaborationService collaboration, PresenceService presence) {
        this.collaboration = collaboration;
        this.presence = presence;
    }

    @MessageMapping("/models/{modelId}/ops")
    public void operation(@DestinationVariable UUID modelId, @Payload OperationRequest request) {
        collaboration.submit(modelId, request);
    }

    public record PresenceMessage(String userName, String color, String selectedId, boolean leaving) {
    }

    @MessageMapping("/models/{modelId}/presence")
    public void presence(@DestinationVariable UUID modelId, @Payload PresenceMessage message,
                         SimpMessageHeaderAccessor headers) {
        String sessionId = headers.getSessionId();
        if (message.leaving()) {
            presence.leave(modelId, sessionId);
        } else {
            String name = message.userName() == null || message.userName().isBlank() ? "Anonymous" : message.userName();
            presence.update(modelId, new PresenceService.Participant(sessionId, name, message.color(), message.selectedId()));
        }
    }

    @MessageExceptionHandler({NotFoundException.class, InvalidOperationException.class})
    public void handle(RuntimeException e) {
        log.warn("WebSocket message failed: {}", e.getMessage());
    }
}
