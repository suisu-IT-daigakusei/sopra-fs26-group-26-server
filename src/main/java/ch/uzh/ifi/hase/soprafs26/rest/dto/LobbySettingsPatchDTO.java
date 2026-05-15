package ch.uzh.ifi.hase.soprafs26.rest.dto;

public class LobbySettingsPatchDTO {

    private Boolean isPublic;
    private Long afkTimeoutSeconds;
    private Long initialPeekSeconds;
    private Long turnSeconds;
    private Long abilityRevealSeconds;
    private Long abilitySwapSeconds;
    private Long absentRoundPoints;
    private Long websocketGraceSeconds;
    private Long chatCooldownSeconds;

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
}
