package ch.uzh.ifi.hase.soprafs26.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.event.EventListener;

import ch.uzh.ifi.hase.soprafs26.entity.Move;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.MoveRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.MoveLogEntryDTO;
import ch.uzh.ifi.hase.soprafs26.entity.PlayerActionEvent;

@Service
public class MoveService {
    private static final int MAX_SESSION_LOG_MOVES = 500;
    private static final long STALE_MOVE_CLEANUP_MIN_INTERVAL_MS = 30_000L;
    private static final long ENDED_SESSION_MOVE_RETENTION_HOURS = 24L;
    private static final int STALE_MOVE_CLEANUP_MAX_BATCHES_PER_RUN = 5;

    private final MoveRepository moveRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final AtomicLong lastMoveCleanupMs = new AtomicLong(0L);

    public MoveService(MoveRepository moveRepository,
                       SessionRepository sessionRepository,
                       UserRepository userRepository) {
        this.moveRepository = moveRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    // #111 requester's own moves + other public moves
    public List<MoveLogEntryDTO> getSessionLog(String sessionId, String token) {
        User requester = userRepository.findByToken(token);
        // reject unauthorized request
        if (requester == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }

        Session session = sessionRepository.findBySessionId(sessionId);
        // if no session found - throw exception 
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found");
        }

        // is requester is not part of this session - throw exception
        Long requesterId = requester.getId();
        if (!session.getTotalScoreByUserId().containsKey(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant of this session");
        }

        // load only the newest log window and return it in chronological order
        List<Move> recentMovesDesc = moveRepository.findTop500BySessionIdOrderByTimestampDesc(sessionId);
        List<Move> chronologicalMoves = new ArrayList<>(recentMovesDesc == null ? List.of() : recentMovesDesc);
        Collections.reverse(chronologicalMoves);

        // create a list of all public moves and a set of their user ids
        List<Move> visibleMoves = new ArrayList<>();
        Set<Long> playerIds = new HashSet<>();
        for (Move move : chronologicalMoves) {
            // skip invalid moves
            if (move == null) {
                continue;
            }
            boolean isOwnMove = requesterId.equals(move.getUserId());
            boolean isPublicMove = Boolean.TRUE.equals(move.getIsPublic());
            if (isOwnMove || isPublicMove) {
                visibleMoves.add(move);
                playerIds.add(move.getUserId());
            }
        }

        // create a map with user id - username pairs
        Map<Long, String> usernamesByUserId = new HashMap<>();
        if (!playerIds.isEmpty()) {
            for (User player : userRepository.findAllById(playerIds)) {
                if (player != null) {
                    usernamesByUserId.put(player.getId(), player.getUsername());
                }
            }
        }

        // create a log list
        List<MoveLogEntryDTO> log = new ArrayList<>();
        for (Move move : visibleMoves) {
            MoveLogEntryDTO row = new MoveLogEntryDTO();
            row.setUserId(move.getUserId());
            row.setUsername(usernamesByUserId.get(move.getUserId()));
            row.setActionType(move.getActionType());
            row.setTimestamp(move.getTimestamp());
            row.setDetails(move.getDetails());
            row.setOwnMove(requesterId.equals(move.getUserId()));
            log.add(row);
        }
        return log;
    }

    @EventListener
    public void handlePlayerAction(PlayerActionEvent event) {
        Move move = new Move();
        move.setSessionId(event.getSessionId());
        move.setUserId(event.getUserId());
        move.setActionType(event.getActionType());
        move.setDetails(event.getDetails());
        move.setTimestamp(Instant.now());

        User user = userRepository.findById(event.getUserId()).orElse(null);
        boolean isPublic = false;
        if (user != null && user.getIsPublicLog() != null) {
            isPublic = user.getIsPublicLog();
        }
        move.setIsPublic(isPublic);
        moveRepository.save(move);
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupStaleSessionMovesJob() {
        maybeCleanupStaleSessionMoves();
    }

    @Transactional
    void maybeCleanupStaleSessionMoves() {
        long nowMs = System.currentTimeMillis();
        long previousCleanupMs = lastMoveCleanupMs.get();
        if (nowMs - previousCleanupMs < STALE_MOVE_CLEANUP_MIN_INTERVAL_MS) {
            return;
        }
        if (!lastMoveCleanupMs.compareAndSet(previousCleanupMs, nowMs)) {
            return;
        }

        Instant cutoff = Instant.now().minusSeconds(ENDED_SESSION_MOVE_RETENTION_HOURS * 3600L);
        for (int batch = 0; batch < STALE_MOVE_CLEANUP_MAX_BATCHES_PER_RUN; batch++) {
            List<Session> staleEndedSessions = sessionRepository
                    .findTop200ByIsEndedTrueAndStartTimeBeforeOrderByStartTimeAsc(cutoff);
            if (staleEndedSessions == null || staleEndedSessions.isEmpty()) {
                return;
            }

            List<String> staleSessionIds = new ArrayList<>();
            for (Session session : staleEndedSessions) {
                if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
                    continue;
                }
                staleSessionIds.add(session.getSessionId());
            }

            if (!staleSessionIds.isEmpty()) {
                moveRepository.deleteAllBySessionIdsBulk(staleSessionIds);
            }

            if (staleEndedSessions.size() < 200) {
                return;
            }
        }
    }
}
