package com.datagocar.ws;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks who has which model open (per WebSocket session) and broadcasts the participant list. */
@Service
public class PresenceService {

    public record Participant(String sessionId, String userName, String color, String selectedId) {
    }

    private final Map<UUID, Map<String, Participant>> byModel = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messaging;

    public PresenceService(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void update(UUID modelId, Participant participant) {
        byModel.computeIfAbsent(modelId, k -> new ConcurrentHashMap<>()).put(participant.sessionId(), participant);
        broadcast(modelId);
    }

    public void leave(UUID modelId, String sessionId) {
        Map<String, Participant> participants = byModel.get(modelId);
        if (participants != null && participants.remove(sessionId) != null) {
            broadcast(modelId);
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        byModel.forEach((modelId, participants) -> {
            if (participants.remove(event.getSessionId()) != null) {
                broadcast(modelId);
            }
        });
    }

    private void broadcast(UUID modelId) {
        List<Participant> list = byModel.getOrDefault(modelId, Map.of()).values().stream()
                .sorted(Comparator.comparing(Participant::userName))
                .toList();
        messaging.convertAndSend("/topic/models/" + modelId + "/presence", list);
    }
}
