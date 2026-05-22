package ch.uzh.ifi.hase.soprafs26.entity;

import jakarta.persistence.*;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;

import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;

@Entity
// create a table with name games in DB
@Table(name = "games", indexes = {
        @Index(name = "idx_games_status", columnList = "status"),
        @Index(name = "idx_games_status_round_ended_at", columnList = "status,round_ended_at")
})
public class Game {
    
    @Id
    // generate a random id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, unique = true)
    private String id;

    // deckofcards API id. null for fallback
    @Column(nullable = true)
    private String deckApiId;

    // the line below is used to save lists and maps to the DB
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, unique = false)
    // all fields are initialized as empty lists or maps to avoid null pointer errors
    private List<Card> drawPile = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, unique = false)
    private List<Card> discardPile = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, unique = false)
    private Map<Long, List<Card>> playerHands = new HashMap<>();

    // to keep a consistent order of players
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, unique = false)
    private List<Long> orderedPlayerIds = new ArrayList<>();

    // this always stores the card that was drawn last by any player
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = true, unique = false)
    private Card drawnCard;

    // game starts with a short intro before entering initial peek
    @Column(nullable = false)
    private GameStatus status = GameStatus.INTRO;

    // Per user: true after successful initial peek (no second initial peek)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, unique = false)
    private Map<Long, Boolean> initialPeekDoneByUserId = new HashMap<>();

    // tracks whether the current drawn card was drawn from the deck (not from hand)
    // needed to determine if special abilities can be triggered
    @Column(nullable = false)
    private boolean drawnFromDeck = false;

    // tracks whether Cabo has been called
    @Column
    private boolean isCaboCalled = false;

    // tracks who called Cabo
    @Column
    private Long caboCalledByUserId;

    // True when Cabo was auto-called due to timeout/disconnect (not manual button press).
    @Column(nullable = false)
    private boolean caboForcedByTimeout = false;

    // tracks if the current player already used their special peek ability
    @Column(nullable = false)
    private boolean specialPeekUsed = false;

    // Per-game runtime timer config (copied from lobby at game start).
    @Column(nullable = false)
    private long turnSeconds = 30L;

    @Column(nullable = false)
    private long initialPeekSeconds = 10L;

    @Column(nullable = false)
    private long abilityRevealSeconds = 5L;

    @Column(nullable = false)
    private long abilitySwapSeconds = 10L;

    @Column(nullable = false)
    private long caboRevealSeconds = 30L;

    @Column(nullable = false)
    private long rematchDecisionSeconds = 60L;

    @Column(nullable = false)
    private long afkTimeoutSeconds = 300L;

    // per-player rematch decision once round enters ROUND_AWAITING_REMATCH:
    // CONTINUE = rematch in same lobby, FRESH = rematch in new lobby code, NONE = no rematch
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, unique = false)
    private Map<Long, String> rematchDecisionByUserId = new HashMap<>();

    // first user who requested a FRESH rematch
    @Column(nullable = true)
    private Long freshRematchRequesterUserId;

    // Timestamp when this game entered ROUND_ENDED.
    // Used to prune stale finished games and keep active-game lookups fast.
    @Column(nullable = true)
    private Instant roundEndedAt;

    // Last deterministic move event for client-side animation.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = true, unique = false)
    private GameMoveEvent lastMoveEvent;

    @Column(nullable = false)
    private long lastMoveSequence = 0L;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    // do not send to client
    @JsonIgnore
    public String getDeckApiId() {
        return deckApiId;
    }

    public void setDeckApiId(String deckApiId) {
        this.deckApiId = deckApiId;
    }

    public List<Card> getDrawPile() {
        return drawPile;
    }

    public void setDrawPile(List<Card> drawPile) {
        this.drawPile = drawPile;
    }

    public List<Card> getDiscardPile() {
        return discardPile;
    }

    public void setDiscardPile(List<Card> discardPile) {
        this.discardPile = discardPile;
    }

    public  Map<Long, List<Card>> getPlayerHands() {
        return playerHands;
    }

    public void setPlayerHands(Map<Long, List<Card>> playerHands) {
        this.playerHands = playerHands;
    }

    public List<Long> getOrderedPlayerIds() {
        return orderedPlayerIds;
    }

    public void setOrderedPlayerIds(List<Long> orderedPlayerIds) {
        this.orderedPlayerIds = orderedPlayerIds;
    }

    //# 8: Implement a global isMyTurn state that disables all buttons and click listeners on the game board when false.
    @Column(nullable = true)
    private Long currentPlayerId;

    public Long getCurrentPlayerId() {
        return currentPlayerId;
    }

    public void setCurrentPlayerId(Long currentPlayerId) {
        this.currentPlayerId = currentPlayerId;
    }

    public Card getDrawnCard() {
        return drawnCard;
    }

    public void setDrawnCard(Card drawnCard) {
        this.drawnCard = drawnCard;
    }

    // peeking at the beginning of the game
    public GameStatus getStatus() {
    return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public Map<Long, Boolean> getInitialPeekDoneByUserId() {
        return initialPeekDoneByUserId;
    }

    public void setInitialPeekDoneByUserId(Map<Long, Boolean> initialPeekDoneByUserId) {
        this.initialPeekDoneByUserId =
                initialPeekDoneByUserId != null ? initialPeekDoneByUserId : new HashMap<>();
    }

    public boolean isDrawnFromDeck() {
        return drawnFromDeck;
    }

    public void setDrawnFromDeck(boolean drawnFromDeck) {
        this.drawnFromDeck = drawnFromDeck;
    }

    public boolean isCaboCalled() {
        return isCaboCalled;
    }

    public void setCaboCalled(boolean isCaboCalled) {
        this.isCaboCalled = isCaboCalled;
    }

    public Long getCaboCalledByUserId() {
        return caboCalledByUserId;
    }

    public void setCaboCalledByUserId(Long caboCalledByUserId) {
        this.caboCalledByUserId = caboCalledByUserId;
    }

    public boolean isCaboForcedByTimeout() {
        return caboForcedByTimeout;
    }

    public void setCaboForcedByTimeout(boolean caboForcedByTimeout) {
        this.caboForcedByTimeout = caboForcedByTimeout;
    }

    public boolean isSpecialPeekUsed() {
        return specialPeekUsed;
    }

    public void setSpecialPeekUsed(boolean specialPeekUsed) {
        this.specialPeekUsed = specialPeekUsed;
    }

    public Map<Long, String> getRematchDecisionByUserId() {
        return rematchDecisionByUserId;
    }

    public void setRematchDecisionByUserId(Map<Long, String> rematchDecisionByUserId) {
        this.rematchDecisionByUserId =
                rematchDecisionByUserId != null ? rematchDecisionByUserId : new HashMap<>();
    }

    public Long getFreshRematchRequesterUserId() {
        return freshRematchRequesterUserId;
    }

    public void setFreshRematchRequesterUserId(Long freshRematchRequesterUserId) {
        this.freshRematchRequesterUserId = freshRematchRequesterUserId;
    }

    public Instant getRoundEndedAt() {
        return roundEndedAt;
    }

    public void setRoundEndedAt(Instant roundEndedAt) {
        this.roundEndedAt = roundEndedAt;
    }

    public long getTurnSeconds() {
        return turnSeconds;
    }

    public void setTurnSeconds(long turnSeconds) {
        this.turnSeconds = turnSeconds;
    }

    public long getInitialPeekSeconds() {
        return initialPeekSeconds;
    }

    public void setInitialPeekSeconds(long initialPeekSeconds) {
        this.initialPeekSeconds = initialPeekSeconds;
    }

    public long getAbilityRevealSeconds() {
        return abilityRevealSeconds;
    }

    public void setAbilityRevealSeconds(long abilityRevealSeconds) {
        this.abilityRevealSeconds = abilityRevealSeconds;
    }

    public long getAbilitySwapSeconds() {
        return abilitySwapSeconds;
    }

    public void setAbilitySwapSeconds(long abilitySwapSeconds) {
        this.abilitySwapSeconds = abilitySwapSeconds;
    }

    public long getRematchDecisionSeconds() {
        return rematchDecisionSeconds;
    }

    public void setRematchDecisionSeconds(long rematchDecisionSeconds) {
        this.rematchDecisionSeconds = rematchDecisionSeconds;
    }

    public long getCaboRevealSeconds() {
        return caboRevealSeconds;
    }

    public void setCaboRevealSeconds(long caboRevealSeconds) {
        this.caboRevealSeconds = caboRevealSeconds;
    }

    public long getAfkTimeoutSeconds() {
        return afkTimeoutSeconds;
    }

    public void setAfkTimeoutSeconds(long afkTimeoutSeconds) {
        this.afkTimeoutSeconds = afkTimeoutSeconds;
    }

    public GameMoveEvent getLastMoveEvent() {
        return lastMoveEvent;
    }

    public void setLastMoveEvent(GameMoveEvent lastMoveEvent) {
        this.lastMoveEvent = lastMoveEvent;
    }

    public long getLastMoveSequence() {
        return lastMoveSequence;
    }

    public void setLastMoveSequence(long lastMoveSequence) {
        this.lastMoveSequence = lastMoveSequence;
    }

    @Column
    private Long resumedFromSessionId;

    public Long getResumedFromSessionId() {
        return resumedFromSessionId;
    }

    public void setResumedFromSessionId(Long resumedFromSessionId) {
        this.resumedFromSessionId = resumedFromSessionId;
    }

}
