package ch.uzh.ifi.hase.soprafs26.rest.dto;

import java.util.ArrayList;
import java.util.List;

public class WaitingLobbyViewDTO {

    private Long lobbyId;
    private String sessionId;
    private Boolean isPublic;
    private Long afkTimeoutSeconds;
    private Long initialPeekSeconds;
    private Long turnSeconds;
    private Long abilityRevealSeconds;
    private Long abilitySwapSeconds;
    private Long absentRoundPoints;
    private Long websocketGraceSeconds;
    private Long chatCooldownSeconds;
    private Boolean viewerIsHost;
    private List<WaitingLobbyPlayerRowDTO> players = new ArrayList<>();

    public Long getLobbyId() {
        return lobbyId;
    }

    public void setLobbyId(Long lobbyId) {
        this.lobbyId = lobbyId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Boolean getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Boolean isPublic) {
        this.isPublic = isPublic;
    }

    public Long getAfkTimeoutSeconds() {
        return afkTimeoutSeconds;
    }

    public void setAfkTimeoutSeconds(Long afkTimeoutSeconds) {
        this.afkTimeoutSeconds = afkTimeoutSeconds;
    }

    public Long getInitialPeekSeconds() {
        return initialPeekSeconds;
    }

    public void setInitialPeekSeconds(Long initialPeekSeconds) {
        this.initialPeekSeconds = initialPeekSeconds;
    }

    public Long getTurnSeconds() {
        return turnSeconds;
    }

    public void setTurnSeconds(Long turnSeconds) {
        this.turnSeconds = turnSeconds;
    }

    public Long getAbilityRevealSeconds() {
        return abilityRevealSeconds;
    }

    public void setAbilityRevealSeconds(Long abilityRevealSeconds) {
        this.abilityRevealSeconds = abilityRevealSeconds;
    }

    public Long getAbilitySwapSeconds() {
        return abilitySwapSeconds;
    }

    public void setAbilitySwapSeconds(Long abilitySwapSeconds) {
        this.abilitySwapSeconds = abilitySwapSeconds;
    }

    public Long getAbsentRoundPoints() {
        return absentRoundPoints;
    }

    public void setAbsentRoundPoints(Long absentRoundPoints) {
        this.absentRoundPoints = absentRoundPoints;
    }

    public Long getWebsocketGraceSeconds() {
        return websocketGraceSeconds;
    }

    public void setWebsocketGraceSeconds(Long websocketGraceSeconds) {
        this.websocketGraceSeconds = websocketGraceSeconds;
    }

    public Long getChatCooldownSeconds() {
        return chatCooldownSeconds;
    }

    public void setChatCooldownSeconds(Long chatCooldownSeconds) {
        this.chatCooldownSeconds = chatCooldownSeconds;
    }

    public Boolean getViewerIsHost() {
        return viewerIsHost;
    }

    public void setViewerIsHost(Boolean viewerIsHost) {
        this.viewerIsHost = viewerIsHost;
    }

    public List<WaitingLobbyPlayerRowDTO> getPlayers() {
        return players;
    }

    public void setPlayers(List<WaitingLobbyPlayerRowDTO> players) {
        this.players = players;
    }

    private List<WaitingLobbyPlayerRowDTO> spectators = new ArrayList<>();

    public List<WaitingLobbyPlayerRowDTO> getSpectators() { return spectators; }
    public void setSpectators(List<WaitingLobbyPlayerRowDTO> spectators) { this.spectators = spectators; }
}
