package ch.uzh.ifi.hase.soprafs26.config;

import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionTracker;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {
    private final UserRepository userRepository;
    private final WebSocketSessionTracker webSocketSessionTracker;

    public StompAuthChannelInterceptor(UserRepository userRepository,
                                       WebSocketSessionTracker webSocketSessionTracker) {
        this.userRepository = userRepository;
        this.webSocketSessionTracker = webSocketSessionTracker;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        // 1. Handle initial CONNECT: Validate token and store userId in session
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            List<String> authHeaders = accessor.getNativeHeader("Authorization");
            String token = (authHeaders == null || authHeaders.isEmpty()) ? null : authHeaders.get(0).trim();

            if (token == null || token.isEmpty()) {
                throw new MessagingException("Missing Authorization token");
            }

            User user = userRepository.findByToken(token);
            if (user == null) {
                throw new MessagingException("Invalid Authorization token");
            }

            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes == null) {
                throw new MessagingException("Missing websocket session attributes");
            }

            // Keep userId in websocket session attributes for connect/disconnect tracking.
            sessionAttributes.put("userId", user.getId());
            // Also set websocket principal so /user/queue routing can resolve convertAndSendToUser calls.
            accessor.setUser(new StompPrincipal(String.valueOf(user.getId())));
            
            // Set initial heartbeat
            user.setLastHeartbeat(Instant.now());
            userRepository.save(user);

            String sessionId = accessor.getSessionId();
            if (sessionId != null && !sessionId.isBlank()) {
                webSocketSessionTracker.registerSession(user.getId(), sessionId);
            }
        }

        touchTrackedSession(accessor);
        return message;
    }

    private void touchTrackedSession(StompHeaderAccessor accessor) {
        if (accessor == null) {
            return;
        }
        String sessionId = accessor.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Long userId = extractUserId(accessor);
        if (userId == null) {
            return;
        }
        webSocketSessionTracker.touchSession(userId, sessionId);
    }

    private Long extractUserId(StompHeaderAccessor accessor) {
        if (accessor == null) {
            return null;
        }
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            Object raw = sessionAttributes.get("userId");
            if (raw instanceof Long userId) {
                return userId;
            }
            if (raw instanceof Number number) {
                return number.longValue();
            }
            if (raw instanceof String textId) {
                try {
                    return Long.parseLong(textId.trim());
                } catch (NumberFormatException ignored) {
                    // continue with principal fallback
                }
            }
        }
        if (accessor.getUser() != null) {
            try {
                return Long.parseLong(accessor.getUser().getName());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
