package dev.aiw.events;

import dev.aiw.access.WorkspaceAccess;
import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket at /ws. The handshake requires an authenticated HTTP session; clients can only
 * subscribe to /topic/workspace.{id} for workspaces they belong to, and cannot send to topics.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private static final String WORKSPACE_TOPIC = "/topic/workspace.";

    private final WorkspaceAccess access;
    private final String[] allowedOrigins;

    WebSocketConfig(WorkspaceAccess access, @Value("${aiw.web.allowed-origins}") String[] allowedOrigins) {
        this.access = access;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) return message;
                StompCommand command = accessor.getCommand();
                Principal user = accessor.getUser();
                if (command == StompCommand.SEND) {
                    throw new MessageDeliveryException("Clients cannot publish messages");
                }
                if (command == StompCommand.SUBSCRIBE) {
                    String destination = accessor.getDestination();
                    if (user == null || destination == null || !destination.startsWith(WORKSPACE_TOPIC)) {
                        throw new MessageDeliveryException("Subscription denied");
                    }
                    String workspaceId = destination.substring(WORKSPACE_TOPIC.length());
                    if (!access.isMember(workspaceId, user.getName())) {
                        throw new MessageDeliveryException("Subscription denied");
                    }
                }
                return message;
            }
        });
    }
}
