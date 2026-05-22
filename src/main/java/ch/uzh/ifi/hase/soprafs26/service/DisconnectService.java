package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.config.settings.TimeoutSettingsProperties;
import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Transactional
public class DisconnectService {
    private static final Logger log = LoggerFactory.getLogger(DisconnectService.class);

    private static final class DisconnectTimerEntry {
        private final long version;
        private final ScheduledFuture<?> future;

        private DisconnectTimerEntry(long version, ScheduledFuture<?> future) {
            this.version = version;
            this.future = future;
        }
    }

    private final UserRepository userRepository;
    private final LobbyService lobbyService;
    private final GameService gameService;
    private final TimeoutSettingsProperties timeoutSettings;
    private final WebSocketSessionTracker webSocketSessionTracker;

    private final Map<Long, DisconnectTimerEntry> activeTimers = new ConcurrentHashMap<>();
    private final AtomicLong disconnectTimerVersionSequence = new AtomicLong(0L);
    private final ScheduledThreadPoolExecutor scheduler = createDisconnectScheduler();

    public DisconnectService(UserRepository userRepository,
                             @Lazy LobbyService lobbyService,
                             @Lazy GameService gameService,
                             TimeoutSettingsProperties timeoutSettings,
                             WebSocketSessionTracker webSocketSessionTracker) {
        this.userRepository = userRepository;
        this.lobbyService = lobbyService;
        this.gameService = gameService;
        this.timeoutSettings = timeoutSettings;
        this.webSocketSessionTracker = webSocketSessionTracker;
    }

    private static ScheduledThreadPoolExecutor createDisconnectScheduler() {
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(5);
        // Avoid keeping canceled delayed tasks in memory until their original delay elapses.
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    @PreDestroy
    void shutdownScheduler() {
        scheduler.shutdownNow();
    }

    /**
     * RULE 1: Connection Loss (WebSocket closed/Tab closed).
     * Starts the websocket grace period.
     */
    public void handleConnectionLoss(Long userId) {
        if (userId == null) return;
        if (hasActiveWebSocketSession(userId)) {
            return;
        }
        User userRecord = userRepository.findById(userId).orElse(null);
        if (userRecord != null && userRecord.getStatus() == UserStatus.SPECTATING) {
            // Spectators are fully exempt from websocket-grace timeout removal.
            cancelDisconnectTimer(userId);
            return;
        }
        // Outside lobby/game context, websocket disconnect alone should not force offline.
        // Presence there is governed by heartbeat idle checks.
        if (lobbyService != null && !lobbyService.isUserInLobbyContext(userId)) {
            cancelDisconnectTimer(userId);
            return;
        }
        // In active games, do not fast-timeout on websocket disconnect alone (tab switch/background throttling).
        // AFK handling should follow the configured game AFK timer via heartbeat idle checks.
        if (lobbyService != null && lobbyService.isUserInActiveGame(userId)) {
            long websocketGraceSeconds = resolveWebsocketGraceSecondsForUser(userId);
            scheduleDisconnectTimer(userId, websocketGraceSeconds, () -> {
                if (hasActiveWebSocketSession(userId)) {
                    return;
                }
                performPermanentRemoval(userId, "websocket_grace_active_game", websocketGraceSeconds);
            });
            return;
        }
        long websocketGraceSeconds = resolveWebsocketGraceSecondsForUser(userId);
        scheduleDisconnectTimer(userId, websocketGraceSeconds, () -> {
            performPermanentRemoval(userId, "websocket_grace", websocketGraceSeconds);
        });
    }

    /**
     * RULE 2: Idle Timeout (User is there but silent for a longer period).
     * Checked periodically via @Scheduled.
     */
    @Scheduled(fixedDelayString = "#{@timeoutSettingsProperties.idleCheckIntervalMs}")
    public void checkIdleUsers() {
        List<User> users = userRepository.findByStatusNot(UserStatus.OFFLINE);
        Set<Long> activeGameUserIds = lobbyService != null
                ? lobbyService.getPlayingLobbyPlayerIdsSnapshot()
                : Set.of();
        Map<Long, Long> activeGameAfkTimeoutByUserId = lobbyService != null
                ? lobbyService.getPlayingLobbyPlayerAfkTimeoutSecondsSnapshot()
                : Map.of();
        if (activeGameAfkTimeoutByUserId == null) {
            activeGameAfkTimeoutByUserId = Map.of();
        }

        Instant now = Instant.now();
        for (User user : users) {
            if (user != null && user.getStatus() == UserStatus.SPECTATING) {
                // Spectators are fully exempt from idle-timeout removal.
                continue;
            }
            if (user.getLastHeartbeat() == null) {
                continue;
            }
            if (lobbyService != null && lobbyService.isPlayerTimedOutInPlaying(user.getId())) {
                // Already timed out midgame; keep game membership without repeated processing.
                continue;
            }
            boolean userInActiveGame = activeGameUserIds.contains(user.getId());
            long idleThresholdSeconds = userInActiveGame
                    ? resolveIdleThresholdSecondsForUser(user.getId(), activeGameAfkTimeoutByUserId.get(user.getId()))
                    : timeoutSettings.getIdleSeconds();
            Instant idleCutoff = now.minusSeconds(idleThresholdSeconds);
            if (!user.getLastHeartbeat().isBefore(idleCutoff)) {
                continue;
            }

            if (userInActiveGame && lobbyService != null && lobbyService.isPlayerTimedOutInPlaying(user.getId())) {
                // Already marked timed out for active game: avoid repeated removals/log spam.
                continue;
            }
            performPermanentRemoval(user.getId(), "idle", idleThresholdSeconds);
        }
    }

    private long resolveIdleThresholdSecondsForUser(Long userId, Long lobbyAfkTimeoutSeconds) {
        long defaultIdle = timeoutSettings.getIdleSeconds();
        if (lobbyAfkTimeoutSeconds != null && lobbyAfkTimeoutSeconds > 0) {
            return lobbyAfkTimeoutSeconds;
        }
        if (userId == null) {
            return defaultIdle;
        }
        if (gameService == null) {
            return defaultIdle;
        }
        return gameService.findActiveGameForUser(userId)
                .map(Game::getAfkTimeoutSeconds)
                .filter(seconds -> seconds > 0)
                .orElse(defaultIdle);
    }

    private long resolveWebsocketGraceSecondsForUser(Long userId) {
        long defaultGrace = timeoutSettings.getWebsocketGraceSeconds();
        if (userId == null || lobbyService == null) {
            return defaultGrace;
        }
        Long lobbyGrace = lobbyService.findWebsocketGraceSecondsForUser(userId);
        if (lobbyGrace == null || lobbyGrace <= 0) {
            return defaultGrace;
        }
        return lobbyGrace;
    }

    /**
     * RULE 3: Automatic logout (token invalidation) for very long inactivity.
     * This is intentionally much longer than idle disconnect timers.
     */
    @Scheduled(fixedDelayString = "#{@timeoutSettingsProperties.autoLogoutCheckIntervalMs}")
    public void checkAutoLogoutUsers() {
        Instant autoLogoutCutoff = Instant.now().minusSeconds(timeoutSettings.getAutoLogoutSeconds());

        List<User> usersToAutoLogout = userRepository.findByLastHeartbeatBeforeAndStatusNot(
                autoLogoutCutoff,
                UserStatus.PLAYING
        );

        for (User user : usersToAutoLogout) {
            if (user == null) {
                continue;
            }
            user.setStatus(UserStatus.OFFLINE);
            user.setToken(UUID.randomUUID().toString());
            userRepository.save(user);

            cancelDisconnectTimer(user.getId());
            webSocketSessionTracker.clearSessions(user.getId());
        }
    }

    @Scheduled(fixedDelayString = "#{@serverSettingsProperties.websocketSessionPruneIntervalMs}")
    public void pruneStaleWebSocketSessions() {
        webSocketSessionTracker.pruneStaleSessions();
    }

    public void handleReconnect(Long userId) {
        webSocketSessionTracker.touchSessions(userId);
        cancelDisconnectTimer(userId);
        lobbyService.clearTimedOutPlayingFlag(userId);
    }

    public void registerWebSocketSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }

        webSocketSessionTracker.registerSession(userId, sessionId);
        cancelDisconnectTimer(userId);
        lobbyService.clearTimedOutPlayingFlag(userId);
    }

    public void unregisterWebSocketSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }

        webSocketSessionTracker.unregisterSession(userId, sessionId);

        if (!hasActiveWebSocketSession(userId)) {
            handleConnectionLoss(userId);
        }
    }

    public void cancelDisconnectTimer(Long userId) {
        DisconnectTimerEntry timerEntry = activeTimers.remove(userId);
        if (timerEntry != null && timerEntry.future != null) {
            timerEntry.future.cancel(false);
        }
    }

    public boolean isPlayerInGracePeriod(Long userId) {
        return activeTimers.containsKey(userId);
    }

    private boolean hasActiveWebSocketSession(Long userId) {
        return webSocketSessionTracker.hasActiveSession(userId);
    }

    private void scheduleDisconnectTimer(Long userId, long delaySeconds, Runnable action) {
        if (userId == null || action == null) {
            return;
        }
        cancelDisconnectTimer(userId);
        long safeDelaySeconds = Math.max(1L, delaySeconds);
        long timerVersion = disconnectTimerVersionSequence.incrementAndGet();
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            DisconnectTimerEntry current = activeTimers.get(userId);
            if (current == null || current.version != timerVersion) {
                return;
            }
            try {
                action.run();
            } finally {
                activeTimers.compute(userId, (id, existing) -> {
                    if (existing != null && existing.version == timerVersion) {
                        return null;
                    }
                    return existing;
                });
            }
        }, safeDelaySeconds, TimeUnit.SECONDS);
        activeTimers.put(userId, new DisconnectTimerEntry(timerVersion, future));
    }

    public List<String> getTrackedWebSocketSessionIds(Long userId) {
        return webSocketSessionTracker.getTrackedSessionIds(userId);
    }

    /**
     * Final cleanup after websocket grace or idle timeout.
     */
    private void performPermanentRemoval(Long userId, String reason, long thresholdSeconds) {
        boolean idleTimeoutPath = "idle".equalsIgnoreCase(String.valueOf(reason));
        if (!idleTimeoutPath && hasActiveWebSocketSession(userId)) {
            cancelDisconnectTimer(userId);
            return;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            cancelDisconnectTimer(userId);
            webSocketSessionTracker.clearSessions(userId);
            return;
        }
        if (user.getStatus() == UserStatus.SPECTATING) {
            // Spectators are fully exempt from timeout-driven disconnect removal.
            cancelDisconnectTimer(userId);
            webSocketSessionTracker.clearSessions(userId);
            return;
        }
        if (lobbyService != null && lobbyService.isPlayerTimedOutInPlaying(userId)) {
            // Idempotency guard for midgame timeout path.
            cancelDisconnectTimer(userId);
            return;
        }
        if (user.getStatus() == UserStatus.OFFLINE) {
            // Idempotency guard for already-processed offline users.
            cancelDisconnectTimer(userId);
            webSocketSessionTracker.clearSessions(userId);
            return;
        }

        // Delegate lobby/game-aware timeout handling.
        lobbyService.handlePermanentDisconnect(userId);
        
        cancelDisconnectTimer(userId);
        webSocketSessionTracker.clearSessions(userId);
        long secondsSinceHeartbeat = -1L;
        Instant lastHeartbeat = user.getLastHeartbeat();
        if (lastHeartbeat != null) {
            secondsSinceHeartbeat = Math.max(0L, Instant.now().getEpochSecond() - lastHeartbeat.getEpochSecond());
        }
        String reasonText = reason == null || reason.isBlank() ? "unknown" : reason;
        String heartbeatText = secondsSinceHeartbeat >= 0
                ? String.valueOf(secondsSinceHeartbeat)
                : "n/a";

        User updatedUser = userRepository.findById(userId).orElse(user);
        boolean timedOutInActiveGame = lobbyService != null && lobbyService.isPlayerTimedOutInPlaying(userId);
        String outcomeText;
        if (timedOutInActiveGame) {
            outcomeText = "marked_timed_out_in_active_game";
        } else if (updatedUser.getStatus() == UserStatus.OFFLINE) {
            outcomeText = "removed_and_set_offline";
        } else {
            outcomeText = "timeout_handled_status_" + String.valueOf(updatedUser.getStatus()).toLowerCase();
        }

        log.info(
                "Timeout handling applied for user {} [reason={}, outcome={}, thresholdSeconds={}, secondsSinceHeartbeat={}].",
                userId,
                reasonText,
                outcomeText,
                thresholdSeconds,
                heartbeatText);
    }
}
