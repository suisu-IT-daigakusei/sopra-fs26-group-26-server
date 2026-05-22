package ch.uzh.ifi.hase.soprafs26.service;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;

@Service
public class HistoryService {
    private static final long HISTORY_CACHE_TTL_MS = 15000L;
    private static final long HISTORY_CACHE_CLEANUP_MIN_INTERVAL_MS = 1000L;
    private static final int MAX_HISTORY_CACHE_ENTRIES = 2048;

    private record CachedHistory(List<Session> sessions, long expiresAtMs) {}
    
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final Map<Long, CachedHistory> cachedHistoryByUserId = new ConcurrentHashMap<>();
    private final AtomicLong lastCacheCleanupMs = new AtomicLong(0L);

    public HistoryService(SessionRepository sessionRepository, UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    public List<Session> getUserSessionHistory(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

        long nowMs = System.currentTimeMillis();
        CachedHistory cachedHistory = cachedHistoryByUserId.get(userId);
        if (cachedHistory != null && cachedHistory.expiresAtMs() > nowMs) {
            return new ArrayList<>(cachedHistory.sessions());
        }

        List<Session> allSessions = sessionRepository.findAll();
        List<Session> filteredSessions = allSessions.stream()
            .filter(session -> session != null
                    && session.getTotalScoreByUserId() != null
                    && session.getTotalScoreByUserId().containsKey(userId))
            .sorted(Comparator.comparing(Session::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                    .reversed())
            .toList();

        cachedHistoryByUserId.put(userId, new CachedHistory(List.copyOf(filteredSessions), nowMs + HISTORY_CACHE_TTL_MS));
        maybeCleanupHistoryCache(nowMs);
        return filteredSessions;
    }

    private void maybeCleanupHistoryCache(long nowMs) {
        long previousCleanupMs = lastCacheCleanupMs.get();
        if (nowMs - previousCleanupMs < HISTORY_CACHE_CLEANUP_MIN_INTERVAL_MS) {
            return;
        }
        if (!lastCacheCleanupMs.compareAndSet(previousCleanupMs, nowMs)) {
            return;
        }
        cachedHistoryByUserId.entrySet().removeIf(entry ->
                entry == null
                        || entry.getKey() == null
                        || entry.getValue() == null
                        || entry.getValue().expiresAtMs() <= nowMs
                        || entry.getValue().sessions() == null);

        int overflowEntries = cachedHistoryByUserId.size() - MAX_HISTORY_CACHE_ENTRIES;
        if (overflowEntries <= 0) {
            return;
        }

        List<Map.Entry<Long, CachedHistory>> oldestEntries = cachedHistoryByUserId.entrySet().stream()
                .filter(entry -> entry != null && entry.getKey() != null && entry.getValue() != null)
                .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAtMs()))
                .limit(overflowEntries)
                .toList();
        for (Map.Entry<Long, CachedHistory> oldestEntry : oldestEntries) {
            cachedHistoryByUserId.remove(oldestEntry.getKey());
        }
    }
}
