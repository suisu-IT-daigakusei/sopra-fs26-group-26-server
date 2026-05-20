package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.config.settings.ServerSettingsProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebSocketSessionTracker {

    private static final class UserSessionBucket {
        private final LinkedHashMap<String, Long> sessionsInOrder = new LinkedHashMap<>();
    }

    private final ServerSettingsProperties serverSettings;
    private final ConcurrentMap<Long, UserSessionBucket> sessionsByUserId = new ConcurrentHashMap<>();

    public WebSocketSessionTracker(ObjectProvider<ServerSettingsProperties> serverSettingsProvider) {
        this.serverSettings = serverSettingsProvider.getIfAvailable(ServerSettingsProperties::new);
    }

    public List<String> registerSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        int maxSessionsPerUser = Math.max(1, serverSettings.getMaxWebsocketSessionsPerUser());
        long nowMs = System.currentTimeMillis();
        UserSessionBucket bucket = sessionsByUserId.computeIfAbsent(userId, ignored -> new UserSessionBucket());
        List<String> evictedSessionIds = new ArrayList<>();

        synchronized (bucket) {
            pruneStaleSessionsLocked(bucket, nowMs);

            bucket.sessionsInOrder.remove(sessionId);
            bucket.sessionsInOrder.put(sessionId, nowMs);

            while (bucket.sessionsInOrder.size() > maxSessionsPerUser) {
                String oldest = bucket.sessionsInOrder.keySet().stream().findFirst().orElse(null);
                if (oldest == null) {
                    break;
                }
                bucket.sessionsInOrder.remove(oldest);
                evictedSessionIds.add(oldest);
            }
        }

        return evictedSessionIds;
    }

    public void unregisterSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }

        UserSessionBucket bucket = sessionsByUserId.get(userId);
        if (bucket == null) {
            return;
        }

        synchronized (bucket) {
            bucket.sessionsInOrder.remove(sessionId);
            if (bucket.sessionsInOrder.isEmpty()) {
                sessionsByUserId.remove(userId, bucket);
            }
        }
    }

    public void touchSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }

        UserSessionBucket bucket = sessionsByUserId.get(userId);
        if (bucket == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        synchronized (bucket) {
            pruneStaleSessionsLocked(bucket, nowMs);
            if (!bucket.sessionsInOrder.containsKey(sessionId)) {
                if (bucket.sessionsInOrder.isEmpty()) {
                    sessionsByUserId.remove(userId, bucket);
                }
                return;
            }
            bucket.sessionsInOrder.put(sessionId, nowMs);
        }
    }

    public void touchSessions(Long userId) {
        if (userId == null) {
            return;
        }

        UserSessionBucket bucket = sessionsByUserId.get(userId);
        if (bucket == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        synchronized (bucket) {
            pruneStaleSessionsLocked(bucket, nowMs);
            if (bucket.sessionsInOrder.isEmpty()) {
                sessionsByUserId.remove(userId, bucket);
                return;
            }
            for (String sessionId : new ArrayList<>(bucket.sessionsInOrder.keySet())) {
                if (sessionId == null || sessionId.isBlank()) {
                    continue;
                }
                bucket.sessionsInOrder.put(sessionId, nowMs);
            }
        }
    }

    public boolean hasActiveSession(Long userId) {
        if (userId == null) {
            return false;
        }

        UserSessionBucket bucket = sessionsByUserId.get(userId);
        if (bucket == null) {
            return false;
        }

        synchronized (bucket) {
            pruneStaleSessionsLocked(bucket, System.currentTimeMillis());
            boolean hasActive = !bucket.sessionsInOrder.isEmpty();
            if (!hasActive) {
                sessionsByUserId.remove(userId, bucket);
            }
            return hasActive;
        }
    }

    public List<String> getTrackedSessionIds(Long userId) {
        if (userId == null) {
            return List.of();
        }

        UserSessionBucket bucket = sessionsByUserId.get(userId);
        if (bucket == null) {
            return List.of();
        }

        synchronized (bucket) {
            pruneStaleSessionsLocked(bucket, System.currentTimeMillis());
            if (bucket.sessionsInOrder.isEmpty()) {
                sessionsByUserId.remove(userId, bucket);
                return List.of();
            }
            return new ArrayList<>(bucket.sessionsInOrder.keySet());
        }
    }

    public void clearSessions(Long userId) {
        if (userId == null) {
            return;
        }
        sessionsByUserId.remove(userId);
    }

    public void pruneStaleSessions() {
        long nowMs = System.currentTimeMillis();
        for (Map.Entry<Long, UserSessionBucket> entry : sessionsByUserId.entrySet()) {
            Long userId = entry.getKey();
            UserSessionBucket bucket = entry.getValue();
            if (bucket == null) {
                sessionsByUserId.remove(userId);
                continue;
            }
            synchronized (bucket) {
                pruneStaleSessionsLocked(bucket, nowMs);
                if (bucket.sessionsInOrder.isEmpty()) {
                    sessionsByUserId.remove(userId, bucket);
                }
            }
        }
    }

    private void pruneStaleSessionsLocked(UserSessionBucket bucket, long nowMs) {
        long staleAfterMs = Math.max(1000L, serverSettings.getWebsocketSessionStaleAfterMs());
        bucket.sessionsInOrder.entrySet().removeIf(entry ->
                entry == null
                        || entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null
                        || nowMs - entry.getValue() > staleAfterMs);
    }
}
