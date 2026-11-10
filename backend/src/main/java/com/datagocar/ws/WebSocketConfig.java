package com.datagocar.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over plain WebSocket at {@code /ws}.
 * <ul>
 *   <li>client → {@code /app/models/{id}/ops}: edit operations</li>
 *   <li>client → {@code /app/models/{id}/presence}: who is here and what they have selected</li>
 *   <li>server → {@code /topic/models/{id}}: applied / rejected operations, in version order</li>
 *   <li>server → {@code /topic/models/{id}/presence}: current participant list</li>
 * </ul>
 * The in-memory simple broker suits a single instance; for several instances switch to
 * {@code enableStompBrokerRelay} (RabbitMQ / ActiveMQ).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
        // Process each client's frames in the order they were sent (e.g. "add column" then "add FK").
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
        // Deliver broadcasts to each client in the order they were published (= version order).
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(1024 * 1024);
        registration.setSendBufferSizeLimit(4 * 1024 * 1024);
    }
}
