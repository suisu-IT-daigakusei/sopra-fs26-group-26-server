package ch.uzh.ifi.hase.soprafs26.config.settings;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Lobby timer defaults and allowed ranges loaded from `app.lobby-settings.*`
 * Hosts can adjust these timers in lobby settings; values are clamped to the configured min/max bounds!
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.lobby-settings")
public class LobbySettingsProperties {

    // AFK timeout: default applied to new lobbies and host-adjustable within min/max
    @Min(1)
    private long afkTimeoutDefaultSeconds = 300;
    @Min(1)
    private long afkTimeoutMinSeconds = 180;
    @Min(1)
    private long afkTimeoutMaxSeconds = 600;

    // Initial peek timer: default and host-adjustable range
    @Min(1)
    private long initialPeekDefaultSeconds = 10;
    @Min(1)
    private long initialPeekMinSeconds = 3;
    @Min(1)
    private long initialPeekMaxSeconds = 60;

    // Turn timer: default and host-adjustable range
    @Min(1)
    private long turnDefaultSeconds = 30;
    @Min(1)
    private long turnMinSeconds = 10;
    @Min(1)
    private long turnMaxSeconds = 60;

    // Ability reveal timer: default and host-adjustable range
    @Min(1)
    private long abilityRevealDefaultSeconds = 5;
    @Min(1)
    private long abilityRevealMinSeconds = 3;
    @Min(1)
    private long abilityRevealMaxSeconds = 10;

    // Ability swap timer: default and host-adjustable range
    @Min(1)
    private long abilitySwapDefaultSeconds = 10;
    @Min(1)
    private long abilitySwapMinSeconds = 5;
    @Min(1)
    private long abilitySwapMaxSeconds = 30;

    // Score assigned per missed round in a session.
    @Min(0)
    private long absentRoundPointsDefault = 20;
    @Min(0)
    private long absentRoundPointsMin = 0;
    @Min(0)
    private long absentRoundPointsMax = 100;

    // Websocket disconnect grace timer: default and host-adjustable range
    @Min(1)
    private long websocketGraceDefaultSeconds = 300;
    @Min(1)
    private long websocketGraceMinSeconds = 180;
    @Min(1)
    private long websocketGraceMaxSeconds = 600;

    // Chat cooldown: default and host-adjustable range.
    @Min(1)
    private long chatCooldownDefaultSeconds = 3;
    @Min(1)
    private long chatCooldownMinSeconds = 1;
    @Min(1)
    private long chatCooldownMaxSeconds = 60;

    public long getAfkTimeoutDefaultSeconds() { return afkTimeoutDefaultSeconds; }
    public void setAfkTimeoutDefaultSeconds(long afkTimeoutDefaultSeconds) { this.afkTimeoutDefaultSeconds = afkTimeoutDefaultSeconds; }
    public long getAfkTimeoutMinSeconds() { return afkTimeoutMinSeconds; }
    public void setAfkTimeoutMinSeconds(long afkTimeoutMinSeconds) { this.afkTimeoutMinSeconds = afkTimeoutMinSeconds; }
    public long getAfkTimeoutMaxSeconds() { return afkTimeoutMaxSeconds; }
    public void setAfkTimeoutMaxSeconds(long afkTimeoutMaxSeconds) { this.afkTimeoutMaxSeconds = afkTimeoutMaxSeconds; }

    public long getInitialPeekDefaultSeconds() { return initialPeekDefaultSeconds; }
    public void setInitialPeekDefaultSeconds(long initialPeekDefaultSeconds) { this.initialPeekDefaultSeconds = initialPeekDefaultSeconds; }
    public long getInitialPeekMinSeconds() { return initialPeekMinSeconds; }
    public void setInitialPeekMinSeconds(long initialPeekMinSeconds) { this.initialPeekMinSeconds = initialPeekMinSeconds; }
    public long getInitialPeekMaxSeconds() { return initialPeekMaxSeconds; }
    public void setInitialPeekMaxSeconds(long initialPeekMaxSeconds) { this.initialPeekMaxSeconds = initialPeekMaxSeconds; }

    public long getTurnDefaultSeconds() { return turnDefaultSeconds; }
    public void setTurnDefaultSeconds(long turnDefaultSeconds) { this.turnDefaultSeconds = turnDefaultSeconds; }
    public long getTurnMinSeconds() { return turnMinSeconds; }
    public void setTurnMinSeconds(long turnMinSeconds) { this.turnMinSeconds = turnMinSeconds; }
    public long getTurnMaxSeconds() { return turnMaxSeconds; }
    public void setTurnMaxSeconds(long turnMaxSeconds) { this.turnMaxSeconds = turnMaxSeconds; }

    public long getAbilityRevealDefaultSeconds() { return abilityRevealDefaultSeconds; }
    public void setAbilityRevealDefaultSeconds(long abilityRevealDefaultSeconds) { this.abilityRevealDefaultSeconds = abilityRevealDefaultSeconds; }
    public long getAbilityRevealMinSeconds() { return abilityRevealMinSeconds; }
    public void setAbilityRevealMinSeconds(long abilityRevealMinSeconds) { this.abilityRevealMinSeconds = abilityRevealMinSeconds; }
    public long getAbilityRevealMaxSeconds() { return abilityRevealMaxSeconds; }
    public void setAbilityRevealMaxSeconds(long abilityRevealMaxSeconds) { this.abilityRevealMaxSeconds = abilityRevealMaxSeconds; }

    public long getAbilitySwapDefaultSeconds() { return abilitySwapDefaultSeconds; }
    public void setAbilitySwapDefaultSeconds(long abilitySwapDefaultSeconds) { this.abilitySwapDefaultSeconds = abilitySwapDefaultSeconds; }
    public long getAbilitySwapMinSeconds() { return abilitySwapMinSeconds; }
    public void setAbilitySwapMinSeconds(long abilitySwapMinSeconds) { this.abilitySwapMinSeconds = abilitySwapMinSeconds; }
    public long getAbilitySwapMaxSeconds() { return abilitySwapMaxSeconds; }
    public void setAbilitySwapMaxSeconds(long abilitySwapMaxSeconds) { this.abilitySwapMaxSeconds = abilitySwapMaxSeconds; }

    public long getAbsentRoundPointsDefault() { return absentRoundPointsDefault; }
    public void setAbsentRoundPointsDefault(long absentRoundPointsDefault) { this.absentRoundPointsDefault = absentRoundPointsDefault; }
    public long getAbsentRoundPointsMin() { return absentRoundPointsMin; }
    public void setAbsentRoundPointsMin(long absentRoundPointsMin) { this.absentRoundPointsMin = absentRoundPointsMin; }
    public long getAbsentRoundPointsMax() { return absentRoundPointsMax; }
    public void setAbsentRoundPointsMax(long absentRoundPointsMax) { this.absentRoundPointsMax = absentRoundPointsMax; }

    public long getWebsocketGraceDefaultSeconds() { return websocketGraceDefaultSeconds; }
    public void setWebsocketGraceDefaultSeconds(long websocketGraceDefaultSeconds) { this.websocketGraceDefaultSeconds = websocketGraceDefaultSeconds; }
    public long getWebsocketGraceMinSeconds() { return websocketGraceMinSeconds; }
    public void setWebsocketGraceMinSeconds(long websocketGraceMinSeconds) { this.websocketGraceMinSeconds = websocketGraceMinSeconds; }
    public long getWebsocketGraceMaxSeconds() { return websocketGraceMaxSeconds; }
    public void setWebsocketGraceMaxSeconds(long websocketGraceMaxSeconds) { this.websocketGraceMaxSeconds = websocketGraceMaxSeconds; }

    public long getChatCooldownDefaultSeconds() { return chatCooldownDefaultSeconds; }
    public void setChatCooldownDefaultSeconds(long chatCooldownDefaultSeconds) { this.chatCooldownDefaultSeconds = chatCooldownDefaultSeconds; }
    public long getChatCooldownMinSeconds() { return chatCooldownMinSeconds; }
    public void setChatCooldownMinSeconds(long chatCooldownMinSeconds) { this.chatCooldownMinSeconds = chatCooldownMinSeconds; }
    public long getChatCooldownMaxSeconds() { return chatCooldownMaxSeconds; }
    public void setChatCooldownMaxSeconds(long chatCooldownMaxSeconds) { this.chatCooldownMaxSeconds = chatCooldownMaxSeconds; }
}
