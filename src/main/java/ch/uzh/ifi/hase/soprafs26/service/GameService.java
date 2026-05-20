package ch.uzh.ifi.hase.soprafs26.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Iterator;
import java.util.zip.CRC32;


import ch.uzh.ifi.hase.soprafs26.config.settings.GameSettingsProperties;
import ch.uzh.ifi.hase.soprafs26.config.settings.ServerSettingsProperties;
import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.GameMoveEvent;
import ch.uzh.ifi.hase.soprafs26.entity.GameMoveStep;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.GameRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameSyncStateDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.PeekSelectionDTO;
import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.util.PeekType;
import ch.uzh.ifi.hase.soprafs26.entity.PlayerActionEvent;

import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;

// added TEMPORARY FALLBACK, SINCE DECKAPI IS UNRELIABLE FOR TESTING
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    public static final String REMATCH_DECISION_CONTINUE = "CONTINUE";
    public static final String REMATCH_DECISION_FRESH = "FRESH";
    public static final String REMATCH_DECISION_NONE = "NONE";
    private static final long CACHE_CLEANUP_MIN_INTERVAL_MS = 1000L;

    public static final class SyncStateSnapshot {
        private final GameSyncStateDTO payload;
        private final String etag;

        public SyncStateSnapshot(GameSyncStateDTO payload, String etag) {
            this.payload = payload;
            this.etag = etag;
        }

        public GameSyncStateDTO getPayload() {
            return payload;
        }

        public String getEtag() {
            return etag;
        }
    }

    private static final class CachedAuthenticatedUser {
        private final User user;
        private final long expiresAtMs;

        private CachedAuthenticatedUser(User user, long expiresAtMs) {
            this.user = user;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final class CachedSyncState {
        private final SyncStateSnapshot snapshot;
        private final long expiresAtMs;

        private CachedSyncState(SyncStateSnapshot snapshot, long expiresAtMs) {
            this.snapshot = snapshot;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final List<String> FALLBACK_CABO_CARD_CODES = Arrays.asList(
            "AS", "AD", "AC", "AH",
            "2S", "2D", "2C", "2H",
            "3S", "3D", "3C", "3H",
            "4S", "4D", "4C", "4H",
            "5S", "5D", "5C", "5H",
            "6S", "6D", "6C", "6H",
            "7S", "7D", "7C", "7H",
            "8S", "8D", "8C", "8H",
            "9S", "9D", "9C", "9H",
            "0S", "0D", "0C", "0H",
            "JS", "JD", "JC", "JH",
            "QS", "QD", "QC", "QH",
            "KS", "KC", "X1", "X2"
    );

    private final GameRepository gameRepository;
    private final DeckOfCardsAPIService deckOfCardsAPIService;
    private final UserRepository userRepository;
    private final GameEventPublisher gameEventPublisher;
    private final ScheduledExecutorService scheduler;
    private final LobbyService lobbyService;
    private final LobbyChatService lobbyChatService;
    private final SessionRepository sessionRepository;
    private final GameSettingsProperties gameSettings;
    private final ServerSettingsProperties serverSettings;
    // map to store tasks - key: gameId, value: scheduled task
    private final Map<String, ScheduledFuture<?>> gameTimers = new ConcurrentHashMap<>();
    // per-game count so an outdated scheduled ability timer cannot execute 
    // already existing cancel mechanism alone is not always enough if a task was queued/running
    // AtomicLong: enables thread-safe increments
    private final Map<String, AtomicLong> abilityTimerCounts = new ConcurrentHashMap<>();
    // per-game count to guard rematch decision timers from executing stale tasks
    private final Map<String, AtomicLong> rematchDecisionTimerCounts = new ConcurrentHashMap<>();
    // per-game lock to serialize rematch decision writes/resolution and avoid race conditions
    private final Map<String, Object> rematchResolutionLocks = new ConcurrentHashMap<>();
    private static final String MOVE_ZONE_DRAW_PILE = "DRAW_PILE";
    private static final String MOVE_ZONE_DISCARD_PILE = "DISCARD_PILE";
    private static final String MOVE_ZONE_HAND = "HAND";
    private static final long INTRO_PHASE_SECONDS = 10L;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, CachedAuthenticatedUser> authenticatedUserByTokenCache = new ConcurrentHashMap<>();
    private final AtomicLong lastAuthCacheCleanupMs = new AtomicLong(0L);
    private final Map<String, CachedSyncState> syncStateCacheByViewerAndGame = new ConcurrentHashMap<>();
    private final AtomicLong lastSyncStateCacheCleanupMs = new AtomicLong(0L);
    private final Map<String, Long> rematchDecisionDedupUntilByKey = new ConcurrentHashMap<>();
    private final AtomicLong lastRematchDecisionDedupCleanupMs = new AtomicLong(0L);

    private Object getRematchResolutionLock(String gameId) {
        return rematchResolutionLocks.computeIfAbsent(gameId, ignored -> new Object());
    }

    private long resolvePositiveOrDefault(Long candidate, long defaultValue) {
        if (candidate == null || candidate <= 0) {
            return defaultValue;
        }
        return candidate;
    }

    private long resolveCaboRevealSecondsForPlayerCount(List<Long> playerIds) {
        int playerCount = playerIds == null ? 0 : (int) playerIds.stream().filter(id -> id != null).distinct().count();
        if (playerCount == 2) {
            return 30L;
        }
        if (playerCount == 3) {
            return 40L;
        }
        if (playerCount == 4) {
            return 50L;
        }
        return resolvePositiveOrDefault(null, gameSettings.getCaboRevealSeconds());
    }

    private GameMoveStep createMoveStep(String sourceZone, Long sourceUserId, Integer sourceCardIndex,
                                        String targetZone, Long targetUserId, Integer targetCardIndex,
                                        boolean hidden, Integer value) {
        GameMoveStep step = new GameMoveStep();
        step.setSourceZone(sourceZone);
        step.setSourceUserId(sourceUserId);
        step.setSourceCardIndex(sourceCardIndex);
        step.setTargetZone(targetZone);
        step.setTargetUserId(targetUserId);
        step.setTargetCardIndex(targetCardIndex);
        step.setHidden(hidden);
        step.setValue(hidden ? null : value);
        return step;
    }

    private void setLastMoveEvent(Game game, Long actorUserId, GameMoveStep primary, GameMoveStep secondary) {
        if (game == null || primary == null) {
            return;
        }
        long sequence = game.getLastMoveSequence() + 1;
        game.setLastMoveSequence(sequence);

        GameMoveEvent event = new GameMoveEvent();
        event.setSequence(sequence);
        event.setActorUserId(actorUserId);
        event.setPrimary(primary);
        event.setSecondary(secondary);
        game.setLastMoveEvent(event);
    }

    // constructor injection
    public GameService(GameRepository gameRepository, DeckOfCardsAPIService deckOfCardsAPIService,
                       UserRepository userRepository, GameEventPublisher gameEventPublisher,
                       ScheduledExecutorService scheduler, GameSettingsProperties gameSettings) {
        this(
                gameRepository,
                deckOfCardsAPIService,
                userRepository,
                gameEventPublisher,
                scheduler,
                null,
                null,
                gameSettings,
                null,
                null,
                null);
    }

    // Used by Spring: allows game lifecycle -> lobby lifecycle handoff after round end.
    @Autowired
    public GameService(GameRepository gameRepository, DeckOfCardsAPIService deckOfCardsAPIService,
                       UserRepository userRepository, GameEventPublisher gameEventPublisher,
                       ScheduledExecutorService scheduler, @Lazy LobbyService lobbyService,
                       @Lazy SessionRepository sessionRepository,
                       GameSettingsProperties gameSettings,
                       @Lazy LobbyChatService lobbyChatService,
                       ApplicationEventPublisher eventPublisher,
                       @Lazy ServerSettingsProperties serverSettings) {
        this.gameRepository = gameRepository;
        this.deckOfCardsAPIService = deckOfCardsAPIService;
        this.userRepository = userRepository;
        this.gameEventPublisher = gameEventPublisher;
        this.scheduler = scheduler;
        this.lobbyService = lobbyService;
        this.sessionRepository = sessionRepository;
        this.gameSettings = gameSettings;
        this.lobbyChatService = lobbyChatService;
        this.eventPublisher = eventPublisher;
        this.serverSettings = serverSettings == null ? new ServerSettingsProperties() : serverSettings;
    }

    public Game startGame(List<Long> playerIds) {
        return startGame(playerIds, null);
    }

    public Game startGame(List<Long> playerIds, Lobby lobbyConfig) {
        List<Long> sanitizedPlayerIds = sanitizePlayerIds(playerIds);
        validatePlayerCount(sanitizedPlayerIds);

        // create a new game
        Game newGame = new Game();
        buildInitialDrawPile(newGame);
        List<Card> drawPile = newGame.getDrawPile();
        // initialize the player hands
        Map<Long, List<Card>> playerHands = new HashMap<>();

        // give each player an empty hand
        for (Long id:sanitizedPlayerIds) {
            playerHands.put(id, new ArrayList<>());
        }

        int cardsNeeded = (gameSettings.getStarterCardsPerPlayer() * sanitizedPlayerIds.size()) + 1;
        if (drawPile.size() < cardsNeeded) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not initialize deck");
        }

        // do four rounds of dealing each player one card from the draw pile
        for (int i = 0; i < gameSettings.getStarterCardsPerPlayer(); i++) {
            for (Long id:sanitizedPlayerIds) {
                Card card = drawPile.remove(0);
                playerHands.get(id).add(card);
            }
        }

        // create a discard pile, get one card from the draw pile and place it face up on the 
        // discard pile
        List<Card> discardPile = new ArrayList<>();
        Card firstCard = drawPile.remove(0);
        firstCard.setVisibility(true);
        discardPile.add(firstCard);

        // update all piles and player hands
        newGame.setPlayerHands(playerHands);
        newGame.setDiscardPile(discardPile);
        newGame.setDrawPile(drawPile);
        newGame.setOrderedPlayerIds(new ArrayList<>(sanitizedPlayerIds));
        newGame.setCurrentPlayerId(null);
        newGame.setStatus(GameStatus.INTRO);

        long resolvedTurnSeconds = resolvePositiveOrDefault(
                lobbyConfig != null ? lobbyConfig.getTurnSeconds() : null,
                gameSettings.getTurnSeconds());
        long resolvedInitialPeekSeconds = resolvePositiveOrDefault(
                lobbyConfig != null ? lobbyConfig.getInitialPeekSeconds() : null,
                gameSettings.getInitialPeekSeconds());
        long resolvedAbilityRevealSeconds = resolvePositiveOrDefault(
                lobbyConfig != null ? lobbyConfig.getAbilityRevealSeconds() : null,
                gameSettings.getPostPeekAutoEndSeconds());
        long resolvedAbilitySwapSeconds = resolvePositiveOrDefault(
                lobbyConfig != null ? lobbyConfig.getAbilitySwapSeconds() : null,
                gameSettings.getAbilitySwapSeconds());
        long resolvedCaboRevealSeconds = resolveCaboRevealSecondsForPlayerCount(sanitizedPlayerIds);
        // Rematch decision timer is fixed globally (not lobby-adjustable).
        long resolvedRematchDecisionSeconds = resolvePositiveOrDefault(
                gameSettings.getRematchDecisionSeconds(),
                60L);
        long resolvedAfkTimeoutSeconds = resolvePositiveOrDefault(
                lobbyConfig != null ? lobbyConfig.getAfkTimeoutSeconds() : null,
                300L);

        newGame.setTurnSeconds(resolvedTurnSeconds);
        newGame.setInitialPeekSeconds(resolvedInitialPeekSeconds);
        newGame.setAbilityRevealSeconds(resolvedAbilityRevealSeconds);
        newGame.setAbilitySwapSeconds(resolvedAbilitySwapSeconds);
        newGame.setCaboRevealSeconds(resolvedCaboRevealSeconds);
        newGame.setRematchDecisionSeconds(resolvedRematchDecisionSeconds);
        newGame.setAfkTimeoutSeconds(resolvedAfkTimeoutSeconds);
        ensureSessionExistsForLobbyIfNeeded(lobbyConfig, sanitizedPlayerIds);
        // Save first to get a generated game id, then start the timer.
        Game saved = saveGameAndBroadcast(newGame);
        if (saved.getId() != null && !saved.getId().isBlank()) {
            startIntroTimer(saved.getId());
        }

        // startTurnTimer(saved.getId(), saved.getCurrentPlayerId());
        return saved;
    }

    public Game resumeGame(Long sessionId) {
        // 1. get session from DB
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.isEnded()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session already finished");
        }

        // 2. get player ID's
        List<Long> playerIds = new ArrayList<>(session.getTotalScoreByUserId().keySet()); 

        if (playerIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No players found in this session");
        }

        // 3. start new game with those players
        Game resumedGame = startGame(playerIds);
    
        resumedGame.setResumedFromSessionId(sessionId);
        Game saved = gameRepository.save(resumedGame);
        invalidateSyncStateCacheForGame(saved.getId());
        return saved;
    }

    private List<Long> sanitizePlayerIds(List<Long> playerIds) {
        if (playerIds == null) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long playerId : playerIds) {
            if (playerId != null) {
                unique.add(playerId);
            }
        }
        return new ArrayList<>(unique);
    }
    
    // EXCEPTION FOR PLAYER AMOUNT REQUIREMENTS
    private void validatePlayerCount(List<Long> playerIds) {
        if (playerIds.size() < gameSettings.getMinPlayers() || playerIds.size() > gameSettings.getMaxPlayers()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lobby requires 2 to 4 players");
        }
    }

    // ALSO HAS TEMP??? FALLBACK IN IT for building deck
    private void buildInitialDrawPile(Game newGame) {
        try {
            // create new deck at the api and get its id
            String deckId = deckOfCardsAPIService.createNewDeckId();
            // shuffle the api deck
            deckOfCardsAPIService.shuffleDeck(deckId);
            // draw all cards from the api deck 
            List<CardDTO> apiCards = deckOfCardsAPIService.drawFromDeck(deckId, 52);
            if (apiCards == null || apiCards.isEmpty()) {
                throw new IllegalStateException("Deck API draw returned no cards");
            }
            // convert DTO representation to Entity representation for cards
            List<Card> converted = DTOMapper.INSTANCE.convertCardDTOListtoEntityList(apiCards);
            if (converted == null || converted.isEmpty()) {
                throw new IllegalStateException("Card conversion produced empty list");
            }
            // set the deckId and the DrawPile
            newGame.setDeckApiId(deckId);
            newGame.setDrawPile(new ArrayList<>(converted));
        } catch (Exception ex) {
            System.err.println("Deck API unavailable for startGame; using fallback deck: " + ex.getMessage());
            // if api didnt work and we fallback, DeckApiId is null
            newGame.setDeckApiId(null);
            newGame.setDrawPile(buildFallbackDeck());
        }
    }

    // TEMP??? FALLBACK for building deck
    private List<Card> buildFallbackDeck() {
        List<Card> fallback = new ArrayList<>();
        for (String code : FALLBACK_CABO_CARD_CODES) {
            Card card = new Card();
            card.setCode(code);
            card.setVisibility(false);
            card.setValue(mapCardCodeToValue(code));
            fallback.add(card);
        }
        Collections.shuffle(fallback);
        return fallback;
    }

    private int mapCardCodeToValue(String code) {
        if (code == null || code.isBlank()) {
            return 0;
        }
        char firstChar = code.charAt(0);
        return switch (firstChar) {
            case 'X' -> 0;
            case 'A' -> 1;
            case '0' -> 10;
            case 'J' -> 11;
            case 'Q' -> 12;
            case 'K' -> 13;
            default -> Character.getNumericValue(firstChar);
        };
    }

    // helper method that shuffles the discard pile, called when the draw pile is empty
    private void reshuffleDiscardPile(String gameId) {
        Game game = getGameById(gameId);
        List<Card> discardPile = game.getDiscardPile();
        if (discardPile.isEmpty()) {
            return;
        }
        // Keep the top card (same logic as in getDiscardPileTopCard) in the discard pile
        // put the rest into draw pile

        // index of top card from discard pile
        int topIdx = discardPile.size() - 1;
        // remove top card from discard pile
        Card topCard = discardPile.remove(topIdx);

        // use the rest of the discard pile to put into draw pile
        List<Card> toPutIntoDrawPile = new ArrayList<>(discardPile);

        // empty discard pile and put only the top card there
        discardPile.clear();
        discardPile.add(topCard);

        // edge case: nothing to put into draw pile
        // set empty draw pile and save
        if (toPutIntoDrawPile.isEmpty()) {
            game.setDrawPile(new ArrayList<>());
            saveGameAndBroadcast(game);
            return;
        }

        // get the deck id from the game
        String deckId = game.getDeckApiId();
        try {
            if (deckId != null) {
                // return cards to the deck at the api
                deckOfCardsAPIService.returnDrawnCardsToDeck(deckId, toPutIntoDrawPile);
                // shuffle the deck
                deckOfCardsAPIService.shuffleDeck(deckId);
                // draw from the deck
                List<CardDTO> dtos = deckOfCardsAPIService.drawFromDeck(deckId, toPutIntoDrawPile.size());
                // set the draw pile, converting from CardDTO to Card Entity representation
                game.setDrawPile(new ArrayList<>(DTOMapper.INSTANCE.convertCardDTOListtoEntityList(dtos)));
            } else {
                // if deck id is null (setting the deckId has initially failed) and we have been using a fallback deck
                Collections.shuffle(toPutIntoDrawPile);
                game.setDrawPile(toPutIntoDrawPile);
            }
        } catch (Exception ex) {
            // if there was an error while talking to the api - fallback to Java's shuffle 
            System.err.println("Deck API reshuffle failed; using Java's shuffle: " + ex.getMessage());
            Collections.shuffle(toPutIntoDrawPile);
            game.setDrawPile(toPutIntoDrawPile);
        }
        saveGameAndBroadcast(game);
    }

    // Backlog #9: Implement logic to always render the DiscardPile top card with its face-up value
    public Game getGameById(String gameId) {
        return gameRepository.findById(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Game not found"));
    }


    public Card getDiscardPileTopCard(String gameId) {
        Game game = getGameById(gameId);
        List<Card> discardPile = game.getDiscardPile();

        if (discardPile.isEmpty()) {
            return null;
        }


        Card topCard = discardPile.get(discardPile.size() - 1);
        topCard.setVisibility(true);
        return topCard;
    }

    //# 8: Implement a global isMyTurn state that disables all buttons and click listeners on the game board when false.
    // this method says if it is the Users turn or not
    public boolean isMyTurn(String gameId, Long userId) {
        Game game = getGameById(gameId);
        return userId.equals(game.getCurrentPlayerId());
    }

    private long resolveAuthTokenCacheTtlMs() {
        return Math.max(1L, serverSettings.getAuthTokenLookupCacheTtlMs());
    }

    private long resolveSyncStateCacheTtlMs() {
        return Math.max(1L, serverSettings.getSyncStateCacheTtlMs());
    }

    private long resolveRematchDecisionDedupWindowMs() {
        return Math.max(1L, serverSettings.getRematchDuplicateDecisionWindowMs());
    }

    private String normalizeToken(String token) {
        return token == null ? "" : token.trim();
    }

    private User requireAuthenticatedUser(String token) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }

        long nowMs = System.currentTimeMillis();
        CachedAuthenticatedUser cachedUser = authenticatedUserByTokenCache.get(normalizedToken);
        if (cachedUser != null && cachedUser.expiresAtMs > nowMs && cachedUser.user != null) {
            return cachedUser.user;
        }

        User user = userRepository.findByToken(normalizedToken);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }

        long ttlMs = resolveAuthTokenCacheTtlMs();
        authenticatedUserByTokenCache.put(normalizedToken, new CachedAuthenticatedUser(user, nowMs + ttlMs));
        maybeCleanupAuthenticatedUserCache(nowMs);
        return user;
    }

    private void maybeCleanupAuthenticatedUserCache(long nowMs) {
        long previousCleanupMs = lastAuthCacheCleanupMs.get();
        if (nowMs - previousCleanupMs < CACHE_CLEANUP_MIN_INTERVAL_MS) {
            return;
        }
        if (!lastAuthCacheCleanupMs.compareAndSet(previousCleanupMs, nowMs)) {
            return;
        }
        Iterator<Map.Entry<String, CachedAuthenticatedUser>> iterator = authenticatedUserByTokenCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedAuthenticatedUser> entry = iterator.next();
            CachedAuthenticatedUser cachedUser = entry.getValue();
            if (cachedUser == null || cachedUser.expiresAtMs <= nowMs || cachedUser.user == null) {
                iterator.remove();
            }
        }
    }

    private boolean isSpectatorForPlayingLobby(List<Long> orderedPlayers, Long userId) {
        if (userId == null || lobbyService == null) {
            return false;
        }
        List<Long> spectatorIds = lobbyService.findPlayingSpectatorIdsForPlayers(orderedPlayers);
        return spectatorIds != null && spectatorIds.contains(userId);
    }

    private Card copyCardForResponse(Card sourceCard, boolean forceVisible) {
        if (sourceCard == null) {
            return null;
        }
        Card copy = new Card();
        copy.setCode(sourceCard.getCode());
        copy.setValue(sourceCard.getValue());
        copy.setVisibility(forceVisible || sourceCard.getVisibility());
        return copy;
    }

    private Card resolveDiscardTopCardForResponse(Game game) {
        if (game == null || game.getDiscardPile() == null || game.getDiscardPile().isEmpty()) {
            return null;
        }
        Card topCard = game.getDiscardPile().get(game.getDiscardPile().size() - 1);
        return copyCardForResponse(topCard, true);
    }

    private Card resolveDrawnCardForViewer(Game game, Long viewerUserId, boolean participant) {
        if (game == null || viewerUserId == null) {
            return null;
        }

        Card drawnCard = game.getDrawnCard();
        if (drawnCard == null) {
            return null;
        }

        if (allInPlayCardsReveal(game.getStatus()) && participant) {
            return copyCardForResponse(drawnCard, true);
        }

        if (!viewerUserId.equals(game.getCurrentPlayerId())) {
            return null;
        }

        return copyCardForResponse(drawnCard, false);
    }

    private String buildSyncStateCacheKey(String gameId, Long viewerUserId) {
        String normalizedGameId = gameId == null ? "" : gameId.trim();
        return normalizedGameId + "|" + String.valueOf(viewerUserId);
    }

    private String computeSyncStateEtag(GameSyncStateDTO syncState) {
        CRC32 crc = new CRC32();
        updateChecksum(crc, syncState == null ? null : syncState.getStatus());
        updateChecksum(crc, syncState == null ? null : syncState.getCurrentTurnUserId());
        updateChecksum(crc, syncState == null ? null : syncState.getPostRoundSessionId());
        updateChecksum(crc, syncState == null ? null : syncState.getRematchDecisionSeconds());
        updateChecksumWithCard(crc, syncState == null ? null : syncState.getDiscardTop());
        updateChecksumWithCard(crc, syncState == null ? null : syncState.getDrawnCard());
        updateChecksumWithCards(crc, syncState == null ? null : syncState.getMyHand());
        return "\"" + Long.toHexString(crc.getValue()) + "\"";
    }

    private void updateChecksum(CRC32 crc, Object value) {
        String normalized = value == null ? "null" : String.valueOf(value);
        byte[] bytes = normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        crc.update('|');
    }

    private void updateChecksumWithCard(CRC32 crc, Card card) {
        if (card == null) {
            updateChecksum(crc, null);
            return;
        }
        updateChecksum(crc, card.getCode());
        updateChecksum(crc, card.getValue());
        updateChecksum(crc, card.getVisibility());
    }

    private void updateChecksumWithCards(CRC32 crc, List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            updateChecksum(crc, "[]");
            return;
        }
        for (Card card : cards) {
            updateChecksumWithCard(crc, card);
        }
    }

    private void maybeCleanupSyncStateCache(long nowMs) {
        long previousCleanupMs = lastSyncStateCacheCleanupMs.get();
        if (nowMs - previousCleanupMs < CACHE_CLEANUP_MIN_INTERVAL_MS) {
            return;
        }
        if (!lastSyncStateCacheCleanupMs.compareAndSet(previousCleanupMs, nowMs)) {
            return;
        }
        Iterator<Map.Entry<String, CachedSyncState>> iterator = syncStateCacheByViewerAndGame.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedSyncState> entry = iterator.next();
            CachedSyncState cachedSyncState = entry.getValue();
            if (cachedSyncState == null
                    || cachedSyncState.expiresAtMs <= nowMs
                    || cachedSyncState.snapshot == null
                    || cachedSyncState.snapshot.getPayload() == null
                    || cachedSyncState.snapshot.getEtag() == null) {
                iterator.remove();
            }
        }
    }

    private GameSyncStateDTO buildSyncStatePayload(Game game, User viewer, boolean participant) {
        GameSyncStateDTO syncState = new GameSyncStateDTO();
        syncState.setStatus(game.getStatus() == null ? null : game.getStatus().name());
        syncState.setCurrentTurnUserId(game.getCurrentPlayerId());
        syncState.setDiscardTop(resolveDiscardTopCardForResponse(game));
        syncState.setDrawnCard(resolveDrawnCardForViewer(game, viewer.getId(), participant));
        syncState.setMyHand(participant
                ? new ArrayList<>(game.getPlayerHands().getOrDefault(viewer.getId(), List.of()))
                : List.of());

        if (participant) {
            syncState.setRematchDecisionSeconds(game.getRematchDecisionSeconds());
            if (game.getStatus() == GameStatus.ROUND_AWAITING_REMATCH || game.getStatus() == GameStatus.ROUND_ENDED) {
                String waitingSessionId = lobbyService == null
                        ? null
                        : lobbyService.findWaitingSessionIdForPlayer(viewer.getId());
                if (waitingSessionId != null && !waitingSessionId.isBlank()) {
                    syncState.setPostRoundSessionId(waitingSessionId);
                }
            }
        }
        return syncState;
    }

    @Transactional(readOnly = true)
    public SyncStateSnapshot getSyncStateSnapshotForToken(String gameId, String token) {
        User viewer = requireAuthenticatedUser(token);
        Game game = getGameById(gameId);
        List<Long> orderedPlayers = game.getOrderedPlayerIds() == null
                ? List.of()
                : game.getOrderedPlayerIds();
        boolean participant = orderedPlayers.contains(viewer.getId());
        boolean spectator = !participant && isSpectatorForPlayingLobby(orderedPlayers, viewer.getId());
        if (!participant && !spectator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to view this game");
        }

        long nowMs = System.currentTimeMillis();
        String cacheKey = buildSyncStateCacheKey(gameId, viewer.getId());
        CachedSyncState cachedSyncState = syncStateCacheByViewerAndGame.get(cacheKey);
        if (cachedSyncState != null && cachedSyncState.expiresAtMs > nowMs && cachedSyncState.snapshot != null) {
            return cachedSyncState.snapshot;
        }

        GameSyncStateDTO syncState = buildSyncStatePayload(game, viewer, participant);
        SyncStateSnapshot snapshot = new SyncStateSnapshot(syncState, computeSyncStateEtag(syncState));
        long ttlMs = resolveSyncStateCacheTtlMs();
        syncStateCacheByViewerAndGame.put(cacheKey, new CachedSyncState(snapshot, nowMs + ttlMs));
        maybeCleanupSyncStateCache(nowMs);
        return snapshot;
    }

    @Transactional(readOnly = true)
    public GameSyncStateDTO getSyncStateForToken(String gameId, String token) {
        return getSyncStateSnapshotForToken(gameId, token).getPayload();
    }

    @Transactional(readOnly = true)
    public Long getCurrentTurnOwnerForToken(String gameId, String token) {
        User user = requireAuthenticatedUser(token);

        Game game = getGameById(gameId);
        List<Long> orderedPlayers = game.getOrderedPlayerIds() == null
                ? List.of()
                : game.getOrderedPlayerIds();
        boolean participant = orderedPlayers.contains(user.getId());
        boolean spectator = !participant && isSpectatorForPlayingLobby(orderedPlayers, user.getId());
        if (!participant && !spectator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to view this game");
        }

        return game.getCurrentPlayerId();
    }

    // get the player's own hand
    @Transactional(readOnly = true)
    public List<Card> getMyHand(String gameId, String token) {
        User user = requireAuthenticatedUser(token);
        Game game = getGameById(gameId);
        List<Card> hand = game.getPlayerHands().get(user.getId());
        if (hand == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a player in this game");
        }
        return hand;
    }


    // Add a "Current Player" check to all incoming move requests; return a 403 Forbidden if it's not their turn. #30
    public void verifyMoveCallerIsCurrentPlayer(String gameId, String authorizationToken) {
        User user = requireAuthenticatedUser(authorizationToken);
        if (lobbyService != null) {
            lobbyService.clearTimedOutPlayingFlag(user.getId());
        }
        Game game = getGameById(gameId);
        if (!user.getId().equals(game.getCurrentPlayerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your turn");
        }
    }


     // #47 initial peek + per-user broadcast 
     // #49 authentication guards.
    public void applyPeek(String gameId, String token, PeekSelectionDTO body) {
        User authenticatedUser = requireAuthenticatedUser(token);
        if (body == null || body.getPeekType() == null || body.getPeekType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "peekType is required");
        }
        // Locale.ROOT ensures consistent character manipulation regardless of server's language settings 
        String peekType = body.getPeekType().trim().toLowerCase(Locale.ROOT);
        if (!PeekType.INITIAL.equals(peekType) && !PeekType.SPECIAL.equals(peekType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "peekType must be \"initial\" or \"special\"");
        }

        Game game = getGameById(gameId);

        if (PeekType.SPECIAL.equals(peekType)) {
            applySpecialPeek(game, authenticatedUser, body);
            return;
        }

        applyInitialPeek(game, authenticatedUser, body);
    }

    private void applyInitialPeek(Game game, User authenticatedUser, PeekSelectionDTO body) {
        Long authenticatedUserId = authenticatedUser.getId();
        Long handUserId = body.getHandUserId();
        if (handUserId != null && !handUserId.equals(authenticatedUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot peek another player's hand");
        }

        List<Integer> indices = body.getIndices();
        if (indices == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indices required");
        }
        if (indices.size() != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exactly two card indices required");
        }
        Integer a = indices.get(0);
        Integer b = indices.get(1);
        if (a == null || b == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indices cannot be null");
        }
        if (a.equals(b)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indices must be distinct");
        }

        if (game.getStatus() != GameStatus.INITIAL_PEEK) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not in initial peek phase");
        }

        Map<Long, Boolean> performedInitialPeek = game.getInitialPeekDoneByUserId();
        if (performedInitialPeek == null) {
            performedInitialPeek = new HashMap<>();
            game.setInitialPeekDoneByUserId(performedInitialPeek);
        }
        // use Boolean.TRUE cause .get() may return a null
        if (Boolean.TRUE.equals(performedInitialPeek.get(authenticatedUserId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Initial peek already used");
        }

        List<Card> hand = game.getPlayerHands().get(authenticatedUserId);
        if (hand == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a player in this game");
        }
        if (a < 0 || a >= hand.size() || b < 0 || b >= hand.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Index out of range");
        }

        // reset visibility, then reveal two selected cards
        for (Card c : hand) {
            if (c != null) {
                c.setVisibility(false);
            }
        }
        hand.get(a).setVisibility(true);
        hand.get(b).setVisibility(true);

        performedInitialPeek.put(authenticatedUserId, true);
        saveGameAndBroadcast(game);
    }

    // 7/8 (own card) or 9/10 (opponent card): reveal one card and broadcast; phase ends when the ability timer fires (same idea as initial peek + global timer).
    private void applySpecialPeek(Game game, User authenticatedUser, PeekSelectionDTO body) {
        Long currentId = game.getCurrentPlayerId();
        if (currentId == null || !authenticatedUser.getId().equals(currentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your turn");
        }
        GameStatus status = game.getStatus();
        if (status != GameStatus.ABILITY_PEEK_SELF && status != GameStatus.ABILITY_PEEK_OPPONENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No special peek ability active");
        }

        // check if peek was already used
        if (game.isSpecialPeekUsed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Peek ability already used");
        }

        List<Integer> indices = body.getIndices();
        if (indices == null || indices.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exactly one card index required");
        }
        Integer idx = indices.get(0);
        if (idx == null || idx < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid index");
        }

        Long handOwnerId;
        if (status == GameStatus.ABILITY_PEEK_SELF) {
            Long handUserId = body.getHandUserId();
            if (handUserId != null && !handUserId.equals(currentId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Can only peek your own hand");
            }
            handOwnerId = currentId;
        } else {
            Long handUserId = body.getHandUserId();
            if (handUserId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "handUserId required for opponent peek");
            }
            if (handUserId.equals(currentId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot peek your own hand in opponent peek");
            }
            List<Long> ordered = game.getOrderedPlayerIds();
            if (ordered == null || !ordered.contains(handUserId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target player is not in this game");
            }
            handOwnerId = handUserId;
        }

        Map<Long, List<Card>> hands = game.getPlayerHands();
        List<Card> hand = hands == null ? null : hands.get(handOwnerId);
        if (hand == null || idx >= hand.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Index out of range");
        }

        for (Card c : hand) {
            if (c != null) {
                c.setVisibility(false);
            }
        }
        hand.get(idx).setVisibility(true);

        if (eventPublisher != null) {
            String sessionId = null;
            if (lobbyService != null) {
                sessionId = lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds());
            }   
            PlayerActionEvent actionEvent = new PlayerActionEvent();
            actionEvent.setSessionId(sessionId);
            actionEvent.setUserId(authenticatedUser.getId());
            
            if (status == GameStatus.ABILITY_PEEK_SELF) {
                actionEvent.setActionType("ABILITY PEEK");
                actionEvent.setDetails("Player peeked at their own card");
            } else {
                actionEvent.setActionType("ABILITY SPY");
                actionEvent.setDetails("Player spied on an opponent's card");
            }
            eventPublisher.publishEvent(actionEvent);
        }

        // mark peek as used
        game.setSpecialPeekUsed(true);

        saveGameAndBroadcast(game);
        // Keep the revealed card visible briefly, then auto-end peek/spy ability.
        startAbilityTimer(game.getId(), game.getAbilityRevealSeconds());
    }

    private void clearAllHandVisibility(Game game) {
        List<Long> players = game.getOrderedPlayerIds();
        if (players == null) {
            return;
        }
        Map<Long, List<Card>> hands = game.getPlayerHands();
        if (hands == null) {
            return;
        }
        for (Long id : players) {
            List<Card> hand = hands.get(id);
            if (hand != null) {
                for (Card c : hand) {
                    if (c != null) {
                        c.setVisibility(false);
                    }
                }
            }
        }
    }

    // #89 when round ends, all in-play cards are face-up in persisted game 
    private void revealAllInPlayCardsForRoundEnd(Game game) {
        Map<Long, List<Card>> hands = game.getPlayerHands();
        if (hands != null) {
            for (List<Card> hand : hands.values()) {
                if (hand == null) {
                    continue;
                }
                for (Card c : hand) {
                    if (c != null) {
                        c.setVisibility(true);
                    }
                }
            }
        }
        Card drawn = game.getDrawnCard();
        if (drawn != null) {
            drawn.setVisibility(true);
        }
    }

    private static boolean allInPlayCardsReveal(GameStatus status) {
        return status == GameStatus.CABO_REVEAL
                || status == GameStatus.ROUND_AWAITING_REMATCH
                || status == GameStatus.ROUND_ENDED;
    }

    // to save and broadcast: saveGameAndBroadcast(game)
    public void moveDrawFromDrawPile(String gameId, String token) {

        verifyMoveCallerIsCurrentPlayer(gameId, token);

        Game game = getGameById(gameId);

        // block drawing during initial peek phase
        if (game.getStatus() != GameStatus.ROUND_ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot draw a card right now");
        }

        // trigger reshuffle if draw pile is empty
        if (game.getDrawPile().isEmpty()) {
            reshuffleDiscardPile(gameId);
            game = getGameById(gameId); // reload game after reshuffle
        }       

        // draw the top card from the draw pile
        Card drawnCard = game.getDrawPile().remove(0);
        drawnCard.setVisibility(true);
        game.setDrawnCard(drawnCard);
        game.setDrawnFromDeck(true); // mark that this card came from the deck

        if (eventPublisher != null) {
            String sessionId = null;
            if (lobbyService != null) {
                sessionId = lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds());
            }
            PlayerActionEvent actionEvent = new PlayerActionEvent();
            actionEvent.setSessionId(sessionId);
            actionEvent.setUserId(game.getCurrentPlayerId());
            actionEvent.setActionType("DRAW");
            actionEvent.setDetails("player drew card from the draw pile");
            eventPublisher.publishEvent(actionEvent);
        }

        saveGameAndBroadcast(game);
    }
    
    // allows to pick a card from the discard pile and safe it into the field drawn card
    public void moveDrawFromDiscardPile(String gameId, String token) {
        verifyMoveCallerIsCurrentPlayer(gameId, token);
        Game game = getGameById(gameId);
        List<Card> discardPile = game.getDiscardPile();
        if (!game.getStatus().equals(GameStatus.ROUND_ACTIVE)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Round is not active.");
        }
        if (game.getDrawnCard() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already drawn a card!");
        }
        if (discardPile.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Discard pile is empty.");
        }
        Card drawnCard = discardPile.remove(discardPile.size()-1);
        game.setDrawnCard(drawnCard);

        if (eventPublisher != null) {
            String sessionId = null;
            if(lobbyService != null) {
                sessionId = lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds());
            }
            PlayerActionEvent actionEvent = new PlayerActionEvent();
            actionEvent.setSessionId(sessionId);
            actionEvent.setUserId(game.getCurrentPlayerId());
            actionEvent.setActionType("DRAW");
            actionEvent.setDetails("player drew card from discard pile");
            eventPublisher.publishEvent(actionEvent);
        }

        saveGameAndBroadcast(game);
    }

    // the equivalent to moveDrawFromDrawPile - takes the current drawn card and places it on the 
    // discard pile
    public void moveCardToDiscardPile(String gameId, String token) {

        verifyMoveCallerIsCurrentPlayer(gameId, token);
        Game game = getGameById(gameId);
        Card drawnCard = game.getDrawnCard();
        List<Card> discardPile = game.getDiscardPile();

        if (drawnCard != null) {
            boolean wasDrawnFromDeck = game.isDrawnFromDeck(); // save before reset
            String sourceZone = wasDrawnFromDeck ? MOVE_ZONE_DRAW_PILE : MOVE_ZONE_DISCARD_PILE;
            drawnCard.setVisibility(true);
            discardPile.add(drawnCard);
            game.setDrawnCard(null);
            game.setDrawnFromDeck(false); // reset flag
            setLastMoveEvent(
                    game,
                    game.getCurrentPlayerId(),
                    createMoveStep(
                            sourceZone,
                            null,
                            null,
                            MOVE_ZONE_DISCARD_PILE,
                            null,
                            null,
                            true,
                            drawnCard.getValue()
                    ),
                    null
            );

        if (eventPublisher != null) {
            String sessionId = null;
            if (lobbyService != null) {
                sessionId = lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds());
            }
            PlayerActionEvent actionEvent = new PlayerActionEvent();
            actionEvent.setSessionId(sessionId);
            actionEvent.setUserId(game.getCurrentPlayerId());
            actionEvent.setActionType("DISCARD");
            actionEvent.setDetails("Player discarded a card");
            eventPublisher.publishEvent(actionEvent);
        }

            // only trigger ability if card came from draw pile
            if (wasDrawnFromDeck) {
                triggerAbilityIfApplicable(game, drawnCard);
            } else {
                saveGameAndBroadcast(game);
                advanceTurnToNextPlayer(gameId);
            }
        }
    } 

    // swap drawn card with one of the player's hand cards
    public void moveSwapDrawnCard(String gameId, String token, int targetCardIndex) {
        // verify it's the player's turn
        verifyMoveCallerIsCurrentPlayer(gameId, token);

        Game game = getGameById(gameId);
        Card drawnCard = game.getDrawnCard();

        // check there is actually a drawn card
        if (drawnCard == null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "No drawn card available for swapping");
        }

        // get the current player's hand
        Long currentPlayerId = game.getCurrentPlayerId();
        List<Card> playerHand = game.getPlayerHands().get(currentPlayerId);

        // validate the target index
        if (targetCardIndex < 0 || targetCardIndex >= playerHand.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid card index");
        }

        // remove the card at the target index from the hand
        Card replacedCard = playerHand.remove(targetCardIndex);
        boolean drawnFromDeck = game.isDrawnFromDeck();

        // put the drawn card in its place, face-down
        drawnCard.setVisibility(false);
        playerHand.add(targetCardIndex, drawnCard);

        // put the replaced card face-up on the discard pile
        replacedCard.setVisibility(true);
        game.getDiscardPile().add(replacedCard);

        // clear the drawn card and advance turn
        game.setDrawnCard(null);
        game.setDrawnFromDeck(false); // reset flag after swap
        setLastMoveEvent(
                game,
                currentPlayerId,
                createMoveStep(
                        drawnFromDeck ? MOVE_ZONE_DRAW_PILE : MOVE_ZONE_DISCARD_PILE,
                        null,
                        null,
                        MOVE_ZONE_HAND,
                        currentPlayerId,
                        targetCardIndex,
                        drawnFromDeck,
                        drawnCard.getValue()
                ),
                createMoveStep(
                        MOVE_ZONE_HAND,
                        currentPlayerId,
                        targetCardIndex,
                        MOVE_ZONE_DISCARD_PILE,
                        null,
                        null,
                        true,
                        replacedCard.getValue()
                )
        );

        if (eventPublisher != null) {
            String sessionId = null;
            if (lobbyService != null) {
                sessionId = lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds());
            }
            PlayerActionEvent actionEvent = new PlayerActionEvent();
            actionEvent.setSessionId(sessionId);
            actionEvent.setUserId(game.getCurrentPlayerId());
            actionEvent.setActionType("SWAP");
            actionEvent.setDetails("Player swapped a drawn card into their hand");
            eventPublisher.publishEvent(actionEvent);
        }

        saveGameAndBroadcast(game);
        advanceTurnToNextPlayer(gameId);
    }

    // check card value and trigger ability phase if applicable
    private void triggerAbilityIfApplicable(Game game, Card discardedCard) {
        int value = discardedCard.getValue();
        if (value == 7 || value == 8) {
            // peek at own card
            game.setStatus(GameStatus.ABILITY_PEEK_SELF);
            saveGameAndBroadcast(game);
            // allow target selection with a full turn window;
            // after selection we switch to short reveal timer.
            startAbilityTimer(game.getId(), game.getTurnSeconds());
        } else if (value == 9 || value == 10) {
            // peek at opponent's card
            game.setStatus(GameStatus.ABILITY_PEEK_OPPONENT);
            saveGameAndBroadcast(game);
            // allow target selection with a full turn window;
            // after selection we switch to short reveal timer.
            startAbilityTimer(game.getId(), game.getTurnSeconds());
        } else if (value == 11 || value == 12) {
            // swap cards with opponent
            game.setStatus(GameStatus.ABILITY_SWAP);
            saveGameAndBroadcast(game);
            // Swap requires two clicks (own card + opponent card), so use dedicated swap timer,
            // not short peek/spy reveal duration.
            startAbilityTimer(game.getId(), game.getAbilitySwapSeconds());
        } else {
            // no ability — just advance turn normally
            saveGameAndBroadcast(game);
            advanceTurnToNextPlayer(game.getId());
        }
    }

    // auto-end ability phase after configured timeout if player doesn't act
    private void startAbilityTimer(String gameId) {
        Game game = getGameById(gameId);
        startAbilityTimer(gameId, game.getAbilityRevealSeconds());
    }

    private void startAbilityTimer(String gameId, long delaySeconds) {
        cancelTurnTimer(gameId);
        long scheduledCount = abilityTimerCounts
                .computeIfAbsent(gameId, ignored -> new AtomicLong(0)) // if there is no count for this game id, set to 0
                .incrementAndGet(); // increment by 1 and retrieve
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                Game game = getGameById(gameId);
                if (isCurrentAbilityTimerCount(gameId, scheduledCount)) {
                    completeAbilityPhaseAndAdvance(gameId, game, scheduledCount);
                }
            } catch (Exception e) {
                System.err.println("Ability timer failed for game " + gameId + ": " + e.getMessage());
            }
        }, delaySeconds, TimeUnit.SECONDS);
        gameTimers.put(gameId, future);
    }

    // True if scheduledCount is still the latest count for this game's ability timers
    private boolean isCurrentAbilityTimerCount(String gameId, long scheduledCount) {
        AtomicLong latest = abilityTimerCounts.get(gameId);
        return latest != null && latest.get() == scheduledCount;
    }

    private boolean isAbilityPhase(GameStatus status) {
        return status == GameStatus.ABILITY_PEEK_SELF
                || status == GameStatus.ABILITY_PEEK_OPPONENT
                || status == GameStatus.ABILITY_SWAP;
    }


     // expectedCountIfAny is non-null only from startAbilityTimer method, where it must match latest count
     // null from other paths (e.g. away from keyboard turn timeout) — no count check
    private boolean completeAbilityPhaseAndAdvance(String gameId, Game game, Long expectedCountIfAny) {
        GameStatus status = game.getStatus();
        if (!isAbilityPhase(status)) {
            return false;
        }
        if (expectedCountIfAny != null && !isCurrentAbilityTimerCount(gameId, expectedCountIfAny)) {
            return false;
        }

        if (status == GameStatus.ABILITY_PEEK_SELF || status == GameStatus.ABILITY_PEEK_OPPONENT) {
            clearAllHandVisibility(game);
        }

        game.setSpecialPeekUsed(false); // reset peek flag

        game.setStatus(GameStatus.ROUND_ACTIVE);
        cancelTurnTimer(gameId);
        saveGameAndBroadcast(game);
        advanceTurnToNextPlayer(gameId);
        return true;
    }

    // swap one card from current player's hand with a card from opponent's hand
    public void moveAbilitySwap(String gameId, String token, int ownCardIndex, Long targetUserId, int targetCardIndex) {
        // verify it's the player's turn
        verifyMoveCallerIsCurrentPlayer(gameId, token);

        Game game = getGameById(gameId);

        // verify game is in ability swap phase
        if (game.getStatus() != GameStatus.ABILITY_SWAP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Swap ability is not available");
        }

        // get both hands
        Long currentPlayerId = game.getCurrentPlayerId();
        List<Card> ownHand = game.getPlayerHands().get(currentPlayerId);
        List<Card> targetHand = game.getPlayerHands().get(targetUserId);

        // validate target player exists
        if (targetHand == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Target player not found");
        }

        // cannot swap with yourself
        if (currentPlayerId.equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot swap with yourself");
        }

        // validate indices
        if (ownCardIndex < 0 || ownCardIndex >= ownHand.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid card index");
        }
        if (targetCardIndex < 0 || targetCardIndex >= targetHand.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid target card index");
        }

        // swap the cards — neither card's visibility changes
        Card ownCard = ownHand.remove(ownCardIndex);
        Card targetCard = targetHand.remove(targetCardIndex);

        ownCard.setVisibility(false);
        targetCard.setVisibility(false);

        ownHand.add(ownCardIndex, targetCard);
        targetHand.add(targetCardIndex, ownCard);
        setLastMoveEvent(
                game,
                currentPlayerId,
                createMoveStep(
                        MOVE_ZONE_HAND,
                        currentPlayerId,
                        ownCardIndex,
                        MOVE_ZONE_HAND,
                        targetUserId,
                        targetCardIndex,
                        true,
                        ownCard.getValue()
                ),
                createMoveStep(
                        MOVE_ZONE_HAND,
                        targetUserId,
                        targetCardIndex,
                        MOVE_ZONE_HAND,
                        currentPlayerId,
                        ownCardIndex,
                        true,
                        targetCard.getValue()
                )
        );

        if (eventPublisher != null) {
            String sessionId = null;
            if(lobbyService != null) {
                sessionId = lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds());
            }
            PlayerActionEvent actionEvent = new PlayerActionEvent();
            actionEvent.setSessionId(sessionId);
            actionEvent.setUserId(game.getCurrentPlayerId());
            actionEvent.setActionType("ABILITY SWAP");
            actionEvent.setDetails("player swapped a card with another player");
            eventPublisher.publishEvent(actionEvent);
        }
        // end ability phase, go back to next player's turn
        game.setStatus(GameStatus.ROUND_ACTIVE);
        // cancel pending timer, player finished the ability manually
        cancelTurnTimer(gameId);
        saveGameAndBroadcast(game);
        advanceTurnToNextPlayer(gameId);
    }

    // swap top card of discard pile with one of the player's hand cards
    public void moveSwapWithDiscardPile(String gameId, String token, int targetCardIndex) {
    // guard 1: verify it's the player's turn
    verifyMoveCallerIsCurrentPlayer(gameId, token);

    Game game = getGameById(gameId);

    // guard 2: player must not have already drawn a card from the draw pile
    if (game.getDrawnCard() != null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot swap with discard pile after drawing a card");
    }

    // guard 3: discard pile must not be empty
    List<Card> discardPile = game.getDiscardPile();
    if (discardPile.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Discard pile is empty");
    }

    // get the player's hand
    Long currentPlayerId = game.getCurrentPlayerId();
    List<Card> playerHand = game.getPlayerHands().get(currentPlayerId);

    // validate the target index
    if (targetCardIndex < 0 || targetCardIndex >= playerHand.size()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid card index");
    }

    // take the top card from the discard pile
    Card topDiscardCard = discardPile.remove(discardPile.size() - 1);

    // remove the player's selected card from their hand
    Card replacedCard = playerHand.remove(targetCardIndex);

    // put the discard pile card into the player's hand face-down
    topDiscardCard.setVisibility(false);
    playerHand.add(targetCardIndex, topDiscardCard);

    // put the replaced card face-up on the discard pile
    replacedCard.setVisibility(true);
    discardPile.add(replacedCard);
    setLastMoveEvent(
            game,
            currentPlayerId,
            createMoveStep(
                    MOVE_ZONE_DISCARD_PILE,
                    null,
                    null,
                    MOVE_ZONE_HAND,
                    currentPlayerId,
                    targetCardIndex,
                    false,
                    topDiscardCard.getValue()
            ),
            createMoveStep(
                    MOVE_ZONE_HAND,
                    currentPlayerId,
                    targetCardIndex,
                    MOVE_ZONE_DISCARD_PILE,
                    null,
                    null,
                    true,
                    replacedCard.getValue()
            )
    );

    if (eventPublisher != null) {
        String sessionId = null;
        if (lobbyService != null) {
            sessionId = lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds());
        }
        PlayerActionEvent actionEvent = new PlayerActionEvent();
        actionEvent.setSessionId(sessionId);
        actionEvent.setUserId(game.getCurrentPlayerId());
        actionEvent.setActionType("SWAP");
        actionEvent.setDetails("player swapped a card with the discard pile");
        eventPublisher.publishEvent(actionEvent);
    }

    // advance turn
    saveGameAndBroadcast(game);
    advanceTurnToNextPlayer(gameId);
    }
    
    // handles callindgcabo - assumes that cabo is in itself a turn and no card can be drawn if a player wants to call cabo
    public void moveCallCabo(String gameId, String token) {
        verifyMoveCallerIsCurrentPlayer(gameId, token);
        Game game = getGameById(gameId);

        if (game.getStatus() != GameStatus.ROUND_ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot call Cabo right now");
        }

        if (game.isCaboCalled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cabo has already been called");
        }
        // a player can either call cabo OR draw a card
        if (game.getDrawnCard() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot call Cabo after drawing a card");
        }

        if (eventPublisher != null) {
            String sessionId = null;
            if (lobbyService != null) {
                sessionId = lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds());
            }
            PlayerActionEvent actionEvent = new PlayerActionEvent();
            actionEvent.setSessionId(sessionId);
            actionEvent.setUserId(game.getCurrentPlayerId());
            actionEvent.setActionType("CABO");
            actionEvent.setDetails("player called cabo");
            eventPublisher.publishEvent(actionEvent);
        }

        game.setCaboCalled(true);
        game.setCaboCalledByUserId(game.getCurrentPlayerId());
        game.setCaboForcedByTimeout(false);
        saveGameAndBroadcast(game);
        advanceTurnToNextPlayer(gameId); 
    }

    // Internal method for system-triggered Cabo (no token required)
    public void forceCallCabo(String gameId, Long userId) {
        Game game = getGameById(gameId);
    
        // Safety checks
        if (game.getStatus() == GameStatus.ROUND_ENDED
                || game.getStatus() == GameStatus.CABO_REVEAL
                || game.getStatus() == GameStatus.ROUND_AWAITING_REMATCH
                || game.isCaboCalled()) {
            return;
        }

        // If a forced Cabo happens during an ability step, normalize back to round flow first.
        if (game.getStatus() != GameStatus.ROUND_ACTIVE) {
            clearAllHandVisibility(game);
            game.setSpecialPeekUsed(false);
            game.setStatus(GameStatus.ROUND_ACTIVE);
        }

        game.setCaboCalled(true);
        game.setCaboCalledByUserId(userId);
        game.setCaboForcedByTimeout(true);
        saveGameAndBroadcast(game);
        advanceTurnToNextPlayer(gameId);
    }

    private void maybeCleanupRematchDecisionDedupCache(long nowMs) {
        long previousCleanupMs = lastRematchDecisionDedupCleanupMs.get();
        if (nowMs - previousCleanupMs < CACHE_CLEANUP_MIN_INTERVAL_MS) {
            return;
        }
        if (!lastRematchDecisionDedupCleanupMs.compareAndSet(previousCleanupMs, nowMs)) {
            return;
        }
        Iterator<Map.Entry<String, Long>> iterator = rematchDecisionDedupUntilByKey.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            Long expiresAtMs = entry.getValue();
            if (expiresAtMs == null || expiresAtMs <= nowMs) {
                iterator.remove();
            }
        }
    }

    private boolean shouldSkipDuplicateRematchDecision(String gameId, Long userId, String normalizedDecision) {
        if (gameId == null || gameId.isBlank() || userId == null || normalizedDecision == null || normalizedDecision.isBlank()) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        long dedupWindowMs = resolveRematchDecisionDedupWindowMs();
        String dedupKey = gameId + "|" + userId + "|" + normalizedDecision;
        Long dedupUntilMs = rematchDecisionDedupUntilByKey.get(dedupKey);
        if (dedupUntilMs != null && dedupUntilMs > nowMs) {
            return true;
        }
        rematchDecisionDedupUntilByKey.put(dedupKey, nowMs + dedupWindowMs);
        maybeCleanupRematchDecisionDedupCache(nowMs);
        return false;
    }

    public void submitRematchDecision(String gameId, String token, String decision) {
        User user = requireAuthenticatedUser(token);

        String normalizedDecision = String.valueOf(decision).trim().toUpperCase(Locale.ROOT);
        if (!REMATCH_DECISION_CONTINUE.equals(normalizedDecision)
                && !REMATCH_DECISION_FRESH.equals(normalizedDecision)
                && !REMATCH_DECISION_NONE.equals(normalizedDecision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be CONTINUE, FRESH, or NONE");
        }
        if (shouldSkipDuplicateRematchDecision(gameId, user.getId(), normalizedDecision)) {
            return;
        }

        synchronized (getRematchResolutionLock(gameId)) {
            Game game = getGameById(gameId);
            if (game.getStatus() != GameStatus.ROUND_AWAITING_REMATCH) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Round is not waiting for rematch decision");
            }
            List<Long> players = game.getOrderedPlayerIds();
            if (players == null || !players.contains(user.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a player in this game");
            }

            Map<Long, String> decisions = game.getRematchDecisionByUserId();
            if (decisions == null) {
                decisions = new HashMap<>();
                game.setRematchDecisionByUserId(decisions);
            }
            String currentDecision = decisions.get(user.getId());
            if (normalizedDecision.equalsIgnoreCase(String.valueOf(currentDecision))) {
                if (decisions.keySet().containsAll(players)) {
                    resolveRematchDecisionLocked(gameId, game, null);
                }
                return;
            }
            decisions.put(user.getId(), normalizedDecision);
            // first user with FRESH decision is saved as rematch requester
            if (REMATCH_DECISION_FRESH.equals(normalizedDecision) && game.getFreshRematchRequesterUserId() == null) {
                game.setFreshRematchRequesterUserId(user.getId());
            }
            saveGameAndBroadcast(game);

            if (decisions.keySet().containsAll(players)) {
                resolveRematchDecisionLocked(gameId, game, null);
            }
        }
    }

    public String completeRoundWithoutRematch(String gameId, String token) {
        submitRematchDecision(gameId, token, REMATCH_DECISION_NONE);
        return getPostRoundLobbySessionForToken(gameId, token);
    }

    @Transactional(readOnly = true)
    public String getPostRoundLobbySessionForToken(String gameId, String token) {
        User user = requireAuthenticatedUser(token);
        Game game = getGameById(gameId);
        if (game.getOrderedPlayerIds() == null || !game.getOrderedPlayerIds().contains(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a player in this game");
        }
        if (lobbyService == null) {
            return null;
        }
        return lobbyService.findWaitingSessionIdForPlayer(user.getId());
    }

    @Transactional(readOnly = true)
    public long getRematchDecisionSeconds(String gameId, String token) {
        User user = requireAuthenticatedUser(token);
        Game game = getGameById(gameId);
        if (game.getOrderedPlayerIds() == null || !game.getOrderedPlayerIds().contains(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a player in this game");
        }
        return game.getRematchDecisionSeconds();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getGameRuntimeConfig(String gameId, String token) {
        User user = requireAuthenticatedUser(token);
        Game game = getGameById(gameId);
        if (game.getOrderedPlayerIds() == null || !game.getOrderedPlayerIds().contains(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a player in this game");
        }
        return Map.of(
                "turnSeconds", game.getTurnSeconds(),
                "initialPeekSeconds", game.getInitialPeekSeconds(),
                "abilityRevealSeconds", game.getAbilityRevealSeconds(),
                "abilitySwapSeconds", game.getAbilitySwapSeconds(),
                "caboRevealSeconds", game.getCaboRevealSeconds(),
                "afkTimeoutSeconds", game.getAfkTimeoutSeconds(),
                "rematchDecisionSeconds", game.getRematchDecisionSeconds()
        );
    }

    private void resolveRematchDecision(String gameId, Game game, Long expectedCountIfAny) {
        synchronized (getRematchResolutionLock(gameId)) {
            resolveRematchDecisionLocked(gameId, game, expectedCountIfAny);
        }
    }

    private void resolveRematchDecisionLocked(String gameId, Game game, Long expectedCountIfAny) {
        if (game.getStatus() != GameStatus.ROUND_AWAITING_REMATCH) {
            return;
        }
        if (expectedCountIfAny != null && !isCurrentRematchDecisionTimerCount(gameId, expectedCountIfAny)) {
            return;
        }

        List<Long> orderedPlayers = game.getOrderedPlayerIds() == null
                ? List.of()
                : new ArrayList<>(game.getOrderedPlayerIds());
        Map<Long, String> decisions = game.getRematchDecisionByUserId();
        List<Long> continuePlayers = orderedPlayers.stream()
                .filter(playerId -> decisions != null && REMATCH_DECISION_CONTINUE.equalsIgnoreCase(decisions.get(playerId)))
                .toList();
        List<Long> freshPlayers = orderedPlayers.stream()
                .filter(playerId -> decisions != null && REMATCH_DECISION_FRESH.equalsIgnoreCase(decisions.get(playerId)))
                .toList();

        Long freshRematchRequesterUserId = game.getFreshRematchRequesterUserId();

        applyHundredToFiftyReductionIfNeeded(orderedPlayers);

        cancelTurnTimer(gameId);
        game.setStatus(GameStatus.ROUND_ENDED);
        game.setCaboCalled(false);
        game.setCaboCalledByUserId(null);
        game.setCaboForcedByTimeout(false);
        game.setRematchDecisionByUserId(new HashMap<>());
        // reset to null for further gameplay
        game.setFreshRematchRequesterUserId(null);
        saveGameAndBroadcast(game);

        if (lobbyService != null) {
            lobbyService.handleRoundResolvedForGamePlayers(
                    orderedPlayers, continuePlayers, freshPlayers, freshRematchRequesterUserId);
        }
        rematchDecisionTimerCounts.remove(gameId);
        rematchResolutionLocks.remove(gameId);
    }

    // #91: if a player's session score is 100, reduce to 50 once
    private void applyHundredToFiftyReductionIfNeeded(List<Long> orderedPlayers) {
        if (orderedPlayers == null || orderedPlayers.isEmpty() || lobbyService == null || sessionRepository == null) {
            return;
        }
        String sessionId = lobbyService.findPlayingSessionIdForPlayers(orderedPlayers);
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Session session = sessionRepository.findBySessionId(sessionId);
        if (session == null) {
            return;
        }
        List<Map<Long, Integer>> perRound = session.getUserScoresPerRound();
        if (perRound == null) {
            perRound = new ArrayList<>();
            session.setUserScoresPerRound(perRound);
        }
        Map<Long, Integer> totalScoreByUserId = session.getTotalScoreByUserId();
        if (totalScoreByUserId == null) {
            totalScoreByUserId = new HashMap<>();
            session.setTotalScoreByUserId(totalScoreByUserId);
        }
        Map<Long, Boolean> alreadyApplied = session.getHundredReductionAppliedByUserId();
        if (alreadyApplied == null) {
            alreadyApplied = new HashMap<>();
            session.setHundredReductionAppliedByUserId(alreadyApplied);
        }

        boolean changed = false;
        for (Long playerId : orderedPlayers) {
            if (playerId == null || Boolean.TRUE.equals(alreadyApplied.get(playerId))) {
                continue;
            }
            Integer totalScoreObj = totalScoreByUserId.get(playerId);
            if (totalScoreObj == null) {
                int recomputedTotal = 0;
                for (Map<Long, Integer> roundScores : perRound) {
                    if (roundScores != null) {
                        recomputedTotal += roundScores.getOrDefault(playerId, 0);
                    }
                }
                totalScoreByUserId.put(playerId, recomputedTotal);
                totalScoreObj = recomputedTotal;
                changed = true;
            }
            int totalScore = totalScoreObj;
            if (totalScore == 100) {
                totalScoreByUserId.put(playerId, 50);
                alreadyApplied.put(playerId, true);
                changed = true;
            }
        }
        if (changed) {
            session.setUserScoresPerRound(perRound);
            session.setTotalScoreByUserId(totalScoreByUserId);
            session.setHundredReductionAppliedByUserId(alreadyApplied);
            sessionRepository.save(session);
        }
    }

    private boolean isActiveGame(Game game) {
        return game != null && game.getStatus() != GameStatus.ROUND_ENDED;
    }

    private Set<Long> toPlayerSet(List<Long> playerIds) {
        Set<Long> sanitized = new LinkedHashSet<>();
        if (playerIds == null) {
            return sanitized;
        }
        for (Long playerId : playerIds) {
            if (playerId != null) {
                sanitized.add(playerId);
            }
        }
        return sanitized;
    }

    private Optional<Game> findActiveGameMatchingLobbyPlayers(List<Long> lobbyPlayerIds) {
        Set<Long> expectedPlayerIds = toPlayerSet(lobbyPlayerIds);
        if (expectedPlayerIds.isEmpty()) {
            return Optional.empty();
        }

        Long anchorPlayerId = expectedPlayerIds.iterator().next();
        List<Game> candidates = gameRepository.findGamesByPlayerId(anchorPlayerId);
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        Game bestOverlapGame = null;
        int bestOverlapCount = 0;
        for (Game candidate : candidates) {
            if (!isActiveGame(candidate)) {
                continue;
            }
            List<Long> orderedPlayerIds = candidate.getOrderedPlayerIds();
            if (orderedPlayerIds == null || !orderedPlayerIds.contains(anchorPlayerId)) {
                continue;
            }
            Set<Long> candidatePlayerIds = toPlayerSet(orderedPlayerIds);
            if (candidatePlayerIds.equals(expectedPlayerIds)) {
                return Optional.of(candidate);
            }

            int overlapCount = 0;
            for (Long candidatePlayerId : candidatePlayerIds) {
                if (expectedPlayerIds.contains(candidatePlayerId)) {
                    overlapCount++;
                }
            }
            if (overlapCount > bestOverlapCount) {
                bestOverlapCount = overlapCount;
                bestOverlapGame = candidate;
            }
        }
        return Optional.ofNullable(bestOverlapGame);
    }

    private Optional<Game> findActiveGameForSpectator(Long userId) {
        if (userId == null || lobbyService == null) {
            return Optional.empty();
        }
        Optional<Lobby> playingLobbyForSpectator = lobbyService.findLatestPlayingLobbyForSpectator(userId);
        if (playingLobbyForSpectator.isEmpty()) {
            return Optional.empty();
        }
        return findActiveGameMatchingLobbyPlayers(playingLobbyForSpectator.get().getPlayerIds());
    }

    public Optional<Game> findActiveGameForUser(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }

        return gameRepository.findGamesByPlayerId(userId).stream()
                .filter(this::isActiveGame)
                .filter(game -> game.getOrderedPlayerIds() != null && game.getOrderedPlayerIds().contains(userId))
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Game> getActiveGameForToken(String token) {
        User user = requireAuthenticatedUser(token);
        return findActiveGameForUser(user.getId())
                .or(() -> findActiveGameForSpectator(user.getId()));
    }

    // Convenience method for callers that only know the user (e.g., lobby kicks/disconnect handling)
    public void forceCallCaboForUser(Long userId) {
        findActiveGameForUser(userId).ifPresent(game -> forceCallCabo(game.getId(), userId));
    }


    private void invalidateSyncStateCacheForGame(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return;
        }
        String keyPrefix = gameId.trim() + "|";
        syncStateCacheByViewerAndGame.keySet().removeIf(key -> key != null && key.startsWith(keyPrefix));
    }

    // save in db and send filtered representations to all players 
    private Game saveGameAndBroadcast(Game game) {
        Game saved = gameRepository.save(game);
        if (saved == null) {
            saved = game;
        }
        invalidateSyncStateCacheForGame(saved.getId());
        gameEventPublisher.publishFilteredState(saved);
        return saved;
    }

    private long resolveAbsentRoundPointsForLobby(Lobby lobbyConfig) {
        if (lobbyConfig == null) {
            return 0L;
        }
        Long configured = lobbyConfig.getAbsentRoundPoints();
        if (configured == null) {
            return 0L;
        }
        return Math.max(0L, configured);
    }

    private void ensureSessionExistsForLobbyIfNeeded(Lobby lobbyConfig, List<Long> playerIds) {
        if (lobbyConfig == null || sessionRepository == null) {
            return;
        }
        String sessionId = lobbyConfig.getSessionId();
        if (sessionId != null) {
            sessionId = sessionId.trim();
        }
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        Session existing = sessionRepository.findBySessionId(sessionId);
        if (existing != null) {
            boolean changed = false;
            if (existing.getAbsentRoundPoints() == null) {
                existing.setAbsentRoundPoints(resolveAbsentRoundPointsForLobby(lobbyConfig));
                changed = true;
            }

            List<Map<Long, Integer>> perRoundScores = existing.getUserScoresPerRound();
            if (perRoundScores == null) {
                perRoundScores = new ArrayList<>();
                existing.setUserScoresPerRound(perRoundScores);
                changed = true;
            }

            Map<Long, Integer> totalScores = existing.getTotalScoreByUserId();
            if (totalScores == null) {
                totalScores = new HashMap<>();
                existing.setTotalScoreByUserId(totalScores);
                changed = true;
            }

            long absentRoundPoints = resolveAbsentRoundPointsForSession(existing);
            if (playerIds != null) {
                for (Long playerId : playerIds) {
                    if (playerId == null) {
                        continue;
                    }
                    boolean alreadyPresent = totalScores.containsKey(playerId);
                    backfillPlayerForPreviousRounds(playerId, perRoundScores, totalScores, absentRoundPoints);
                    if (!alreadyPresent && totalScores.containsKey(playerId)) {
                        changed = true;
                    }
                }
            }

            if (changed) {
                sessionRepository.save(existing);
            }
            return;
        }

        Session session = new Session();
        session.setSessionId(sessionId);
        session.setStartTime(Instant.now());
        session.setEnded(false);
        session.setAbsentRoundPoints(resolveAbsentRoundPointsForLobby(lobbyConfig));

        Map<Long, Integer> totals = new HashMap<>();
        if (playerIds != null) {
            for (Long playerId : playerIds) {
                if (playerId != null) {
                    totals.put(playerId, 0);
                }
            }
        }
        session.setTotalScoreByUserId(totals);
        sessionRepository.save(session);
    }

    private long resolveAbsentRoundPointsForSession(Session session) {
        if (session == null) {
            return 0L;
        }
        Long configured = session.getAbsentRoundPoints();
        if (configured == null) {
            return 0L;
        }
        return Math.max(0L, configured);
    }

    private long resolveAbsentRoundPointsForPlayingLobby(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || lobbyService == null) {
            return 0L;
        }
        try {
            Lobby lobby = lobbyService.getLobbyBySessionId(sessionId);
            return resolveAbsentRoundPointsForLobby(lobby);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void backfillPlayerForPreviousRounds(
            Long playerId,
            List<Map<Long, Integer>> perRoundScores,
            Map<Long, Integer> totalScores,
            long absentRoundPoints) {
        if (playerId == null || totalScores.containsKey(playerId)) {
            return;
        }
        int backfilledTotal = 0;
        int absentPoints = (int) absentRoundPoints;
        for (Map<Long, Integer> roundScores : perRoundScores) {
            if (roundScores == null) {
                continue;
            }
            Integer existing = roundScores.get(playerId);
            if (existing == null) {
                roundScores.put(playerId, absentPoints);
                backfilledTotal += absentPoints;
            } else {
                backfilledTotal += existing;
            }
        }
        totalScores.put(playerId, backfilledTotal);
    }

    // this is used to automatically end a players turn by drawing and instantly discarding a card 
    // if they are AFK
    public void executeTimoutMove(String gameId, Long userId) {
        Game game = getGameById(gameId);
        GameStatus status = game.getStatus();
        Card cardToDiscard = game.getDrawnCard();
        List<Card> discardPile = game.getDiscardPile();
        
        if (!userId.equals(game.getCurrentPlayerId())) {
            return;
        }

        if(status == GameStatus.ROUND_ACTIVE) {

            // make sure a card is drawn - if not, draw one from the draw pile (triggering reshuffle if necessary)
            if (cardToDiscard == null) {
                if (game.getDrawPile().isEmpty()) {
                    reshuffleDiscardPile(gameId);
                    game = getGameById(gameId);
                }
                cardToDiscard = game.getDrawPile().remove(0);
            }
            // discard the card (it is visible since it is being discarded)
            cardToDiscard.setVisibility(true);
            discardPile.add(cardToDiscard);
            game.setDrawnCard(null);
            game.setDrawnFromDeck(false);
        }

        if (isAbilityPhase(status)) {
            // null: no ability timer count to validate (this path is from the turn timer, not startAbilityTimer)
            completeAbilityPhaseAndAdvance(gameId, game, null);
            return;
        }
        // save changes and advance turn
        saveGameAndBroadcast(game);
        advanceTurnToNextPlayer(gameId);
    }

    // pass the turn to the next player
    public void advanceTurnToNextPlayer(String gameId) {
        Game game = getGameById(gameId);
        List<Long> players = game.getOrderedPlayerIds();
        Long currentPlayerId = game.getCurrentPlayerId();

        int currentIndex = players.indexOf(currentPlayerId);
        int nextIndex = (currentIndex+1)%players.size();
        Long nextPlayerId = players.get(nextIndex);

        // If a player is officially timed out/disconnected midgame, auto-call Cabo
        // when their turn starts, while keeping them in the game roster.
        if (!game.isCaboCalled() && isTimedOutPlayer(nextPlayerId)) {
            game.setCurrentPlayerId(nextPlayerId);
            game.setCaboCalled(true);
            game.setCaboCalledByUserId(nextPlayerId);
            game.setCaboForcedByTimeout(true);
            saveGameAndBroadcast(game);
            advanceTurnToNextPlayer(gameId);
            return;
        }

        // makes sure the game only advances one round after cabo is called
        if (game.isCaboCalled() && nextPlayerId.equals(game.getCaboCalledByUserId())) {

            // Calculate round scores with special rule
            Map<Long, Integer> roundScores = calculatedRoundScores(game);

            boolean isSessionOver = saveRoundScoreAndCheckGameOver(gameId, roundScores);
            // Keep session history immediately consistent for cabo_reveal consumers.
            applyHundredToFiftyReductionIfNeeded(players);

            if (isSessionOver) {
                // didnt know what we want to do if game is over...
                System.out.println("Session has reached its limit and is now over!");
            }

            game.setCaboRevealSeconds(resolveCaboRevealSecondsForPlayerCount(players));
            game.setStatus(GameStatus.CABO_REVEAL);
            revealAllInPlayCardsForRoundEnd(game);
            game.setRematchDecisionByUserId(new HashMap<>());
            // reset to null for further gameplay
            game.setFreshRematchRequesterUserId(null);
            cancelTurnTimer(gameId);
            saveGameAndBroadcast(game);
            startRoundRevealTimer(gameId);
            return;
        }

        game.setCurrentPlayerId(nextPlayerId);
        startTurnTimer(gameId, game.getCurrentPlayerId());
        saveGameAndBroadcast(game);
    }

    private boolean isTimedOutPlayer(Long playerId) {
        if (playerId == null || lobbyService == null) {
            return false;
        }
        if (!lobbyService.isPlayerTimedOutInPlaying(playerId)) {
            return false;
        }
        // Self-heal stale timeout flags to avoid false auto-Cabo.
        if (hasFreshHeartbeat(playerId, 45)) {
            lobbyService.clearTimedOutPlayingFlag(playerId);
            return false;
        }
        return true;
    }

    private boolean hasFreshHeartbeat(Long userId, long freshnessWindowSeconds) {
        if (userId == null || freshnessWindowSeconds <= 0) {
            return false;
        }
        return userRepository.findById(userId)
                .map(User::getLastHeartbeat)
                .filter(last -> last != null)
                .map(last -> last.isAfter(Instant.now().minusSeconds(freshnessWindowSeconds)))
                .orElse(false);
    }

    private void startIntroTimer(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return;
        }
        cancelTurnTimer(gameId);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                enterInitialPeekPhase(gameId);
            } catch (Exception e) {
                System.err.println("Intro phase timer failed for game " + gameId + ": " + e.getMessage());
            }
        }, INTRO_PHASE_SECONDS, TimeUnit.SECONDS);
        gameTimers.put(gameId, future);
    }

    private void enterInitialPeekPhase(String gameId) {
        Game game = getGameById(gameId);
        if (game.getStatus() != GameStatus.INTRO) {
            return;
        }
        game.setStatus(GameStatus.INITIAL_PEEK);
        saveGameAndBroadcast(game);
        startPeekingTimer(gameId, game.getInitialPeekSeconds());
    }

    // start new turn alarm for specified player
    private void startTurnTimer(String gameId, Long playerId) {
        if (gameId == null || gameId.isBlank() || playerId == null) {
            return;
        }
        Game game = getGameById(gameId);
        // always cancel running timers first
        cancelTurnTimer(gameId);
        // tell the alarm what to run and when to run it
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                // this runs when the 30 sec are over
                executeTimoutMove(gameId, playerId);
            } catch (Exception e) {
                // catch errors such that bug doesnt permanently crash timer
                System.err.println("Timeout execution failed for game " + gameId + ": " + e.getMessage());
            }
        }, game.getTurnSeconds(), TimeUnit.SECONDS);
        // save it to our tasks
        gameTimers.put(gameId, future);
    }

    private void startRematchDecisionTimer(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return;
        }

        cancelTurnTimer(gameId);
        Game gameAtScheduling = getGameById(gameId);
        long scheduledCount = rematchDecisionTimerCounts
                .computeIfAbsent(gameId, ignored -> new AtomicLong(0))
                .incrementAndGet();

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                Game game = getGameById(gameId);
                if (isCurrentRematchDecisionTimerCount(gameId, scheduledCount)) {
                    resolveRematchDecision(gameId, game, scheduledCount);
                }
            } catch (Exception e) {
                System.err.println("Rematch decision timer failed for game " + gameId + ": " + e.getMessage());
            }
        }, gameAtScheduling.getRematchDecisionSeconds(), TimeUnit.SECONDS);

        gameTimers.put(gameId, future);
    }

    private void startRoundRevealTimer(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return;
        }

        cancelTurnTimer(gameId);
        Game gameAtScheduling = getGameById(gameId);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                Game game = getGameById(gameId);
                if (game.getStatus() != GameStatus.CABO_REVEAL) {
                    return;
                }
                enterRoundAwaitingRematchPhase(gameId, game);
            } catch (Exception e) {
                System.err.println("Round reveal timer failed for game " + gameId + ": " + e.getMessage());
            }
        }, gameAtScheduling.getCaboRevealSeconds(), TimeUnit.SECONDS);
        gameTimers.put(gameId, future);
    }

    private void enterRoundAwaitingRematchPhase(String gameId, Game game) {
        if (game.getStatus() == GameStatus.ROUND_AWAITING_REMATCH) {
            return;
        }
        if (game.getStatus() != GameStatus.CABO_REVEAL) {
            return;
        }

        game.setStatus(GameStatus.ROUND_AWAITING_REMATCH);
        game.setRematchDecisionByUserId(new HashMap<>());
        // reset to null for further gameplay
        game.setFreshRematchRequesterUserId(null);
        saveGameAndBroadcast(game);
        startRematchDecisionTimer(gameId);
    }

    private boolean isCurrentRematchDecisionTimerCount(String gameId, long scheduledCount) {
        AtomicLong latest = rematchDecisionTimerCounts.get(gameId);
        return latest != null && latest.get() == scheduledCount;
    }

    // cancels current alarm if player makes a move
    private void cancelTurnTimer(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return;
        }
        ScheduledFuture<?> future = gameTimers.get(gameId);
        if (future != null) {
            // cancel timer
            future.cancel(false);
            gameTimers.remove(gameId);
        }
    }

    private void startPeekingTimer(String gameId) {
        startPeekingTimer(gameId, getGameById(gameId).getInitialPeekSeconds());
    }

    private void startPeekingTimer(String gameId, long delaySeconds) {
        if (gameId == null || gameId.isBlank()) {
            return;
        }
        // make sure no other timer runs
        cancelTurnTimer(gameId);
        // start timer that allows players to do intial peek
        ScheduledFuture<?> future = scheduler.schedule( () -> {
            endPeekingTimer(gameId);
        }, delaySeconds, TimeUnit.SECONDS);
        gameTimers.put(gameId, future);
    }

    private void endPeekingTimer(String gameId) {
        Game game = getGameById(gameId);
        List<Integer> randomIndices = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        Map<Long, Boolean> performedInitialPeek = game.getInitialPeekDoneByUserId();
        List<Long> players = game.getOrderedPlayerIds();

        if (performedInitialPeek == null) {
            performedInitialPeek = new HashMap<>();
            game.setInitialPeekDoneByUserId(performedInitialPeek);
        }

        // iterate through players to make sure all of them made initial peek
        for (Long id : players) {
            if (!Boolean.TRUE.equals(performedInitialPeek.get(id))) {
                // if a player didnt do their initial peek select two random cards and reveal them
                Collections.shuffle(randomIndices);
                // select the two random cards
                int firstIndex = randomIndices.get(0);
                int secondIndex = randomIndices.get(1);
                List<Card> hand = game.getPlayerHands().get(id);
                // reveal them
                hand.get(firstIndex).setVisibility(true);
                hand.get(secondIndex).setVisibility(true);
                // state that the players did their initial peek
                performedInitialPeek.put(id, true);
            }
        }

        // Bevor die Runde startet, alle Karten für alle Spieler wieder auf unsichtbar setzen
        clearAllHandVisibility(game);

        // randomly select who starts with the first move
        int randomStarterIndex = new java.util.Random().nextInt(players.size());
        Long starterId = players.get(randomStarterIndex);
        // set that player as the first one to move 
        game.setCurrentPlayerId(starterId);
        // set game status
        game.setStatus(GameStatus.ROUND_ACTIVE);
        // broadcast changes
        saveGameAndBroadcast(game);
        // initialize timer for turns
        startTurnTimer(gameId, starterId);

    }
    // #20 drawn card only reveals value to the right player
    @Transactional(readOnly = true)
    public Card getDrawnCard(String gameId, String token) {
        User user = requireAuthenticatedUser(token);

        Game game = getGameById(gameId);
        List<Long> players = game.getOrderedPlayerIds();
        boolean participant = players != null && players.contains(user.getId());

        if (allInPlayCardsReveal(game.getStatus()) && participant) {
            return game.getDrawnCard();
        }

        if (!user.getId().equals(game.getCurrentPlayerId())) {
            return null;
        }

        return game.getDrawnCard();
    }

    public void skipAbility(String gameId, String token) {
        verifyMoveCallerIsCurrentPlayer(gameId, token);
        Game game = getGameById(gameId);
        GameStatus status = game.getStatus();
        if (status != GameStatus.ABILITY_PEEK_SELF && status != GameStatus.ABILITY_PEEK_OPPONENT && status != GameStatus.ABILITY_SWAP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No ability to skip");
        }
        if (status == GameStatus.ABILITY_PEEK_SELF || status == GameStatus.ABILITY_PEEK_OPPONENT) {
            clearAllHandVisibility(game);
        }

        game.setSpecialPeekUsed(false); // reset peek flag
        
        game.setStatus(GameStatus.ROUND_ACTIVE);
        // cancel pending timer, player finished the ability manually
        cancelTurnTimer(gameId);
        saveGameAndBroadcast(game);
        advanceTurnToNextPlayer(gameId);
    }

    /**
     * Calculate round scores including Kamikaze special rule. 
     * Scoring rules:
     * 1. Kamikaze: If a player has exactly 2×12 and 2×13, they get 0 points and all others get 50
     * 2. Normal: Player(s) with lowest sum get 0 points, others get their card sum
     * 3. Cabo penalty: If Cabo caller doesn't have lowest sum, they get +5 penalty points
     * 
     * @param game The game instance at round end
     * @return Map of userId -> round score for each player
     */
    private Map<Long, Integer> calculatedRoundScores(Game game) {
        Map<Long, Integer> roundScores = new HashMap<>();
        Map<Long, List<Card>> playerHands = game.getPlayerHands();
    
        if (playerHands == null || playerHands.isEmpty()) {
            return roundScores;
        }
    
        List<Long> players = game.getOrderedPlayerIds();
        if (players == null) {
            return roundScores;
        }
    
        // First check: Does anyone have the Kamikaze combination (2×12 and 2×13)?
        Set<Long> kamikazePlayers = new LinkedHashSet<>();
    
        for (Long playerId : players) {
            List<Card> hand = playerHands.get(playerId);
            if (hand != null && hasKamikazeCombination(hand)) {
                kamikazePlayers.add(playerId);
            }
        }
    
        // If Kamikaze rule applies: all Kamikaze holders get 0, everyone else gets 50
        if (!kamikazePlayers.isEmpty()) {
            for (Long playerId : players) {
                if (kamikazePlayers.contains(playerId)) {
                    roundScores.put(playerId, 0);
                } else {
                    roundScores.put(playerId, 50);
                }
            }
            return roundScores;
        }
    
        // Normal scoring: calculate hand values for all players
        Map<Long, Integer> handValues = new HashMap<>();
        int minValue = Integer.MAX_VALUE;
    
        for (Long playerId : players) {
            List<Card> hand = playerHands.get(playerId);
            int handValue = calculateHandValue(hand);
            handValues.put(playerId, handValue);
            minValue = Math.min(minValue, handValue);
        }
    
        // Find all players with the minimum hand value
        List<Long> playersWithMinValue = new ArrayList<>();
        for (Long playerId : players) {
            if (handValues.get(playerId) == minValue) {
                playersWithMinValue.add(playerId);
            }
        }
    
        Long caboCallerId = game.getCaboCalledByUserId();
        boolean caboCallerHasMinValue = caboCallerId != null && playersWithMinValue.contains(caboCallerId);
    
        // Assign scores based on rules
        for (Long playerId : players) {
            int handValue = handValues.get(playerId);
        
            if (handValue == minValue) {
                // Player has minimum value
                if (playersWithMinValue.size() == 1) {
                    // Clear winner: 0 points
                    roundScores.put(playerId, 0);
                } else {
                    // Tie for minimum
                    if (caboCallerHasMinValue) {
                        // Cabo caller is part of the tie -> only they get 0
                        if (playerId.equals(caboCallerId)) {
                            roundScores.put(playerId, 0);
                        } else {
                            roundScores.put(playerId, handValue);
                        }
                    } else {
                        // Cabo caller not in tie -> all tied players get 0
                        roundScores.put(playerId, 0);
                    }
                }
            } else {
                // Player does not have minimum value
                int score = handValue;
            
                // Apply Cabo penalty if applicable
                if (playerId.equals(caboCallerId)) {
                    score += 5; // Cabo caller penalty
                }
            
                roundScores.put(playerId, score);
            }
        }
    
        return roundScores;
    }

    /**
    * Check if a hand contains exactly 2×12 and 2×13 (Kamikaze rule).
    * 
    * @param hand The player's hand
    * @return true if hand has exactly two 12s and two 13s
    */
    private boolean hasKamikazeCombination(List<Card> hand) {
        if (hand == null || hand.size() != 4) {
            return false;
        }
    
        int count12 = 0;
        int count13 = 0;
    
        for (Card card : hand) {
            if (card == null) {
                continue;
            }
            int value = card.getValue();
            if (value == 12) {
                count12++;
            } else if (value == 13) {
                count13++;
            }
        }
    
        return count12 == 2 && count13 == 2;
    }

    /**
    * Calculate the sum of card values in a hand (normal scoring).
    * 
    * @param hand The player's hand
    * @return Sum of all card values
    */
    private int calculateHandValue(List<Card> hand) {
        if (hand == null) {
            return 0;
        }   
    
        int sum = 0;
        for (Card card : hand) {
            if (card != null) {
                sum += card.getValue();
            }
        }
        return sum;
    }

    /**
     * Pipeline method to persist round scores, update session totals, 
     * and check for game-over conditions.
     * 
     * @param gameId The ID of the current game.
     * @param calculatedRoundScores The scores for this round.
     * @return boolean True if the session is over, False if another round should start.
     */
    public boolean saveRoundScoreAndCheckGameOver(String gameId, Map<Long, Integer> calculatedRoundScores) {
        try {
            // retrieve session 
            Game game = getGameById(gameId);
            List<Long> orderedPlayers = game.getOrderedPlayerIds();

            String sessionId = lobbyService.findPlayingSessionIdForPlayers(orderedPlayers);

            if (sessionId == null || sessionId.isBlank()) {
                System.err.println("Warning: No active session found for game. Skipping score save.");
                return false;
            }
            
            Session session = sessionRepository.findBySessionId(sessionId);

            if (session == null) {
                System.err.println("Warning: Session entity not found in DB. Skipping score save.");
                return false;
            }

            if (session.getAbsentRoundPoints() == null) {
                session.setAbsentRoundPoints(resolveAbsentRoundPointsForPlayingLobby(sessionId));
            }

            // update session fields
            List<Map<Long, Integer>> perRoundScores = session.getUserScoresPerRound();
            if (perRoundScores == null) {
                perRoundScores = new ArrayList<>();
            }

            Map<Long, Integer> totalScores = session.getTotalScoreByUserId();
            if (totalScores == null) {
                totalScores = new HashMap<>();
            }

            long absentRoundPoints = resolveAbsentRoundPointsForSession(session);
            int absentPoints = (int) absentRoundPoints;

            Map<Long, Integer> normalizedRoundScores = new HashMap<>();
            if (calculatedRoundScores != null) {
                normalizedRoundScores.putAll(calculatedRoundScores);
            }

            // Late joiners: backfill all previous rounds with configurable absent points.
            for (Long playerId : new ArrayList<>(normalizedRoundScores.keySet())) {
                backfillPlayerForPreviousRounds(playerId, perRoundScores, totalScores, absentRoundPoints);
            }

            // Players already in session but not in this round receive absent points.
            for (Long participantId : new ArrayList<>(totalScores.keySet())) {
                normalizedRoundScores.putIfAbsent(participantId, absentPoints);
            }

            // Safety net: if a current game participant has no score entry, count as absent.
            if (orderedPlayers != null) {
                for (Long participantId : orderedPlayers) {
                    if (participantId != null) {
                        normalizedRoundScores.putIfAbsent(participantId, absentPoints);
                    }
                }
            }

            perRoundScores.add(normalizedRoundScores);
            session.setUserScoresPerRound(perRoundScores);

            for (Map.Entry<Long, Integer> entry: normalizedRoundScores.entrySet()) {
                Long playerId = entry.getKey();
                Integer roundScore = entry.getValue();
                if (playerId == null || roundScore == null) {
                    continue;
                }
                totalScores.merge(playerId, roundScore, Integer::sum);
            }

            session.setTotalScoreByUserId(totalScores);

            // check if ending conditions are met
            boolean isGameOver = checkGameOverConditions(session);

            if (isGameOver) {
                session.setEnded(true);
                updateUserStatisticsAtSessionEnd(session);
            }

            sessionRepository.save(session);

            return isGameOver;
        } catch (Exception e) {
            log.error("Critical error during scoring pipeline", e);
            return false;
        }
    }

    private boolean checkGameOverConditions(Session session) {
        try {
            int maxRounds = gameSettings.getRoundLimit();
            int maxScore = gameSettings.getScoreLimit();

            if (session.getUserScoresPerRound() != null && session.getUserScoresPerRound().size() >= maxRounds) {
                return true;
            }

            if (session.getTotalScoreByUserId() != null) {
                for (Integer totalScore : session.getTotalScoreByUserId().values()) {
                    if (totalScore != null && totalScore >= maxScore) {
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            log.error("Error checking game over conditions", e);
            return false;
        }
    }

    /**
        update statistics of all players after session is finishesd
    **/
    private void updateUserStatisticsAtSessionEnd(Session session) {
        try {
            Map<Long, Integer> totalScores = session.getTotalScoreByUserId();
            if (totalScores == null || totalScores.isEmpty()) {
                return;
            }

            // get all player ID's
            List<User> participants = userRepository.findAllById(totalScores.keySet());

            // find the winner
            int lowestScore = Integer.MAX_VALUE;
            for (Integer score : totalScores.values()) {
                if (score != null && score < lowestScore) {
                    lowestScore = score;
                }
            }

            // find all player with fewest points
            List<Long> winners = new ArrayList<>();
            for (Map.Entry<Long, Integer> entry : totalScores.entrySet()) {
                if (entry.getValue() != null && entry.getValue() == lowestScore) {
                    winners.add(entry.getKey());
                }
            }

            // save winners into session
            session.setWinnerIds(winners);

            // update statistics
            for (User user : participants) {
                Long userId = user.getId();
                
                // increase amount of games +1
                int currentGames = user.getGamesPlayed() != null ? user.getGamesPlayed() : 0;
                user.setGamesPlayed(currentGames + 1);

                // sum up amount of points
                int sessionPoints = totalScores.getOrDefault(userId, 0);
                int currentPointsAccumulated = user.getTotalPointsAccumulated() != null ? user.getTotalPointsAccumulated() : 0;
                user.setTotalPointsAccumulated(currentPointsAccumulated + sessionPoints);

                // update wins/losses
                if (winners.contains(userId)) {
                    int currentWins = user.getGamesWon() != null ? user.getGamesWon() : 0;
                    user.setGamesWon(currentWins + 1);
                } else {
                    int currentLosses = user.getGamesLost() != null ? user.getGamesLost() : 0;
                    user.setGamesLost(currentLosses + 1);
                }
            }

            // save results
            userRepository.saveAll(participants);

        } catch (Exception e) {
            log.error("Error updating user statistics at session end", e);
        }
    }
}
