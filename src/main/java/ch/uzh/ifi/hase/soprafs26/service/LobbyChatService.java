package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatMessageDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatSendDTO;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LobbyChatService {

    public static final int MAX_MESSAGE_LENGTH = 50;
    private static final int MAX_HISTORY_MESSAGES = 200;
    private static final long DEFAULT_CHAT_COOLDOWN_SECONDS = 3L;
    private static final long LAST_SENT_TRACK_RETENTION_SECONDS = 1800L;
    private static final long CHAT_STATE_CACHE_CLEANUP_MIN_INTERVAL_MS = 30_000L;
    private static final long CHAT_STATE_INACTIVE_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final int CHAT_STATE_MAX_SESSIONS = 1024;

    private final LobbyRepository lobbyRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ConcurrentHashMap<String, SessionChatState> chatStateBySessionId = new ConcurrentHashMap<>();
    private final AtomicLong lastChatStateCleanupMs = new AtomicLong(0L);

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
        verifyLobbyParticipant(lobby.getSessionId(), user.getId());

        SessionChatState state = getOrCreateStateForSession(lobby.getSessionId());
        synchronized (state) {
            Instant now = Instant.now();
            pruneLastSentTracker(state, now);
            state.touch(System.currentTimeMillis());
            return copyMessages(state.history);
        }
    }

    public LobbyChatMessageDTO sendMessage(String token, String sessionId, LobbyChatSendDTO request) {
        User user = requireUserByToken(token);
        Lobby lobby = requireLobbyBySessionId(sessionId);
        verifyLobbyParticipant(lobby.getSessionId(), user.getId());
        String normalizedText = normalizeAndValidateText(request == null ? null : request.getMessage());
        long cooldownSeconds = resolveCooldownSeconds(lobby);
        Instant now = Instant.now();

        SessionChatState state = getOrCreateStateForSession(lobby.getSessionId());
        LobbyChatMessageDTO outgoingMessage;
        synchronized (state) {
            pruneLastSentTracker(state, now);
            state.touch(System.currentTimeMillis());
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

    private void pruneLastSentTracker(SessionChatState state, Instant now) {
        if (state == null || now == null) {
            return;
        }
        Instant cutoff = now.minusSeconds(LAST_SENT_TRACK_RETENTION_SECONDS);
        state.lastSentAtByUserId.entrySet().removeIf(entry ->
                entry == null
                        || entry.getKey() == null
                        || entry.getValue() == null
                        || entry.getValue().isBefore(cutoff));
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
        long nowMs = System.currentTimeMillis();
        maybeCleanupSessionStateCache(nowMs);
        SessionChatState state = chatStateBySessionId.computeIfAbsent(normalizedSessionId, ignored -> new SessionChatState());
        state.touch(nowMs);
        return state;
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupStaleSessionChatStateJob() {
        maybeCleanupSessionStateCache(System.currentTimeMillis());
    }

    private void maybeCleanupSessionStateCache(long nowMs) {
        long previousCleanupMs = lastChatStateCleanupMs.get();
        if (nowMs - previousCleanupMs < CHAT_STATE_CACHE_CLEANUP_MIN_INTERVAL_MS) {
            return;
        }
        if (!lastChatStateCleanupMs.compareAndSet(previousCleanupMs, nowMs)) {
            return;
        }

        long staleCutoffMs = nowMs - CHAT_STATE_INACTIVE_TTL_MS;
        chatStateBySessionId.entrySet().removeIf(entry -> {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                return true;
            }
            SessionChatState state = entry.getValue();
            return state.getLastTouchedAtMs() < staleCutoffMs;
        });

        int overflowEntries = chatStateBySessionId.size() - CHAT_STATE_MAX_SESSIONS;
        if (overflowEntries <= 0) {
            return;
        }
        List<Map.Entry<String, SessionChatState>> oldestEntries = chatStateBySessionId.entrySet().stream()
                .filter(entry -> entry != null && entry.getKey() != null && entry.getValue() != null)
                .sorted(Comparator.comparingLong(entry -> entry.getValue().getLastTouchedAtMs()))
                .limit(overflowEntries)
                .toList();
        for (Map.Entry<String, SessionChatState> oldestEntry : oldestEntries) {
            chatStateBySessionId.remove(oldestEntry.getKey());
        }
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

    private void verifyLobbyParticipant(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank() || userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not part of this lobby");
        }
        if (!lobbyRepository.existsBySessionIdAndParticipantId(sessionId, userId)) {
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
        private volatile long lastTouchedAtMs = System.currentTimeMillis();
        private final List<LobbyChatMessageDTO> history = new ArrayList<>();
        private final Map<Long, Instant> lastSentAtByUserId = new HashMap<>();

        private void touch(long nowMs) {
            this.lastTouchedAtMs = nowMs;
        }

        private long getLastTouchedAtMs() {
            return lastTouchedAtMs;
        }
    }
}
