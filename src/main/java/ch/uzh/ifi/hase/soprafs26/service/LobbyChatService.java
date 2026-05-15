package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatMessageDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatSendDTO;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LobbyChatService {

    public static final int MAX_MESSAGE_LENGTH = 50;
    private static final int MAX_HISTORY_MESSAGES = 200;
    private static final long DEFAULT_CHAT_COOLDOWN_SECONDS = 3L;

    private final LobbyRepository lobbyRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ConcurrentHashMap<String, SessionChatState> chatStateBySessionId = new ConcurrentHashMap<>();

    public LobbyChatService(LobbyRepository lobbyRepository,
                            UserRepository userRepository,
                            SimpMessagingTemplate messagingTemplate) {
        this.lobbyRepository = lobbyRepository;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    public List<LobbyChatMessageDTO> getMessages(String token, String sessionId) {
        User user = requireUserByToken(token);
        Lobby lobby = requireLobbyBySessionId(sessionId);
        verifyLobbyParticipant(lobby, user.getId());

        SessionChatState state = getOrCreateStateForSession(lobby.getSessionId());
        synchronized (state) {
            return copyMessages(state.history);
        }
    }

    public LobbyChatMessageDTO sendMessage(String token, String sessionId, LobbyChatSendDTO request) {
        User user = requireUserByToken(token);
        Lobby lobby = requireLobbyBySessionId(sessionId);
        verifyLobbyParticipant(lobby, user.getId());
        String normalizedText = normalizeAndValidateText(request == null ? null : request.getMessage());
        long cooldownSeconds = resolveCooldownSeconds(lobby);
        Instant now = Instant.now();

        SessionChatState state = getOrCreateStateForSession(lobby.getSessionId());
        LobbyChatMessageDTO outgoingMessage;
        synchronized (state) {
            Instant previousMessageAt = state.lastSentAtByUserId.get(user.getId());
            if (previousMessageAt != null) {
                long elapsedMillis = Duration.between(previousMessageAt, now).toMillis();
                long requiredMillis = cooldownSeconds * 1000L;
                if (elapsedMillis < requiredMillis) {
                    long remainingSeconds = (long) Math.ceil((requiredMillis - elapsedMillis) / 1000.0d);
                    throw new ResponseStatusException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "Chat cooldown active. Try again in " + remainingSeconds + "s.");
                }
            }

            long nextSequence = state.sequence + 1L;
            state.sequence = nextSequence;
            outgoingMessage = new LobbyChatMessageDTO();
            outgoingMessage.setSequence(nextSequence);
            outgoingMessage.setSessionId(lobby.getSessionId());
            outgoingMessage.setUserId(user.getId());
            outgoingMessage.setUsername(user.getUsername());
            outgoingMessage.setText(normalizedText);
            outgoingMessage.setSentAt(now);

            state.history.add(copyMessage(outgoingMessage));
            while (state.history.size() > MAX_HISTORY_MESSAGES) {
                state.history.remove(0);
            }
            state.lastSentAtByUserId.put(user.getId(), now);
        }

        messagingTemplate.convertAndSend("/topic/lobby/session/" + lobby.getSessionId() + "/chat", outgoingMessage);
        return outgoingMessage;
    }

    public void clearSessionMessages(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null) {
            return;
        }
        chatStateBySessionId.remove(normalizedSessionId);
    }

    private SessionChatState getOrCreateStateForSession(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing session id");
        }
        return chatStateBySessionId.computeIfAbsent(normalizedSessionId, ignored -> new SessionChatState());
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        String normalized = sessionId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private User requireUserByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
        User user = userRepository.findByToken(token);
        if (user == null || user.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
        return user;
    }

    private Lobby requireLobbyBySessionId(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing session id");
        }
        Lobby lobby = lobbyRepository.findBySessionId(normalizedSessionId);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found");
        }
        return lobby;
    }

    private void verifyLobbyParticipant(Lobby lobby, Long userId) {
        if (lobby == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not part of this lobby");
        }
        boolean isPlayer = lobby.getPlayerIds() != null && lobby.getPlayerIds().contains(userId);
        boolean isSpectator = lobby.getSpectatorIds() != null && lobby.getSpectatorIds().contains(userId);
        if (!isPlayer && !isSpectator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not part of this lobby");
        }
    }

    private long resolveCooldownSeconds(Lobby lobby) {
        if (lobby == null || lobby.getChatCooldownSeconds() == null || lobby.getChatCooldownSeconds() <= 0L) {
            return DEFAULT_CHAT_COOLDOWN_SECONDS;
        }
        return lobby.getChatCooldownSeconds();
    }

    private String normalizeAndValidateText(String rawText) {
        if (rawText == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing chat message");
        }
        if (rawText.contains("\n") || rawText.contains("\r")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chat messages must be single-line");
        }
        String normalized = rawText.trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chat message must not be empty");
        }
        if (normalized.length() > MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Chat messages are limited to " + MAX_MESSAGE_LENGTH + " characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (current < 0x20 || current > 0x7E) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chat messages must be ASCII only");
            }
        }
        return normalized;
    }

    private List<LobbyChatMessageDTO> copyMessages(List<LobbyChatMessageDTO> source) {
        List<LobbyChatMessageDTO> copies = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return copies;
        }
        for (LobbyChatMessageDTO message : source) {
            copies.add(copyMessage(message));
        }
        return copies;
    }

    private LobbyChatMessageDTO copyMessage(LobbyChatMessageDTO source) {
        if (source == null) {
            return null;
        }
        LobbyChatMessageDTO copy = new LobbyChatMessageDTO();
        copy.setSequence(source.getSequence());
        copy.setSessionId(source.getSessionId());
        copy.setUserId(source.getUserId());
        copy.setUsername(source.getUsername());
        copy.setText(source.getText());
        copy.setSentAt(source.getSentAt());
        return copy;
    }

    private static class SessionChatState {
        private long sequence = 0L;
        private final List<LobbyChatMessageDTO> history = new ArrayList<>();
        private final Map<Long, Instant> lastSentAtByUserId = new HashMap<>();
    }
}
