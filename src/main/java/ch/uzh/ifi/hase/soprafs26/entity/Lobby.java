package ch.uzh.ifi.hase.soprafs26.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "lobbies", indexes = {
        @Index(name = "idx_lobby_status_player_set_key", columnList = "status,player_set_key")
})
public class Lobby implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false, unique = true)
    private String sessionId; // unique code players use to find the lobby

    @Column(nullable = false)
    private Long sessionHostUserId; // userId of the creator

    @ElementCollection
    private List<Long> playerIds = new ArrayList<>(); // userIds of players in lobby

    // Users kicked by host from this waiting lobby.
    // They may rejoin only through an explicit host invite flow.
    @ElementCollection
    private List<Long> kickedUserIds = new ArrayList<>();

    @Column(nullable = false)
    private Boolean isPublic = true;

    @Column(nullable = false)
    private Integer currentRound = 0;

    @Column(nullable = false)
    private String status = "WAITING"; // WAITING or PLAYING // prob later SPECTATING

    @Column(name = "player_set_key", nullable = false, length = 256)
    private String playerSetKey = "";

    // Per-lobby AFK timeout used while the game is active.
    @Column(nullable = false)
    private Long afkTimeoutSeconds = 300L;

    // Per-lobby initial peek timer.
    @Column(nullable = false)
    private Long initialPeekSeconds = 10L;

    // Per-lobby turn timer.
    @Column(nullable = false)
    private Long turnSeconds = 30L;

    // Per-lobby reveal duration for peek/spy before auto-end.
    @Column(nullable = false)
    private Long abilityRevealSeconds = 5L;

    // Per-lobby swap ability timer before auto-end.
    @Column(nullable = false)
    private Long abilitySwapSeconds = 10L;

    // Score added for players who did not attend a round in this session.
    @Column(nullable = false)
    private Long absentRoundPoints = 20L;

    // Per-lobby websocket disconnect grace period before timeout handling.
    @Column(nullable = false)
    private Long websocketGraceSeconds = 300L;

    // Per-lobby chat cooldown in seconds.
    @Column(nullable = false)
    private Long chatCooldownSeconds = 3L;

    // user ID's of the spectators
    @ElementCollection
    private List<Long> spectatorIds = new ArrayList<>();

    // Assigned lobby character color per player (resolved from preferred priorities with fallback).
    @ElementCollection
    @CollectionTable(name = "lobby_assigned_character_color", joinColumns = @JoinColumn(name = "lobby_id"))
    @MapKeyColumn(name = "user_id")
    @Column(name = "color_id", nullable = false)
    private Map<Long, String> assignedCharacterColorByUserId = new HashMap<>();

    // Ready state per player while in WAITING lobbies.
    @ElementCollection
    @CollectionTable(name = "lobby_player_ready_state", joinColumns = @JoinColumn(name = "lobby_id"))
    @MapKeyColumn(name = "user_id")
    @Column(name = "is_ready", nullable = false)
    private Map<Long, Boolean> playerReadyByUserId = new HashMap<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long getSessionHostUserId() { return sessionHostUserId; }
    public void setSessionHostUserId(Long sessionHostUserId) { this.sessionHostUserId = sessionHostUserId; }

    public List<Long> getPlayerIds() { return playerIds; }
    public void setPlayerIds(List<Long> playerIds) { this.playerIds = playerIds; }

    public List<Long> getKickedUserIds() { return kickedUserIds; }
    public void setKickedUserIds(List<Long> kickedUserIds) { this.kickedUserIds = kickedUserIds; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    public Integer getCurrentRound() { return currentRound; }
    public void setCurrentRound(Integer currentRound) { this.currentRound = currentRound; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPlayerSetKey() { return playerSetKey; }
    public void setPlayerSetKey(String playerSetKey) { this.playerSetKey = playerSetKey; }

    public Long getAfkTimeoutSeconds() { return afkTimeoutSeconds; }
    public void setAfkTimeoutSeconds(Long afkTimeoutSeconds) { this.afkTimeoutSeconds = afkTimeoutSeconds; }

    public Long getInitialPeekSeconds() { return initialPeekSeconds; }
    public void setInitialPeekSeconds(Long initialPeekSeconds) { this.initialPeekSeconds = initialPeekSeconds; }

    public Long getTurnSeconds() { return turnSeconds; }
    public void setTurnSeconds(Long turnSeconds) { this.turnSeconds = turnSeconds; }

    public Long getAbilityRevealSeconds() { return abilityRevealSeconds; }
    public void setAbilityRevealSeconds(Long abilityRevealSeconds) { this.abilityRevealSeconds = abilityRevealSeconds; }

    public Long getAbilitySwapSeconds() { return abilitySwapSeconds; }
    public void setAbilitySwapSeconds(Long abilitySwapSeconds) { this.abilitySwapSeconds = abilitySwapSeconds; }

    public Long getAbsentRoundPoints() { return absentRoundPoints; }
    public void setAbsentRoundPoints(Long absentRoundPoints) { this.absentRoundPoints = absentRoundPoints; }

    public Long getWebsocketGraceSeconds() { return websocketGraceSeconds; }
    public void setWebsocketGraceSeconds(Long websocketGraceSeconds) { this.websocketGraceSeconds = websocketGraceSeconds; }

    public Long getChatCooldownSeconds() { return chatCooldownSeconds; }
    public void setChatCooldownSeconds(Long chatCooldownSeconds) { this.chatCooldownSeconds = chatCooldownSeconds; }

    public List<Long> getSpectatorIds() { return spectatorIds; }
    public void setSpectatorIds(List<Long> spectatorIds) { this.spectatorIds = spectatorIds; }

    public Map<Long, String> getAssignedCharacterColorByUserId() { return assignedCharacterColorByUserId; }
    public void setAssignedCharacterColorByUserId(Map<Long, String> assignedCharacterColorByUserId) {
        this.assignedCharacterColorByUserId = assignedCharacterColorByUserId;
    }

    public Map<Long, Boolean> getPlayerReadyByUserId() { return playerReadyByUserId; }
    public void setPlayerReadyByUserId(Map<Long, Boolean> playerReadyByUserId) {
        this.playerReadyByUserId = playerReadyByUserId;
    }

    @PrePersist
    @PreUpdate
    private void refreshPlayerSetKey() {
        this.playerSetKey = buildPlayerSetKey(playerIds);
    }

    private String buildPlayerSetKey(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        Set<Long> distinctIds = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                distinctIds.add(id);
            }
        }
        if (distinctIds.isEmpty()) {
            return "";
        }
        return distinctIds.stream()
                .sorted()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
