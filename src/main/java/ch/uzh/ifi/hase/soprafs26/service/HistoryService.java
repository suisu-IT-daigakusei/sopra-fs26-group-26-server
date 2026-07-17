package ch.uzh.ifi.hase.soprafs26.service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.repository.SessionHistoryQueryRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;

@Service
public class HistoryService {
    private static final int MAX_HISTORY_SESSIONS = 200;
    private static final int MAX_HISTORY_OFFSET = 10_000;
    
    private final SessionRepository sessionRepository;
    private final SessionHistoryQueryRepository sessionHistoryQueryRepository;
    private final UserRepository userRepository;

    public HistoryService(
            SessionRepository sessionRepository,
            SessionHistoryQueryRepository sessionHistoryQueryRepository,
            UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.sessionHistoryQueryRepository = sessionHistoryQueryRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<Session> getUserSessionHistory(Long userId) {
        return getUserSessionHistory(userId, MAX_HISTORY_SESSIONS, 0);
    }

    @Transactional(readOnly = true)
    public List<Session> getUserSessionHistory(Long userId, int limit, int offset) {
        if (limit < 1 || limit > MAX_HISTORY_SESSIONS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_HISTORY_SESSIONS);
        }
        if (offset < 0 || offset > MAX_HISTORY_OFFSET) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "offset must be between 0 and " + MAX_HISTORY_OFFSET);
        }
        if (userId == null || !userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!");
        }

        List<Long> orderedSessionIds =
                sessionHistoryQueryRepository.findRecentSessionIdsForUser(userId, limit, offset);
        if (orderedSessionIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Session> sessionsById = new HashMap<>();
        for (Session session : sessionRepository.findAllById(orderedSessionIds)) {
            sessionsById.put(session.getId(), session);
        }

        return orderedSessionIds.stream()
                .map(sessionsById::get)
                .filter(session -> session != null)
                .toList();
    }
}
