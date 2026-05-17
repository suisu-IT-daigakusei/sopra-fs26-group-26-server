package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.config.settings.LobbySettingsProperties;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.GameRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbySettingsPatchDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.WaitingLobbyPlayerRowDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.WaitingLobbyViewDTO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
public class LobbyService {

    private final LobbyRepository lobbyRepository;
    private final GameRepository gameRepository;
    private final UserRepository userRepository;
    private final LobbyEventPublisher lobbyEventPublisher;
    private final OnlineUsersEventPublisher onlineUsersEventPublisher;
    private final DisconnectService disconnectService;
    private final GameService gameService;
    private final LobbyChatService lobbyChatService;
    private final LobbySettingsProperties lobbySettings;
    // Players that timed out while being in a PLAYING lobby.
    // They stay part of the active game and trigger an automatic Cabo when their turn starts.
    private final Set<Long> timedOutInPlayingPlayerIds = ConcurrentHashMap.newKeySet();
    private static final List<String> CHARACTER_COLOR_ORDER = List.of(
            "navy_blue",
            "light_blue",
            "dark_green",
            "light_green",
            "yellow",
            "orange",
            "red",
            "pink",
            "purple");
    private static final String PREFERRED_COLOR_UNASSIGNED = "__unassigned__";
    private static final Map<String, String> LEGACY_COLOR_ALIAS_MAP = Map.ofEntries(
            Map.entry("black", "navy_blue"),
            Map.entry("blue", "navy_blue"),
            Map.entry("green", "light_green"),
            Map.entry("default", "orange"),
            Map.entry("slate", "navy_blue"),
            Map.entry("graphite", "dark_green"),
            Map.entry("forest", "dark_green"),
            Map.entry("ocean", "light_blue"),
            Map.entry("teal", "light_blue"),
            Map.entry("coral", "red"),
            Map.entry("indigo", "navy_blue"),
            Map.entry("plum", "purple"),
            Map.entry("amber", "yellow"));

    public LobbyService(LobbyRepository lobbyRepository,
                        GameRepository gameRepository,
                        UserRepository userRepository,
                        LobbyEventPublisher lobbyEventPublisher,
                        OnlineUsersEventPublisher onlineUsersEventPublisher,
                        LobbySettingsProperties lobbySettings,
                        @Lazy DisconnectService disconnectService,
                        @Lazy GameService gameService,
                        @Lazy LobbyChatService lobbyChatService) {
        this.lobbyRepository = lobbyRepository;
        this.gameRepository = gameRepository;
        this.userRepository = userRepository;
        this.lobbyEventPublisher = lobbyEventPublisher;
        this.onlineUsersEventPublisher = onlineUsersEventPublisher;
        this.lobbySettings = lobbySettings;
        this.disconnectService = disconnectService;
        this.gameService = gameService;
        this.lobbyChatService = lobbyChatService;
    }

    // helper: look up user by token, throw 401 if invalid
    private User getUserByToken(String token) {
        User user = userRepository.findByToken(token);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
        return user;
    }

    private void setUserStatus(Long userId, UserStatus status) {
        if (userId == null || status == null) {
            return;
        }
        userRepository.findById(userId).ifPresent(user -> {
            if (status.equals(user.getStatus())) {
                return;
            }
            user.setStatus(status);
            userRepository.save(user);
        });
    }

    // same as setUserStatus but multiple userIds
    private void setUsersStatus(List<Long> userIds, UserStatus status) {
        if (userIds == null || status == null) {
            return;
        }
        List<Long> distinctUserIds = userIds.stream()
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (distinctUserIds.isEmpty()) {
            return;
        }

        List<User> users = userRepository.findAllById(distinctUserIds);
        boolean hasChanges = false;
        for (User user : users) {
            if (user == null || status.equals(user.getStatus())) {
                continue;
            }
            user.setStatus(status);
            hasChanges = true;
        }
        if (hasChanges) {
            userRepository.saveAll(users);
        }
    }

    private String normalizeCharacterColorId(String rawColorId) {
        String normalized = rawColorId == null ? "" : rawColorId.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return "";
        }
        if (LEGACY_COLOR_ALIAS_MAP.containsKey(normalized)) {
            normalized = LEGACY_COLOR_ALIAS_MAP.get(normalized);
        }
        return CHARACTER_COLOR_ORDER.contains(normalized) ? normalized : "";
    }

    private List<String> sanitizePreferredCharacterColors(List<String> preferredColors) {
        if (preferredColors == null || preferredColors.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String preferredColor : preferredColors) {
            String normalized = preferredColor == null ? "" : preferredColor.trim().toLowerCase();
            if (normalized.isEmpty() || PREFERRED_COLOR_UNASSIGNED.equals(normalized)) {
                continue;
            }
            normalized = normalizeCharacterColorId(normalized);
            if (normalized.isEmpty() || seen.contains(normalized)) {
                continue;
            }
            seen.add(normalized);
            sanitized.add(normalized);
        }
        return sanitized;
    }

    private List<String> getPlayerColorPreferences(User user) {
        if (user == null) {
            return List.of(CHARACTER_COLOR_ORDER.get(0));
        }
        List<String> sanitizedPreferred = sanitizePreferredCharacterColors(user.getPreferredColorPriority());
        if (!sanitizedPreferred.isEmpty()) {
            return sanitizedPreferred;
        }
        String normalizedPrimary = normalizeCharacterColorId(user.getPrimaryColorId());
        if (!normalizedPrimary.isEmpty()) {
            return List.of(normalizedPrimary);
        }
        return List.of(CHARACTER_COLOR_ORDER.get(0));
    }

    private List<Long> getOrderedLobbyPlayerIdsHostFirst(Lobby lobby) {
        if (lobby == null || lobby.getPlayerIds() == null || lobby.getPlayerIds().isEmpty()) {
            return List.of();
        }
        Long hostId = lobby.getSessionHostUserId();
        List<Long> orderedIds = new ArrayList<>();
        if (hostId != null && lobby.getPlayerIds().contains(hostId)) {
            orderedIds.add(hostId);
        }
        lobby.getPlayerIds().stream()
                .filter(Objects::nonNull)
                .filter(playerId -> !Objects.equals(playerId, hostId))
                .sorted()
                .forEach(orderedIds::add);
        return orderedIds;
    }

    private boolean normalizeLobbyReadyStateInPlace(Lobby lobby) {
        if (lobby == null) {
            return false;
        }
        List<Long> playerIds = lobby.getPlayerIds();
        if (playerIds == null || playerIds.isEmpty()) {
            if (lobby.getPlayerReadyByUserId() == null || lobby.getPlayerReadyByUserId().isEmpty()) {
                return false;
            }
            lobby.setPlayerReadyByUserId(new HashMap<>());
            return true;
        }
        Map<Long, Boolean> existingReadyState = lobby.getPlayerReadyByUserId() == null
                ? new HashMap<>()
                : lobby.getPlayerReadyByUserId();
        Map<Long, Boolean> nextReadyState = new HashMap<>();
        Long hostId = lobby.getSessionHostUserId();
        for (Long playerId : playerIds) {
            if (playerId == null) {
                continue;
            }
            if (Objects.equals(playerId, hostId)) {
                nextReadyState.put(playerId, true);
                continue;
            }
            nextReadyState.put(playerId, Boolean.TRUE.equals(existingReadyState.get(playerId)));
        }
        if (nextReadyState.equals(existingReadyState)) {
            return false;
        }
        lobby.setPlayerReadyByUserId(nextReadyState);
        return true;
    }

    private boolean recomputeLobbyAssignedCharacterColorsInPlace(Lobby lobby) {
        if (lobby == null) {
            return false;
        }
        List<Long> orderedPlayerIds = getOrderedLobbyPlayerIdsHostFirst(lobby);
        if (orderedPlayerIds.isEmpty()) {
            if (lobby.getAssignedCharacterColorByUserId() == null || lobby.getAssignedCharacterColorByUserId().isEmpty()) {
                return false;
            }
            lobby.setAssignedCharacterColorByUserId(new HashMap<>());
            return true;
        }

        Map<Long, User> usersById = new HashMap<>();
        for (User user : userRepository.findAllById(orderedPlayerIds)) {
            if (user != null && user.getId() != null) {
                usersById.put(user.getId(), user);
            }
        }

        Map<Long, String> nextAssignedColors = new HashMap<>();
        Set<String> alreadyUsedColors = new LinkedHashSet<>();
        for (Long playerId : orderedPlayerIds) {
            User user = usersById.get(playerId);
            List<String> preferredColors = getPlayerColorPreferences(user);

            String selectedColor = "";
            for (String preferred : preferredColors) {
                if (!alreadyUsedColors.contains(preferred)) {
                    selectedColor = preferred;
                    break;
                }
            }
            if (selectedColor.isEmpty()) {
                for (String fallbackColor : CHARACTER_COLOR_ORDER) {
                    if (!alreadyUsedColors.contains(fallbackColor)) {
                        selectedColor = fallbackColor;
                        break;
                    }
                }
            }
            if (selectedColor.isEmpty()) {
                selectedColor = CHARACTER_COLOR_ORDER.get(0);
            }
            nextAssignedColors.put(playerId, selectedColor);
            alreadyUsedColors.add(selectedColor);
        }

        Map<Long, String> existingAssignedColors = lobby.getAssignedCharacterColorByUserId() == null
                ? new HashMap<>()
                : lobby.getAssignedCharacterColorByUserId();
        if (nextAssignedColors.equals(existingAssignedColors)) {
            return false;
        }
        lobby.setAssignedCharacterColorByUserId(nextAssignedColors);
        return true;
    }

    private boolean normalizeLobbyPlayerStateInPlace(Lobby lobby) {
        boolean changed = false;
        changed |= normalizeLobbyReadyStateInPlace(lobby);
        changed |= recomputeLobbyAssignedCharacterColorsInPlace(lobby);
        return changed;
    }

    private void resetLobbyReadyStateForWaiting(Lobby lobby) {
        if (lobby == null) {
            return;
        }
        List<Long> playerIds = lobby.getPlayerIds();
        Map<Long, Boolean> readyStateByUserId = new HashMap<>();
        Long hostId = lobby.getSessionHostUserId();
        if (playerIds != null) {
            for (Long playerId : playerIds) {
                if (playerId == null) {
                    continue;
                }
                readyStateByUserId.put(playerId, Objects.equals(playerId, hostId));
            }
        }
        lobby.setPlayerReadyByUserId(readyStateByUserId);
    }

    public boolean isPlayerTimedOutInPlaying(Long userId) {
        return userId != null && timedOutInPlayingPlayerIds.contains(userId);
    }

    public void clearTimedOutPlayingFlag(Long userId) {
        if (userId == null) {
            return;
        }
        timedOutInPlayingPlayerIds.remove(userId);
    }

    private void clearTimedOutPlayingFlags(List<Long> userIds) {
        if (userIds == null) {
            return;
        }
        for (Long userId : userIds) {
            clearTimedOutPlayingFlag(userId);
        }
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private long normalizeTimerValue(Long rawValue, long min, long max, long defaultValue) {
        long baseValue = rawValue == null ? defaultValue : rawValue;
        return clamp(baseValue, min, max);
    }

    private boolean normalizeLobbySettingsInPlace(Lobby lobby) {
        if (lobby == null) {
            return false;
        }

        boolean changed = false;

        long normalizedAfk = normalizeTimerValue(
                lobby.getAfkTimeoutSeconds(),
                lobbySettings.getAfkTimeoutMinSeconds(),
                lobbySettings.getAfkTimeoutMaxSeconds(),
                lobbySettings.getAfkTimeoutDefaultSeconds());
        if (!Long.valueOf(normalizedAfk).equals(lobby.getAfkTimeoutSeconds())) {
            lobby.setAfkTimeoutSeconds(normalizedAfk);
            changed = true;
        }

        long normalizedInitialPeek = normalizeTimerValue(
                lobby.getInitialPeekSeconds(),
                lobbySettings.getInitialPeekMinSeconds(),
                lobbySettings.getInitialPeekMaxSeconds(),
                lobbySettings.getInitialPeekDefaultSeconds());
        if (!Long.valueOf(normalizedInitialPeek).equals(lobby.getInitialPeekSeconds())) {
            lobby.setInitialPeekSeconds(normalizedInitialPeek);
            changed = true;
        }

        long normalizedTurn = normalizeTimerValue(
                lobby.getTurnSeconds(),
                lobbySettings.getTurnMinSeconds(),
                lobbySettings.getTurnMaxSeconds(),
                lobbySettings.getTurnDefaultSeconds());
        if (!Long.valueOf(normalizedTurn).equals(lobby.getTurnSeconds())) {
            lobby.setTurnSeconds(normalizedTurn);
            changed = true;
        }

        long normalizedAbilityReveal = normalizeTimerValue(
                lobby.getAbilityRevealSeconds(),
                lobbySettings.getAbilityRevealMinSeconds(),
                lobbySettings.getAbilityRevealMaxSeconds(),
                lobbySettings.getAbilityRevealDefaultSeconds());
        if (!Long.valueOf(normalizedAbilityReveal).equals(lobby.getAbilityRevealSeconds())) {
            lobby.setAbilityRevealSeconds(normalizedAbilityReveal);
            changed = true;
        }

        long normalizedAbilitySwap = normalizeTimerValue(
                lobby.getAbilitySwapSeconds(),
                lobbySettings.getAbilitySwapMinSeconds(),
                lobbySettings.getAbilitySwapMaxSeconds(),
                lobbySettings.getAbilitySwapDefaultSeconds());
        if (!Long.valueOf(normalizedAbilitySwap).equals(lobby.getAbilitySwapSeconds())) {
            lobby.setAbilitySwapSeconds(normalizedAbilitySwap);
            changed = true;
        }

        long normalizedAbsentRoundPoints = normalizeTimerValue(
                lobby.getAbsentRoundPoints(),
                lobbySettings.getAbsentRoundPointsMin(),
                lobbySettings.getAbsentRoundPointsMax(),
                lobbySettings.getAbsentRoundPointsDefault());
        if (!Long.valueOf(normalizedAbsentRoundPoints).equals(lobby.getAbsentRoundPoints())) {
            lobby.setAbsentRoundPoints(normalizedAbsentRoundPoints);
            changed = true;
        }

        long normalizedWebsocketGrace = normalizeTimerValue(
                lobby.getWebsocketGraceSeconds(),
                lobbySettings.getWebsocketGraceMinSeconds(),
                lobbySettings.getWebsocketGraceMaxSeconds(),
                lobbySettings.getWebsocketGraceDefaultSeconds());
        if (!Long.valueOf(normalizedWebsocketGrace).equals(lobby.getWebsocketGraceSeconds())) {
            lobby.setWebsocketGraceSeconds(normalizedWebsocketGrace);
            changed = true;
        }

        long normalizedChatCooldown = normalizeTimerValue(
                lobby.getChatCooldownSeconds(),
                lobbySettings.getChatCooldownMinSeconds(),
                lobbySettings.getChatCooldownMaxSeconds(),
                lobbySettings.getChatCooldownDefaultSeconds());
        if (!Long.valueOf(normalizedChatCooldown).equals(lobby.getChatCooldownSeconds())) {
            lobby.setChatCooldownSeconds(normalizedChatCooldown);
            changed = true;
        }

        return changed;
    }

    private void applyDefaultTimerSettings(Lobby lobby) {
        if (lobby == null) {
            return;
        }
        lobby.setAfkTimeoutSeconds(lobbySettings.getAfkTimeoutDefaultSeconds());
        lobby.setInitialPeekSeconds(lobbySettings.getInitialPeekDefaultSeconds());
        lobby.setTurnSeconds(lobbySettings.getTurnDefaultSeconds());
        lobby.setAbilityRevealSeconds(lobbySettings.getAbilityRevealDefaultSeconds());
        lobby.setAbilitySwapSeconds(lobbySettings.getAbilitySwapDefaultSeconds());
        lobby.setAbsentRoundPoints(lobbySettings.getAbsentRoundPointsDefault());
        lobby.setWebsocketGraceSeconds(lobbySettings.getWebsocketGraceDefaultSeconds());
        lobby.setChatCooldownSeconds(lobbySettings.getChatCooldownDefaultSeconds());
    }

    // generates a unique sessionId
    private String generateUniqueSessionId() {
        String sessionId;
        do {
            sessionId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } while (lobbyRepository.findBySessionId(sessionId) != null);
        return sessionId;
    }

    public boolean isUserInActiveGame(Long userId) {
        if (userId == null || gameRepository == null) {
            return false;
        }
        // Fast-path: if user is not in any PLAYING lobby, there cannot be an active game.
        if (!lobbyRepository.existsByStatusAndPlayerId("PLAYING", userId)) {
            return false;
        }
        return gameRepository.findGamesByPlayerId(userId).stream()
                .filter(game -> game != null && game.getStatus() != GameStatus.ROUND_ENDED)
                .anyMatch(game -> game.getOrderedPlayerIds() != null && game.getOrderedPlayerIds().contains(userId));
    }

    /**
     * True when the user currently belongs to a waiting or active-playing lobby.
     * Used by disconnect handling to decide whether websocket grace timers apply.
     */
    public boolean isUserInLobbyContext(Long userId) {
        if (userId == null) {
            return false;
        }
        return lobbyRepository.existsByStatusAndParticipantId("WAITING", userId)
                || lobbyRepository.existsByStatusAndParticipantId("PLAYING", userId);
    }

    public Set<Long> getPlayingLobbyPlayerIdsSnapshot() {
        Set<Long> playerIds = new LinkedHashSet<>();
        for (Lobby lobby : lobbyRepository.findByStatus("PLAYING")) {
            if (lobby != null && lobby.getPlayerIds() != null) {
                playerIds.addAll(lobby.getPlayerIds());
            }
        }
        return playerIds;
    }

    public Optional<Lobby> findLatestPlayingLobbyForSpectator(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return lobbyRepository.findByStatusAndParticipantId("PLAYING", userId).stream()
                .filter(lobby -> lobby != null
                        && lobby.getSpectatorIds() != null
                        && lobby.getSpectatorIds().contains(userId))
                .max(Comparator.comparing(Lobby::getId, Comparator.nullsLast(Long::compareTo)));
    }

    public Long findWebsocketGraceSecondsForUser(Long userId) {
        if (userId == null) {
            return null;
        }
        Lobby waitingLobby = lobbyRepository.findByStatusAndParticipantId("WAITING", userId).stream()
                .findFirst()
                .orElse(null);
        if (waitingLobby != null && waitingLobby.getWebsocketGraceSeconds() != null && waitingLobby.getWebsocketGraceSeconds() > 0) {
            return waitingLobby.getWebsocketGraceSeconds();
        }
        Lobby playingLobby = lobbyRepository.findByStatusAndParticipantId("PLAYING", userId).stream()
                .findFirst()
                .orElse(null);
        if (playingLobby != null && playingLobby.getWebsocketGraceSeconds() != null && playingLobby.getWebsocketGraceSeconds() > 0) {
            return playingLobby.getWebsocketGraceSeconds();
        }
        return null;
    }

    private void cleanupStalePlayingLobbiesForHost(Long hostUserId) {
        if (hostUserId == null) {
            return;
        }

        // If host is still in an active game, PLAYING lobby is legitimate.
        if (isUserInActiveGame(hostUserId)) {
            return;
        }

        List<Lobby> stalePlayingLobbies = lobbyRepository.findBySessionHostUserId(hostUserId).stream()
                .filter(lobby -> "PLAYING".equals(lobby.getStatus()))
                .toList();
        if (stalePlayingLobbies.isEmpty()) {
            return;
        }

        for (Lobby staleLobby : stalePlayingLobbies) {
            List<Long> playerIds = new ArrayList<>(staleLobby.getPlayerIds());
            List<Long> spectatorIds = staleLobby.getSpectatorIds() != null
                    ? new ArrayList<>(staleLobby.getSpectatorIds())
                    : List.of();
            lobbyRepository.delete(staleLobby);
            setUsersStatus(playerIds, UserStatus.ONLINE);
            setUsersStatus(spectatorIds, UserStatus.ONLINE);
        }
        onlineUsersEventPublisher.broadcastOnlineUsers();
    }

    private List<Lobby> findWaitingLobbiesForParticipant(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return lobbyRepository.findByStatusAndParticipantId("WAITING", userId);
    }

    public String findWaitingSessionIdForPlayer(Long userId) {
        return findWaitingLobbiesForParticipant(userId).stream()
                .filter(l -> l.getPlayerIds() != null && l.getPlayerIds().contains(userId))
                .max(Comparator.comparing(Lobby::getId, Comparator.nullsLast(Long::compareTo)))
                .map(Lobby::getSessionId)
                .orElse(null);
    }

    private boolean isLobbyNewer(Lobby candidate, Lobby reference) {
        if (candidate == null) {
            return false;
        }
        if (reference == null) {
            return true;
        }
        Long candidateId = candidate.getId();
        Long referenceId = reference.getId();
        if (candidateId != null && referenceId != null) {
            return candidateId > referenceId;
        }
        if (candidateId != null) {
            return true;
        }
        if (referenceId != null) {
            return false;
        }
        String candidateSessionId = candidate.getSessionId() == null ? "" : candidate.getSessionId();
        String referenceSessionId = reference.getSessionId() == null ? "" : reference.getSessionId();
        return candidateSessionId.compareTo(referenceSessionId) > 0;
    }

    private Set<Long> toRequestedUserIdSet(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> requestedUserIds = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId != null) {
                requestedUserIds.add(userId);
            }
        }
        return requestedUserIds;
    }

    private Map<Long, Lobby> resolveNewestJoinableLobbyByUserId(Set<Long> requestedUserIds) {
        if (requestedUserIds == null || requestedUserIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Lobby> newestPlayingLobbyByUserId = new HashMap<>();
        Map<Long, Lobby> newestWaitingLobbyByUserId = new HashMap<>();

        List<Lobby> playingLobbies = lobbyRepository.findByStatus("PLAYING");
        for (Lobby lobby : playingLobbies) {
            if (lobby == null) {
                continue;
            }
            List<Long> participantIds = new ArrayList<>();
            if (lobby.getPlayerIds() != null) {
                participantIds.addAll(lobby.getPlayerIds());
            }
            if (lobby.getSpectatorIds() != null) {
                participantIds.addAll(lobby.getSpectatorIds());
            }
            for (Long participantId : participantIds) {
                if (participantId == null || !requestedUserIds.contains(participantId)) {
                    continue;
                }
                Lobby knownLobby = newestPlayingLobbyByUserId.get(participantId);
                if (isLobbyNewer(lobby, knownLobby)) {
                    newestPlayingLobbyByUserId.put(participantId, lobby);
                }
            }
        }

        List<Lobby> waitingLobbies = lobbyRepository.findByStatus("WAITING");
        for (Lobby lobby : waitingLobbies) {
            if (lobby == null) {
                continue;
            }
            List<Long> participantIds = new ArrayList<>();
            if (lobby.getPlayerIds() != null) {
                participantIds.addAll(lobby.getPlayerIds());
            }
            if (lobby.getSpectatorIds() != null) {
                participantIds.addAll(lobby.getSpectatorIds());
            }
            for (Long participantId : participantIds) {
                if (participantId == null || !requestedUserIds.contains(participantId)) {
                    continue;
                }
                Lobby knownLobby = newestWaitingLobbyByUserId.get(participantId);
                if (isLobbyNewer(lobby, knownLobby)) {
                    newestWaitingLobbyByUserId.put(participantId, lobby);
                }
            }
        }

        Map<Long, Lobby> selectedLobbyByUserId = new HashMap<>();
        for (Long userId : requestedUserIds) {
            Lobby playingLobby = newestPlayingLobbyByUserId.get(userId);
            if (playingLobby != null) {
                selectedLobbyByUserId.put(userId, playingLobby);
                continue;
            }
            Lobby waitingLobby = newestWaitingLobbyByUserId.get(userId);
            if (waitingLobby != null) {
                selectedLobbyByUserId.put(userId, waitingLobby);
            }
        }
        return selectedLobbyByUserId;
    }

    public Map<Long, String> resolveJoinableSessionIdsForUsers(List<Long> userIds) {
        Set<Long> requestedUserIds = toRequestedUserIdSet(userIds);
        if (requestedUserIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Lobby> newestLobbyByUserId = resolveNewestJoinableLobbyByUserId(requestedUserIds);
        Map<Long, String> joinableSessionIdByUserId = new HashMap<>();
        for (Map.Entry<Long, Lobby> entry : newestLobbyByUserId.entrySet()) {
            String sessionId = entry.getValue() == null ? null : entry.getValue().getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                continue;
            }
            joinableSessionIdByUserId.put(entry.getKey(), sessionId);
        }
        return joinableSessionIdByUserId;
    }

    public String resolveJoinableSessionIdForUser(Long userId) {
        if (userId == null) {
            return null;
        }
        return resolveJoinableSessionIdsForUsers(List.of(userId)).get(userId);
    }

    public Map<Long, UserStatus> resolveLobbyPresenceStatusForUsers(List<Long> userIds) {
        Set<Long> requestedUserIds = toRequestedUserIdSet(userIds);
        if (requestedUserIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Lobby> newestLobbyByUserId = resolveNewestJoinableLobbyByUserId(requestedUserIds);
        Map<Long, UserStatus> statusByUserId = new HashMap<>();

        for (Map.Entry<Long, Lobby> entry : newestLobbyByUserId.entrySet()) {
            Long userId = entry.getKey();
            Lobby lobby = entry.getValue();
            if (userId == null || lobby == null) {
                continue;
            }
            boolean isSpectator = lobby.getSpectatorIds() != null && lobby.getSpectatorIds().contains(userId);
            if ("PLAYING".equals(lobby.getStatus())) {
                statusByUserId.put(userId, isSpectator ? UserStatus.SPECTATING : UserStatus.PLAYING);
                continue;
            }
            if ("WAITING".equals(lobby.getStatus())) {
                statusByUserId.put(userId, isSpectator ? UserStatus.SPECTATING : UserStatus.LOBBY);
            }
        }
        return statusByUserId;
    }

    public UserStatus resolveLobbyPresenceStatusForUser(Long userId) {
        if (userId == null) {
            return null;
        }
        return resolveLobbyPresenceStatusForUsers(List.of(userId)).get(userId);
    }

    /**
     * Guarantees a player only belongs to one waiting lobby at a time.
     * Keeps `keepSessionId` if provided and removes the player from all others.
     */
    private void leaveOtherWaitingLobbies(Long userId, String keepSessionId) {
        List<Lobby> otherWaitingLobbies = findWaitingLobbiesForParticipant(userId).stream()
                .filter(lobby -> keepSessionId == null || !keepSessionId.equals(lobby.getSessionId()))
                .toList();

        for (Lobby previousLobby : otherWaitingLobbies) {
            removePlayerFromDisconnect(previousLobby.getSessionId(), userId);
        }
    }

    private void leaveOtherPlayingSpectatorMemberships(Long userId, String keepSessionId) {
        if (userId == null) {
            return;
        }
        List<Lobby> playing = lobbyRepository.findByStatusAndParticipantId("PLAYING", userId);
        for (Lobby other : playing) {
            if (keepSessionId != null && keepSessionId.equals(other.getSessionId())) {
                continue;
            }
            if (other.getSpectatorIds() == null || !other.getSpectatorIds().contains(userId)) {
                continue;
            }
            other.getSpectatorIds().remove(userId);
            clearTimedOutPlayingFlag(userId);
            lobbyRepository.save(other);
            lobbyEventPublisher.broadcastLobbyUpdate(other.getId(), other);
        }
    }

    private boolean hasFreshHeartbeat(Long userId, long freshnessSeconds) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(User::getLastHeartbeat)
                .map(last -> last != null && last.isAfter(Instant.now().minusSeconds(freshnessSeconds)))
                .orElse(false);
    }

    // POST /lobbies — create a new lobby
    public Lobby createLobby(String token, Boolean isPublic) {
        User host = getUserByToken(token);
        if (isUserInActiveGame(host.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot create a lobby during an active game");
        }
        leaveOtherWaitingLobbies(host.getId(), null);
        leaveOtherPlayingSpectatorMemberships(host.getId(), null);
        cleanupStalePlayingLobbiesForHost(host.getId());
        boolean hostInActiveGame = isUserInActiveGame(host.getId());

        boolean hasActiveLobby = lobbyRepository.findBySessionHostUserId(host.getId()).stream()
                .anyMatch(l -> "WAITING".equals(l.getStatus())
                        || ("PLAYING".equals(l.getStatus()) && hostInActiveGame));
        if (hasActiveLobby) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have an active lobby");
        }

        Lobby lobby = new Lobby();
        lobby.setSessionId(generateUniqueSessionId());
        lobby.setSessionHostUserId(host.getId());
        lobby.setIsPublic(isPublic != null ? isPublic : true);
        lobby.getPlayerIds().add(host.getId());
        applyDefaultTimerSettings(lobby);
        normalizeLobbyPlayerStateInPlace(lobby);

        lobby = lobbyRepository.save(lobby);
        clearTimedOutPlayingFlag(host.getId());
        setUserStatus(host.getId(), UserStatus.LOBBY);  // set host user status to LOBBY after joining lobby
        lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        onlineUsersEventPublisher.broadcastOnlineUsers();
        return lobby;
    }

    public Optional<Lobby> findWaitingLobbyForHost(Long hostUserId) {
        return lobbyRepository.findBySessionHostUserId(hostUserId).stream()
                .filter(l -> "WAITING".equals(l.getStatus()))
                .findFirst();
    }

    public Lobby requireWaitingLobbyForHost(Long hostUserId) {
        return findWaitingLobbyForHost(hostUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Create a lobby first"));
    }

    public boolean isLobbyWaiting(Long lobbyId) {
        if (lobbyId == null) {
            return false;
        }
        return lobbyRepository.findById(lobbyId)
                .map(l -> "WAITING".equals(l.getStatus()))
                .orElse(false);
    }

    public void addPlayerToLobby(Long lobbyId, Long hostUserId, Long guestUserId) {
        Lobby lobby = lobbyRepository.findById(lobbyId)
                .orElse(null);
        if (lobby == null || !hostUserId.equals(lobby.getSessionHostUserId())) {
            return;
        }
        if (!"WAITING".equals(lobby.getStatus())) {
            return;
        }
        if (lobby.getPlayerIds().size() >= 4) {
            return;
        }
        if (isUserInActiveGame(guestUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot join a lobby during an active game");
        }
        if (lobby.getKickedUserIds() != null) {
            lobby.getKickedUserIds().remove(guestUserId);
        }
        if (lobby.getSpectatorIds() != null) {
            lobby.getSpectatorIds().remove(guestUserId);
        }
        if (!lobby.getPlayerIds().contains(guestUserId)) {
            leaveOtherWaitingLobbies(guestUserId, lobby.getSessionId());
            leaveOtherPlayingSpectatorMemberships(guestUserId, lobby.getSessionId());
            lobby.getPlayerIds().add(guestUserId);
            normalizeLobbyPlayerStateInPlace(lobby);
            lobby = lobbyRepository.save(lobby);
            clearTimedOutPlayingFlag(guestUserId);
            setUserStatus(guestUserId, UserStatus.LOBBY);  // set user status to LOBBY after joining lobby
            lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
            onlineUsersEventPublisher.broadcastOnlineUsers();
        }
    }

    public String getWaitingSessionIdIfPlayerInLobby(Long lobbyId, Long guestUserId) {
        return lobbyRepository.findById(lobbyId)
                .filter(l -> "WAITING".equals(l.getStatus()) && l.getPlayerIds().contains(guestUserId))
                .map(Lobby::getSessionId)
                .orElse(null);
    }

    public Lobby getMyWaitingLobbyAsHost(String token) {
        User requester = getUserByToken(token);
        return lobbyRepository.findByStatusAndParticipantId("WAITING", requester.getId()).stream()
                .max(Comparator.comparing(Lobby::getId, Comparator.nullsLast(Long::compareTo)))
                .or(() -> lobbyRepository.findBySessionHostUserId(requester.getId()).stream()
                        .filter(l -> "WAITING".equals(l.getStatus()))
                        .findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No waiting lobby"));
    }

    public WaitingLobbyViewDTO getWaitingLobbyView(String token, String sessionId) {
        User user = getUserByToken(token);
        Lobby lobby = lobbyRepository.findBySessionId(sessionId);
        if (lobby == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lobby not found");
       
        boolean isPlayer = lobby.getPlayerIds().contains(user.getId());
        boolean isSpectator = lobby.getSpectatorIds() != null && lobby.getSpectatorIds().contains(user.getId());
    
        if (!isPlayer && !isSpectator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not part of this lobby");
        }
        boolean lobbyChanged = normalizeLobbySettingsInPlace(lobby);
        lobbyChanged |= normalizeLobbyPlayerStateInPlace(lobby);
        if (lobbyChanged) {
            Lobby persistedLobby = lobbyRepository.save(lobby);
            if (persistedLobby != null) {
                lobby = persistedLobby;
            }
        }
    
        Long hostId = lobby.getSessionHostUserId();
    
        // Sort players so Host is always at the top
        List<Long> orderedIds = getOrderedLobbyPlayerIdsHostFirst(lobby);

        // players and spectators
        List<Long> allMemberIds = new ArrayList<>(orderedIds);
        if (lobby.getSpectatorIds() != null) {
            allMemberIds.addAll(lobby.getSpectatorIds()); // add spectators
        }

        Map<Long, User> usersById = new HashMap<>();
        for (User participant : userRepository.findAllById(allMemberIds)) {
            if (participant != null && participant.getId() != null) {
                usersById.put(participant.getId(), participant);
            }
        }

        WaitingLobbyViewDTO dto = new WaitingLobbyViewDTO();
        dto.setLobbyId(lobby.getId());
        dto.setSessionId(lobby.getSessionId());
        dto.setIsPublic(lobby.getIsPublic());
        dto.setAfkTimeoutSeconds(lobby.getAfkTimeoutSeconds());
        dto.setInitialPeekSeconds(lobby.getInitialPeekSeconds());
        dto.setTurnSeconds(lobby.getTurnSeconds());
        dto.setAbilityRevealSeconds(lobby.getAbilityRevealSeconds());
        dto.setAbilitySwapSeconds(lobby.getAbilitySwapSeconds());
        dto.setAbsentRoundPoints(lobby.getAbsentRoundPoints());
        dto.setWebsocketGraceSeconds(lobby.getWebsocketGraceSeconds());
        dto.setChatCooldownSeconds(lobby.getChatCooldownSeconds());
        dto.setViewerIsHost(user.getId().equals(hostId));

        Map<Long, String> assignedCharacterColorByUserId = lobby.getAssignedCharacterColorByUserId() == null
                ? new HashMap<>()
                : lobby.getAssignedCharacterColorByUserId();
        Map<Long, Boolean> readyStateByUserId = lobby.getPlayerReadyByUserId() == null
                ? new HashMap<>()
                : lobby.getPlayerReadyByUserId();
    
        List<WaitingLobbyPlayerRowDTO> rows = new ArrayList<>();
        for (Long pid : orderedIds) {
            User u = usersById.get(pid);
            if (u == null) continue;

            WaitingLobbyPlayerRowDTO row = new WaitingLobbyPlayerRowDTO();
            row.setUserId(pid);
            row.setUsername(u.getUsername());
            row.setProfileCharacterId(u.getProfileCharacterId());
            List<String> playerColorPreferences = getPlayerColorPreferences(u);
            String fallbackColor = playerColorPreferences.isEmpty()
                    ? CHARACTER_COLOR_ORDER.get(0)
                    : playerColorPreferences.get(0);
            row.setCharacterColorId(assignedCharacterColorByUserId.getOrDefault(
                    pid, fallbackColor));
            row.setReady(Objects.equals(pid, hostId) || Boolean.TRUE.equals(readyStateByUserId.get(pid)));

            // --- THIS IS THE CRITICAL LOGIC FOR THE START BUTTON ---
            // 1. If this row belongs to the Host, status MUST be "host"
            // 2. If it's NOT the host but it's "me", status is "you"
            // 3. Otherwise, it's just "joined"
            if (pid.equals(hostId)) {
                row.setJoinStatus("host"); 
            } else if (pid.equals(user.getId())) {
                row.setJoinStatus("you");
            } else {
                row.setJoinStatus("joined");
            }
        
            rows.add(row);
        }

        dto.setPlayers(rows);

        // fill spectator list
        List<WaitingLobbyPlayerRowDTO> spectatorRows = new ArrayList<>();
        if (lobby.getSpectatorIds() != null) {
            for (Long sid : lobby.getSpectatorIds()) {
                User u = usersById.get(sid);
                if (u == null) continue;
                WaitingLobbyPlayerRowDTO row = new WaitingLobbyPlayerRowDTO();
                row.setUserId(sid);
                row.setUsername(u.getUsername());
                row.setProfileCharacterId(u.getProfileCharacterId());
                List<String> spectatorColorPreferences = getPlayerColorPreferences(u);
                String spectatorColor = spectatorColorPreferences.isEmpty()
                        ? CHARACTER_COLOR_ORDER.get(0)
                        : spectatorColorPreferences.get(0);
                row.setCharacterColorId(spectatorColor);
                row.setReady(false);
                // "you" status for spectator
                row.setJoinStatus(sid.equals(user.getId()) ? "you" : "spectator"); 
                spectatorRows.add(row);
            }
        }
        dto.setSpectators(spectatorRows);

        return dto;
    }

    public WaitingLobbyViewDTO setPlayerReady(String sessionId, String token, Boolean ready) {
        if (ready == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing ready state");
        }
        User user = getUserByToken(token);
        Lobby lobby = lobbyRepository.findBySessionId(sessionId);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found");
        }
        if (!"WAITING".equals(lobby.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lobby is not in waiting state");
        }
        if (!lobby.getPlayerIds().contains(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only lobby players can set ready state");
        }

        normalizeLobbyPlayerStateInPlace(lobby);
        if (Objects.equals(user.getId(), lobby.getSessionHostUserId())) {
            // Host is always considered ready.
            return getWaitingLobbyView(token, sessionId);
        }

        Map<Long, Boolean> readyStateByUserId = lobby.getPlayerReadyByUserId() == null
                ? new HashMap<>()
                : new HashMap<>(lobby.getPlayerReadyByUserId());
        readyStateByUserId.put(user.getId(), ready);
        lobby.setPlayerReadyByUserId(readyStateByUserId);
        lobby = lobbyRepository.save(lobby);
        lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        return getWaitingLobbyView(token, sessionId);
    }

    public Lobby updateLobbySettings(String token, String sessionId, LobbySettingsPatchDTO body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No settings to update");
        }
        boolean hasAnySetting = body.getIsPublic() != null
                || body.getAfkTimeoutSeconds() != null
                || body.getInitialPeekSeconds() != null
                || body.getTurnSeconds() != null
                || body.getAbilityRevealSeconds() != null
                || body.getAbilitySwapSeconds() != null
                || body.getAbsentRoundPoints() != null
                || body.getWebsocketGraceSeconds() != null
                || body.getChatCooldownSeconds() != null;
        if (!hasAnySetting) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No settings to update");
        }
        User user = getUserByToken(token);
        Lobby lobby = lobbyRepository.findBySessionId(sessionId);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found");
        }
        if (!user.getId().equals(lobby.getSessionHostUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the session host can update lobby settings");
        }
        if (!"WAITING".equals(lobby.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid lobby settings update");
        }
        if (body.getIsPublic() != null) {
            lobby.setIsPublic(body.getIsPublic());
        }
        if (body.getAfkTimeoutSeconds() != null) {
            long value = clamp(
                    body.getAfkTimeoutSeconds(),
                    lobbySettings.getAfkTimeoutMinSeconds(),
                    lobbySettings.getAfkTimeoutMaxSeconds());
            lobby.setAfkTimeoutSeconds(value);
        }
        if (body.getInitialPeekSeconds() != null) {
            long value = clamp(
                    body.getInitialPeekSeconds(),
                    lobbySettings.getInitialPeekMinSeconds(),
                    lobbySettings.getInitialPeekMaxSeconds());
            lobby.setInitialPeekSeconds(value);
        }
        if (body.getTurnSeconds() != null) {
            long value = clamp(
                    body.getTurnSeconds(),
                    lobbySettings.getTurnMinSeconds(),
                    lobbySettings.getTurnMaxSeconds());
            lobby.setTurnSeconds(value);
        }
        if (body.getAbilityRevealSeconds() != null) {
            long value = clamp(
                    body.getAbilityRevealSeconds(),
                    lobbySettings.getAbilityRevealMinSeconds(),
                    lobbySettings.getAbilityRevealMaxSeconds());
            lobby.setAbilityRevealSeconds(value);
        }
        if (body.getAbilitySwapSeconds() != null) {
            long value = clamp(
                    body.getAbilitySwapSeconds(),
                    lobbySettings.getAbilitySwapMinSeconds(),
                    lobbySettings.getAbilitySwapMaxSeconds());
            lobby.setAbilitySwapSeconds(value);
        }
        if (body.getAbsentRoundPoints() != null) {
            long value = clamp(
                    body.getAbsentRoundPoints(),
                    lobbySettings.getAbsentRoundPointsMin(),
                    lobbySettings.getAbsentRoundPointsMax());
            lobby.setAbsentRoundPoints(value);
        }
        if (body.getWebsocketGraceSeconds() != null) {
            long value = clamp(
                    body.getWebsocketGraceSeconds(),
                    lobbySettings.getWebsocketGraceMinSeconds(),
                    lobbySettings.getWebsocketGraceMaxSeconds());
            lobby.setWebsocketGraceSeconds(value);
        }
        if (body.getChatCooldownSeconds() != null) {
            long value = clamp(
                    body.getChatCooldownSeconds(),
                    lobbySettings.getChatCooldownMinSeconds(),
                    lobbySettings.getChatCooldownMaxSeconds());
            lobby.setChatCooldownSeconds(value);
        }
        normalizeLobbySettingsInPlace(lobby);
        lobby = lobbyRepository.save(lobby);
        lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        return lobby;
    }

    // POST /lobbies/{sessionId}/players — join a lobby
    public Lobby joinLobby(String sessionId, String token) {
        User user = getUserByToken(token);
        Lobby lobby = lobbyRepository.findBySessionId(sessionId);

        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found");
        }
        if (!"WAITING".equals(lobby.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lobby is not in waiting state");
        }
        if (lobby.getPlayerIds().size() >= 4) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Player limit is 4!");
        }
        if (lobby.getPlayerIds().contains(user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already in lobby");
        }
        if (isUserInActiveGame(user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot join a lobby during an active game");
        }
        if (lobby.getKickedUserIds() != null && lobby.getKickedUserIds().contains(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You were kicked from this lobby");
        }

        leaveOtherWaitingLobbies(user.getId(), sessionId);
        leaveOtherPlayingSpectatorMemberships(user.getId(), sessionId);
        if (lobby.getSpectatorIds() != null) {
            lobby.getSpectatorIds().remove(user.getId());
        }
        lobby.getPlayerIds().add(user.getId());
        normalizeLobbyPlayerStateInPlace(lobby);
        lobby = lobbyRepository.save(lobby);
        clearTimedOutPlayingFlag(user.getId());
        setUserStatus(user.getId(), UserStatus.LOBBY); // set user status to LOBBY after joining lobby
        lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        onlineUsersEventPublisher.broadcastOnlineUsers();
        return lobby;
    }

    public Lobby joinLobbyAsSpectator(String sessionId, String token) {
        User user = getUserByToken(token);
        Lobby lobby = lobbyRepository.findBySessionId(sessionId);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found");
        }
        if (!"WAITING".equals(lobby.getStatus()) && !"PLAYING".equals(lobby.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lobby is not joinable as spectator");
        }
        if (lobby.getKickedUserIds() != null && lobby.getKickedUserIds().contains(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You were kicked from this lobby");
        }
        if (lobby.getPlayerIds() != null && lobby.getPlayerIds().contains(user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already a player in this lobby");
        }
        // if user is player in another game
        if (isUserInActiveGame(user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot spectate during an active game");
        }
        // if already spectator in this lobby: re-broadcast and return the lobby
        if (lobby.getSpectatorIds() != null && lobby.getSpectatorIds().contains(user.getId())) {
            lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
            return lobby;
        }

        leaveOtherWaitingLobbies(user.getId(), sessionId);
        leaveOtherPlayingSpectatorMemberships(user.getId(), sessionId);

        lobby.getSpectatorIds().add(user.getId());
        lobby = lobbyRepository.save(lobby);
        setUserStatus(user.getId(), UserStatus.SPECTATING);
        lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        onlineUsersEventPublisher.broadcastOnlineUsers();
        return lobby;
    }

    public Lobby verifyLobbyCanStart(String token, String sessionId) {
        User requester = getUserByToken(token);
        Lobby lobby = lobbyRepository.findBySessionId(sessionId);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found");
        }
        if (!requester.getId().equals(lobby.getSessionHostUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the session host can start the game");
        }
        if (!"WAITING".equals(lobby.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lobby is not in waiting state");
        }
        if (lobby.getPlayerIds() == null || lobby.getPlayerIds().size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least 2 players are required");
        }
        if (normalizeLobbyPlayerStateInPlace(lobby)) {
            lobby = lobbyRepository.save(lobby);
        }
        Map<Long, Boolean> readyStateByUserId = lobby.getPlayerReadyByUserId() == null
                ? new HashMap<>()
                : lobby.getPlayerReadyByUserId();
        Long hostId = lobby.getSessionHostUserId();
        for (Long playerId : lobby.getPlayerIds()) {
            if (Objects.equals(playerId, hostId)) {
                continue;
            }
            if (!Boolean.TRUE.equals(readyStateByUserId.get(playerId))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "All non-host players must be ready");
            }
        }

        // Ensure no player is currently in the "60s grace period"
        for (Long pid : lobby.getPlayerIds()) {
            if (disconnectService != null && disconnectService.isPlayerInGracePeriod(pid)) {
                // If the player has a fresh heartbeat, the grace flag is stale (e.g. websocket reconnect race).
                // Clear it and allow start.
                if (hasFreshHeartbeat(pid, 45)) {
                    disconnectService.cancelDisconnectTimer(pid);
                    continue;
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Player disconnected");
            }
        }

        return lobby;
    }

    // set players as PLAYING and spectators as SPECTATING
    public void markLobbyAsPlaying(String sessionId) {
        Lobby lobby = lobbyRepository.findBySessionId(sessionId);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found");
        }

        lobby.setStatus("PLAYING");
        lobbyRepository.save(lobby);
        clearTimedOutPlayingFlags(lobby.getPlayerIds());
        setUsersStatus(lobby.getPlayerIds(), UserStatus.PLAYING);
        if (lobby.getSpectatorIds() != null && !lobby.getSpectatorIds().isEmpty()) {
            setUsersStatus(lobby.getSpectatorIds(), UserStatus.SPECTATING);
        }
        lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        onlineUsersEventPublisher.broadcastOnlineUsers();
    }

    /**
     * Called when a game round reaches ROUND_ENDED.
     * Transitions the matching PLAYING lobby back to WAITING and updates present players to LOBBY.
     */
    public void handleRoundEndedForGamePlayers(List<Long> gamePlayerIds) {
        handleRoundResolvedForGamePlayers(gamePlayerIds, List.of());
    }

    public void handleRoundResolvedForGamePlayers(List<Long> gamePlayerIds, List<Long> rematchPlayerIds) {
        handleRoundResolvedForGamePlayers(gamePlayerIds, rematchPlayerIds, List.of());
    }

    public void handleRoundResolvedForGamePlayers(
            List<Long> gamePlayerIds,
            List<Long> continueRematchPlayerIds,
            List<Long> freshRematchPlayerIds) {
        handleRoundResolvedForGamePlayers(gamePlayerIds, continueRematchPlayerIds, freshRematchPlayerIds, null);
    }

    public void handleRoundResolvedForGamePlayers(
            List<Long> gamePlayerIds,
            List<Long> continueRematchPlayerIds,
            List<Long> freshRematchPlayerIds,
            Long freshRematchRequesterUserId) {
        if (gamePlayerIds == null || gamePlayerIds.isEmpty()) {
            return;
        }

        List<Long> orderedGamePlayers = new ArrayList<>(new LinkedHashSet<>(gamePlayerIds));
        Set<Long> expectedPlayerSet = new LinkedHashSet<>(orderedGamePlayers);
        List<Lobby> playingLobbies = lobbyRepository.findByStatus("PLAYING");
        Lobby currentLobby = playingLobbies.stream()
                .filter(lobby -> lobby.getPlayerIds() != null)
                .filter(lobby -> new LinkedHashSet<>(lobby.getPlayerIds()).equals(expectedPlayerSet))
                .findFirst()
                .orElseGet(() -> playingLobbies.stream()
                        .filter(lobby -> lobby.getPlayerIds() != null
                                && !Collections.disjoint(lobby.getPlayerIds(), expectedPlayerSet))
                        .max(Comparator.comparingInt(lobby ->
                                (int) lobby.getPlayerIds().stream().filter(expectedPlayerSet::contains).count()))
                        .orElse(null));

        if (currentLobby == null) {
            clearTimedOutPlayingFlags(gamePlayerIds);
            setUsersStatus(gamePlayerIds, UserStatus.ONLINE);
            onlineUsersEventPublisher.broadcastOnlineUsers();
            return;
        }

        clearTimedOutPlayingFlags(orderedGamePlayers);

        Set<Long> continueRematchSet = continueRematchPlayerIds == null
                ? Set.of()
                : new LinkedHashSet<>(continueRematchPlayerIds);
        Set<Long> freshRematchSet = freshRematchPlayerIds == null
                ? Set.of()
                : new LinkedHashSet<>(freshRematchPlayerIds);

        List<Long> normalizedContinuePlayers = orderedGamePlayers.stream()
                .filter(continueRematchSet::contains)
                .toList();
        List<Long> normalizedFreshPlayers = orderedGamePlayers.stream()
                .filter(freshRematchSet::contains)
                .toList();

        List<Long> effectiveContinuePlayers = normalizedContinuePlayers.size() >= 2
                ? normalizedContinuePlayers
                : List.of();
        List<Long> effectiveFreshPlayers = normalizedFreshPlayers.size() >= 2
                ? normalizedFreshPlayers
                : List.of();
        List<Long> currentSpectators = currentLobby.getSpectatorIds() == null
                ? List.of()
                : currentLobby.getSpectatorIds().stream()
                        .filter(id -> id != null)
                        .distinct()
                        .toList();

        Boolean templateIsPublic = Boolean.TRUE.equals(currentLobby.getIsPublic());
        Long templateAfkTimeoutSeconds = currentLobby.getAfkTimeoutSeconds();
        Long templateInitialPeekSeconds = currentLobby.getInitialPeekSeconds();
        Long templateTurnSeconds = currentLobby.getTurnSeconds();
        Long templateAbilityRevealSeconds = currentLobby.getAbilityRevealSeconds();
        Long templateAbilitySwapSeconds = currentLobby.getAbilitySwapSeconds();
        Long templateAbsentRoundPoints = currentLobby.getAbsentRoundPoints();
        Long templateWebsocketGraceSeconds = currentLobby.getWebsocketGraceSeconds();
        Long templateChatCooldownSeconds = currentLobby.getChatCooldownSeconds();

        List<Long> spectatorsToReleaseOnline = List.of();
        String continueLobbySessionId = null;
        if (!effectiveContinuePlayers.isEmpty()) {
            currentLobby.setStatus("WAITING");
            currentLobby.setSessionHostUserId(effectiveContinuePlayers.get(0));
            currentLobby.setPlayerIds(new ArrayList<>(effectiveContinuePlayers));
            currentLobby.setKickedUserIds(new ArrayList<>());
            resetLobbyReadyStateForWaiting(currentLobby);
            normalizeLobbyPlayerStateInPlace(currentLobby);
            currentLobby = lobbyRepository.save(currentLobby);
            continueLobbySessionId = currentLobby.getSessionId();
            setUsersStatus(effectiveContinuePlayers, UserStatus.LOBBY);
            lobbyEventPublisher.broadcastLobbyUpdate(currentLobby.getId(), currentLobby);
        } else {
            String endedSessionId = currentLobby.getSessionId();
            lobbyRepository.delete(currentLobby);
            if (lobbyChatService != null) {
                lobbyChatService.clearSessionMessages(endedSessionId);
            }
            spectatorsToReleaseOnline = currentSpectators;
        }

        String freshLobbySessionId = null;
        if (!effectiveFreshPlayers.isEmpty()) {
            Lobby freshLobby = new Lobby();
            freshLobby.setSessionId(generateUniqueSessionId());
            Long freshHostUserId = effectiveFreshPlayers.get(0);
            if (freshRematchRequesterUserId != null && effectiveFreshPlayers.contains(freshRematchRequesterUserId)) {
                freshHostUserId = freshRematchRequesterUserId;
            }
            freshLobby.setSessionHostUserId(freshHostUserId);
            freshLobby.setIsPublic(templateIsPublic);
            freshLobby.setStatus("WAITING");
            freshLobby.setPlayerIds(new ArrayList<>(effectiveFreshPlayers));
            boolean shouldCarrySpectatorsToFreshLobby = effectiveContinuePlayers.isEmpty() && !currentSpectators.isEmpty();
            if (shouldCarrySpectatorsToFreshLobby) {
                freshLobby.setSpectatorIds(new ArrayList<>(currentSpectators));
            }
            freshLobby.setKickedUserIds(new ArrayList<>());
            freshLobby.setAfkTimeoutSeconds(templateAfkTimeoutSeconds);
            freshLobby.setInitialPeekSeconds(templateInitialPeekSeconds);
            freshLobby.setTurnSeconds(templateTurnSeconds);
            freshLobby.setAbilityRevealSeconds(templateAbilityRevealSeconds);
            freshLobby.setAbilitySwapSeconds(templateAbilitySwapSeconds);
            freshLobby.setAbsentRoundPoints(templateAbsentRoundPoints);
            freshLobby.setWebsocketGraceSeconds(templateWebsocketGraceSeconds);
            freshLobby.setChatCooldownSeconds(templateChatCooldownSeconds);
            resetLobbyReadyStateForWaiting(freshLobby);
            normalizeLobbyPlayerStateInPlace(freshLobby);
            freshLobby = lobbyRepository.save(freshLobby);
            freshLobbySessionId = freshLobby.getSessionId();
            setUsersStatus(effectiveFreshPlayers, UserStatus.LOBBY);
            if (shouldCarrySpectatorsToFreshLobby) {
                setUsersStatus(currentSpectators, UserStatus.SPECTATING);
                spectatorsToReleaseOnline = List.of();
            }
            lobbyEventPublisher.broadcastLobbyUpdate(freshLobby.getId(), freshLobby);
        }
        if (!spectatorsToReleaseOnline.isEmpty()) {
            setUsersStatus(spectatorsToReleaseOnline, UserStatus.ONLINE);
        }

        for (Long continuePlayerId : effectiveContinuePlayers) {
            leaveOtherWaitingLobbies(continuePlayerId, continueLobbySessionId);
        }
        for (Long freshPlayerId : effectiveFreshPlayers) {
            leaveOtherWaitingLobbies(freshPlayerId, freshLobbySessionId);
        }

        Set<Long> allRematchPlayers = new LinkedHashSet<>(effectiveContinuePlayers);
        allRematchPlayers.addAll(effectiveFreshPlayers);
        List<Long> nonRematchPlayers = orderedGamePlayers.stream()
                .filter(playerId -> !allRematchPlayers.contains(playerId))
                .toList();
        setUsersStatus(nonRematchPlayers, UserStatus.ONLINE);
        onlineUsersEventPublisher.broadcastOnlineUsers();
    }

    private Lobby findBestPlayingLobbyForPlayers(List<Long> gamePlayerIds) {
        if (gamePlayerIds == null || gamePlayerIds.isEmpty()) {
            return null;
        }
        Set<Long> expectedPlayerSet = new LinkedHashSet<>(gamePlayerIds);
        List<Lobby> playingLobbies = lobbyRepository.findByStatus("PLAYING");
        return playingLobbies.stream()
                .filter(lobby -> lobby.getPlayerIds() != null)
                .filter(lobby -> new LinkedHashSet<>(lobby.getPlayerIds()).equals(expectedPlayerSet))
                .findFirst()
                .orElseGet(() -> playingLobbies.stream()
                        .filter(lobby -> lobby.getPlayerIds() != null
                                && !Collections.disjoint(lobby.getPlayerIds(), expectedPlayerSet))
                        .max(Comparator.comparingInt(lobby ->
                                (int) lobby.getPlayerIds().stream().filter(expectedPlayerSet::contains).count()))
                        .orElse(null));
    }

    // lookup of matching PLAYING status lobby while round-end resolve is in progress (same player set)
    public String findPlayingSessionIdForPlayers(List<Long> gamePlayerIds) {
        Lobby matchingLobby = findBestPlayingLobbyForPlayers(gamePlayerIds);
        return matchingLobby == null ? null : matchingLobby.getSessionId();
    }

    public List<Long> findPlayingSpectatorIdsForPlayers(List<Long> gamePlayerIds) {
        Lobby matchingLobby = findBestPlayingLobbyForPlayers(gamePlayerIds);
        if (matchingLobby == null || matchingLobby.getSpectatorIds() == null) {
            return List.of();
        }
        return matchingLobby.getSpectatorIds().stream()
                .filter(id -> id != null)
                .distinct()
                .toList();
    }

    // GET /lobbies — get all public lobbies
    public void refreshWaitingLobbyPresentationForUser(Long userId) {
        if (userId == null) {
            return;
        }
        List<Lobby> affectedLobbies = lobbyRepository.findByStatusAndParticipantId("WAITING", userId);
        if (affectedLobbies.isEmpty()) {
            return;
        }
        for (Lobby lobby : affectedLobbies) {
            if (!normalizeLobbyPlayerStateInPlace(lobby)) {
                continue;
            }
            lobby = lobbyRepository.save(lobby);
            lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        }
    }

    public List<Lobby> getPublicLobbies(String token) {
        User requester = getUserByToken(token);
        return lobbyRepository.findByIsPublicTrueAndStatus("WAITING").stream()
                .filter(l -> l.getPlayerIds().size() < 4)
                .filter(l -> l.getKickedUserIds() == null || !l.getKickedUserIds().contains(requester.getId()))
                .toList();
    }

    // get lobby by id — used by WebSocketController
    public Lobby getLobbyById(Long lobbyId) {
        return lobbyRepository.findById(lobbyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found!"));
    }

    public Optional<Lobby> findLobbyById(Long lobbyId) {
        if (lobbyId == null) {
            return Optional.empty();
        }
        return lobbyRepository.findById(lobbyId);
    }

    public Lobby getLobbyBySessionId(String sessionId) {
        Lobby lobby = lobbyRepository.findBySessionId(sessionId);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session could not be found!");
        }
        return lobby;
    }


    private void deleteLobbyAndReleaseSpectatorsIfAny(Lobby lobby) {
        if (lobby == null) {
            return;
        }
        List<Long> spectatorIds = lobby.getSpectatorIds() != null
                ? new ArrayList<>(lobby.getSpectatorIds())
                : List.of();
        String sessionId = lobby.getSessionId();
        lobbyRepository.delete(lobby);
        if (lobbyChatService != null && sessionId != null && !sessionId.isBlank()) {
            lobbyChatService.clearSessionMessages(sessionId);
        }
        if (!spectatorIds.isEmpty()) {
            setUsersStatus(spectatorIds, UserStatus.ONLINE);
        }
    }
    // remove player from lobby — self leave or host kick
    @Transactional
    public Lobby removePlayerFromLobby(String sessionId, String token, Long targetUserId) {
        User requester = getUserByToken(token);
        Lobby lobby = getLobbyBySessionId(sessionId);

        // only the player themselves or the host can remove a player
        boolean isSelf = requester.getId().equals(targetUserId);
        boolean isHost = requester.getId().equals(lobby.getSessionHostUserId());

        if (!isSelf && !isHost) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden!");
        }

        boolean isPlayer = lobby.getPlayerIds().contains(targetUserId);
        boolean isSpectator = lobby.getSpectatorIds() != null && lobby.getSpectatorIds().contains(targetUserId);

        if (!isPlayer && !isSpectator) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User is not part of this lobby");
        }

        if ("PLAYING".equals(lobby.getStatus()) && isPlayer) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Players cannot be removed from an active game");
        }

        if (isPlayer) {
            lobby.getPlayerIds().remove(targetUserId);
        } else {
            lobby.getSpectatorIds().remove(targetUserId);
        }

        clearTimedOutPlayingFlag(targetUserId);
        setUserStatus(targetUserId, UserStatus.ONLINE);
        if (isHost && !isSelf) {
            if (lobby.getKickedUserIds() == null) {
                lobby.setKickedUserIds(new ArrayList<>());
            }
            if (!lobby.getKickedUserIds().contains(targetUserId)) {
                lobby.getKickedUserIds().add(targetUserId);
            }
        }

        // if no players & no spectators left — delete the lobby
        boolean noPlayers = lobby.getPlayerIds() == null || lobby.getPlayerIds().isEmpty();
        if (noPlayers) {
            deleteLobbyAndReleaseSpectatorsIfAny(lobby);
            onlineUsersEventPublisher.broadcastOnlineUsers();
            return null;
        }

        // if the removed player was the host — migrate to next player -> spectator cannot be host
        if (lobby.getSessionHostUserId().equals(targetUserId) && !lobby.getPlayerIds().isEmpty()) {
            lobby.setSessionHostUserId(lobby.getPlayerIds().get(0));
        }

        normalizeLobbyPlayerStateInPlace(lobby);
        lobby = lobbyRepository.save(lobby);
        lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        onlineUsersEventPublisher.broadcastOnlineUsers();
        return lobby;
    }

    public void handlePermanentDisconnect(Long userId) {
        Lobby lobby = lobbyRepository.findByStatusAndParticipantId("WAITING", userId).stream()
                .findFirst()
                .orElseGet(() -> lobbyRepository.findByStatusAndParticipantId("PLAYING", userId).stream()
                        .findFirst()
                        .orElse(null));

        if (lobby == null) {
            clearTimedOutPlayingFlag(userId);
            setUserStatus(userId, UserStatus.OFFLINE);
            onlineUsersEventPublisher.broadcastOnlineUsers();
            return;
        }

        if ("WAITING".equals(lobby.getStatus())) {
            clearTimedOutPlayingFlag(userId);
            setUserStatus(userId, UserStatus.OFFLINE);
            this.removePlayerFromDisconnect(lobby.getSessionId(), userId);
        } else if ("PLAYING".equals(lobby.getStatus())) {
            boolean isPlayer = lobby.getPlayerIds() != null && lobby.getPlayerIds().contains(userId);
            if (isPlayer) {
                timedOutInPlayingPlayerIds.add(userId);
            } else {
                // remove spectator on disconnect
                if (lobby.getSpectatorIds() != null) {
                    lobby.getSpectatorIds().remove(userId);
                }
                setUserStatus(userId, UserStatus.OFFLINE);
                lobbyRepository.save(lobby);
                lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
            }
            onlineUsersEventPublisher.broadcastOnlineUsers();
        } else {
            clearTimedOutPlayingFlag(userId);
            setUserStatus(userId, UserStatus.OFFLINE);
            onlineUsersEventPublisher.broadcastOnlineUsers();
        }
    }

    public void removePlayerFromDisconnect(String sessionId, Long userId) {
        Lobby lobby = getLobbyBySessionId(sessionId);
        // only players are disconnected, spectators are removed immediately
        if ("PLAYING".equals(lobby.getStatus()) && lobby.getPlayerIds().contains(userId)) {
            timedOutInPlayingPlayerIds.add(userId);
            return;
        }
        lobby.getPlayerIds().remove(userId);
        if (lobby.getSpectatorIds() != null) {
            lobby.getSpectatorIds().remove(userId);
        }
        clearTimedOutPlayingFlag(userId);

        boolean noPlayers = lobby.getPlayerIds() == null || lobby.getPlayerIds().isEmpty();

        if (noPlayers) {
            deleteLobbyAndReleaseSpectatorsIfAny(lobby);
        } else {
            if (lobby.getSessionHostUserId().equals(userId) && !lobby.getPlayerIds().isEmpty()) {
                lobby.setSessionHostUserId(lobby.getPlayerIds().get(0));
            }
            normalizeLobbyPlayerStateInPlace(lobby);
            lobbyRepository.save(lobby);
            lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        }
        onlineUsersEventPublisher.broadcastOnlineUsers();
    }

    @Transactional
    public Lobby removeSpectatorFromLobby(String sessionId, String token, Long targetUserId) {
        User requester = getUserByToken(token);
        Lobby lobby = getLobbyBySessionId(sessionId);

        boolean isSelf = requester.getId().equals(targetUserId);
        boolean isHost = requester.getId().equals(lobby.getSessionHostUserId());
        if (!isSelf && !isHost) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden!");
        }

        if (lobby.getSpectatorIds() == null || !lobby.getSpectatorIds().contains(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User is not a spectator in this lobby");
        }

        lobby.getSpectatorIds().remove(targetUserId);
        clearTimedOutPlayingFlag(targetUserId);
        setUserStatus(targetUserId, UserStatus.ONLINE);

        boolean noPlayers = lobby.getPlayerIds() == null || lobby.getPlayerIds().isEmpty();
        if (noPlayers) {
            deleteLobbyAndReleaseSpectatorsIfAny(lobby);
            onlineUsersEventPublisher.broadcastOnlineUsers();
            return null;
        }

        lobby = lobbyRepository.save(lobby);
        lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby);
        onlineUsersEventPublisher.broadcastOnlineUsers();
        return lobby;
    }
}

