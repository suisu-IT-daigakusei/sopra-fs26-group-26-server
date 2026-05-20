package ch.uzh.ifi.hase.soprafs26.config.settings;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Server runtime settings loaded from `app.server.*`
 * Contains shared scheduler sizing and allowed CORS origins
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.server")
public class ServerSettingsProperties {

    // Shared scheduler thread-pool size for timers and scheduled transitions
    @Min(1)
    private int gameSchedulerThreadPoolSize = 10;

    // WebSocket inbound channel executor tuning
    @Min(1)
    private int websocketInboundCorePoolSize = 4;
    @Min(1)
    private int websocketInboundMaxPoolSize = 16;
    @Min(1)
    private int websocketInboundQueueCapacity = 200;

    // WebSocket outbound channel executor tuning
    @Min(1)
    private int websocketOutboundCorePoolSize = 4;
    @Min(1)
    private int websocketOutboundMaxPoolSize = 16;
    @Min(1)
    private int websocketOutboundQueueCapacity = 300;

    // WebSocket transport back-pressure guardrails
    @Min(1000)
    private int websocketSendTimeLimitMs = 15000;
    @Min(1024)
    private int websocketSendBufferSizeLimitBytes = 524288;
    @Min(1024)
    private int websocketMessageSizeLimitBytes = 131072;

    // Maximum active websocket sessions tracked and served per user.
    @Min(1)
    private int maxWebsocketSessionsPerUser = 2;

    // Minimum spacing between hot fallback requests from the same user/token.
    @Min(1)
    private long hotEndpointMinIntervalMs = 220;
    @Min(1)
    private int hotEndpointBurstCapacity = 8;
    @DecimalMin("0.1")
    private double hotEndpointRefillTokensPerSecond = 4.0d;
    @Min(1)
    private int hotEndpointSyncStateBurstCapacity = 10;
    @DecimalMin("0.1")
    private double hotEndpointSyncStateRefillTokensPerSecond = 6.0d;
    @Min(1)
    private int hotEndpointPostRoundBurstCapacity = 6;
    @DecimalMin("0.1")
    private double hotEndpointPostRoundRefillTokensPerSecond = 3.0d;
    @Min(1000)
    private long hotEndpointEntryTtlMs = 180000;

    // Tiny TTL cache windows for repeated lookup helpers under reconnect storms.
    @Min(1)
    private long playingLobbySnapshotCacheTtlMs = 400;
    @Min(1)
    private long waitingLobbyLookupCacheTtlMs = 350;
    @Min(1)
    private int maxTransientLookupCacheEntries = 4096;
    @Min(1)
    private long authTokenLookupCacheTtlMs = 1500;
    @Min(1)
    private long syncStateCacheTtlMs = 350;
    @Min(1)
    private long rematchDuplicateDecisionWindowMs = 900;

    @Min(1000)
    private long websocketSessionStaleAfterMs = 120000;
    @Min(1000)
    private long websocketSessionPruneIntervalMs = 30000;

    // Frontend origins allowed to call backend HTTP endpoints
    @NotEmpty
    private List<String> corsAllowedOrigins = new ArrayList<>(List.of(
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "https://sopra-fs26-group-26-client.vercel.app"
    ));

    public int getGameSchedulerThreadPoolSize() {
        return gameSchedulerThreadPoolSize;
    }

    public void setGameSchedulerThreadPoolSize(int gameSchedulerThreadPoolSize) {
        this.gameSchedulerThreadPoolSize = gameSchedulerThreadPoolSize;
    }

    public int getWebsocketInboundCorePoolSize() {
        return websocketInboundCorePoolSize;
    }

    public void setWebsocketInboundCorePoolSize(int websocketInboundCorePoolSize) {
        this.websocketInboundCorePoolSize = websocketInboundCorePoolSize;
    }

    public int getWebsocketInboundMaxPoolSize() {
        return websocketInboundMaxPoolSize;
    }

    public void setWebsocketInboundMaxPoolSize(int websocketInboundMaxPoolSize) {
        this.websocketInboundMaxPoolSize = websocketInboundMaxPoolSize;
    }

    public int getWebsocketInboundQueueCapacity() {
        return websocketInboundQueueCapacity;
    }

    public void setWebsocketInboundQueueCapacity(int websocketInboundQueueCapacity) {
        this.websocketInboundQueueCapacity = websocketInboundQueueCapacity;
    }

    public int getWebsocketOutboundCorePoolSize() {
        return websocketOutboundCorePoolSize;
    }

    public void setWebsocketOutboundCorePoolSize(int websocketOutboundCorePoolSize) {
        this.websocketOutboundCorePoolSize = websocketOutboundCorePoolSize;
    }

    public int getWebsocketOutboundMaxPoolSize() {
        return websocketOutboundMaxPoolSize;
    }

    public void setWebsocketOutboundMaxPoolSize(int websocketOutboundMaxPoolSize) {
        this.websocketOutboundMaxPoolSize = websocketOutboundMaxPoolSize;
    }

    public int getWebsocketOutboundQueueCapacity() {
        return websocketOutboundQueueCapacity;
    }

    public void setWebsocketOutboundQueueCapacity(int websocketOutboundQueueCapacity) {
        this.websocketOutboundQueueCapacity = websocketOutboundQueueCapacity;
    }

    public int getWebsocketSendTimeLimitMs() {
        return websocketSendTimeLimitMs;
    }

    public void setWebsocketSendTimeLimitMs(int websocketSendTimeLimitMs) {
        this.websocketSendTimeLimitMs = websocketSendTimeLimitMs;
    }

    public int getWebsocketSendBufferSizeLimitBytes() {
        return websocketSendBufferSizeLimitBytes;
    }

    public void setWebsocketSendBufferSizeLimitBytes(int websocketSendBufferSizeLimitBytes) {
        this.websocketSendBufferSizeLimitBytes = websocketSendBufferSizeLimitBytes;
    }

    public int getWebsocketMessageSizeLimitBytes() {
        return websocketMessageSizeLimitBytes;
    }

    public void setWebsocketMessageSizeLimitBytes(int websocketMessageSizeLimitBytes) {
        this.websocketMessageSizeLimitBytes = websocketMessageSizeLimitBytes;
    }

    public int getMaxWebsocketSessionsPerUser() {
        return maxWebsocketSessionsPerUser;
    }

    public void setMaxWebsocketSessionsPerUser(int maxWebsocketSessionsPerUser) {
        this.maxWebsocketSessionsPerUser = maxWebsocketSessionsPerUser;
    }

    public long getHotEndpointMinIntervalMs() {
        return hotEndpointMinIntervalMs;
    }

    public void setHotEndpointMinIntervalMs(long hotEndpointMinIntervalMs) {
        this.hotEndpointMinIntervalMs = hotEndpointMinIntervalMs;
    }

    public int getHotEndpointBurstCapacity() {
        return hotEndpointBurstCapacity;
    }

    public void setHotEndpointBurstCapacity(int hotEndpointBurstCapacity) {
        this.hotEndpointBurstCapacity = hotEndpointBurstCapacity;
    }

    public double getHotEndpointRefillTokensPerSecond() {
        return hotEndpointRefillTokensPerSecond;
    }

    public void setHotEndpointRefillTokensPerSecond(double hotEndpointRefillTokensPerSecond) {
        this.hotEndpointRefillTokensPerSecond = hotEndpointRefillTokensPerSecond;
    }

    public int getHotEndpointSyncStateBurstCapacity() {
        return hotEndpointSyncStateBurstCapacity;
    }

    public void setHotEndpointSyncStateBurstCapacity(int hotEndpointSyncStateBurstCapacity) {
        this.hotEndpointSyncStateBurstCapacity = hotEndpointSyncStateBurstCapacity;
    }

    public double getHotEndpointSyncStateRefillTokensPerSecond() {
        return hotEndpointSyncStateRefillTokensPerSecond;
    }

    public void setHotEndpointSyncStateRefillTokensPerSecond(double hotEndpointSyncStateRefillTokensPerSecond) {
        this.hotEndpointSyncStateRefillTokensPerSecond = hotEndpointSyncStateRefillTokensPerSecond;
    }

    public int getHotEndpointPostRoundBurstCapacity() {
        return hotEndpointPostRoundBurstCapacity;
    }

    public void setHotEndpointPostRoundBurstCapacity(int hotEndpointPostRoundBurstCapacity) {
        this.hotEndpointPostRoundBurstCapacity = hotEndpointPostRoundBurstCapacity;
    }

    public double getHotEndpointPostRoundRefillTokensPerSecond() {
        return hotEndpointPostRoundRefillTokensPerSecond;
    }

    public void setHotEndpointPostRoundRefillTokensPerSecond(double hotEndpointPostRoundRefillTokensPerSecond) {
        this.hotEndpointPostRoundRefillTokensPerSecond = hotEndpointPostRoundRefillTokensPerSecond;
    }

    public long getHotEndpointEntryTtlMs() {
        return hotEndpointEntryTtlMs;
    }

    public void setHotEndpointEntryTtlMs(long hotEndpointEntryTtlMs) {
        this.hotEndpointEntryTtlMs = hotEndpointEntryTtlMs;
    }

    public long getPlayingLobbySnapshotCacheTtlMs() {
        return playingLobbySnapshotCacheTtlMs;
    }

    public void setPlayingLobbySnapshotCacheTtlMs(long playingLobbySnapshotCacheTtlMs) {
        this.playingLobbySnapshotCacheTtlMs = playingLobbySnapshotCacheTtlMs;
    }

    public long getWaitingLobbyLookupCacheTtlMs() {
        return waitingLobbyLookupCacheTtlMs;
    }

    public void setWaitingLobbyLookupCacheTtlMs(long waitingLobbyLookupCacheTtlMs) {
        this.waitingLobbyLookupCacheTtlMs = waitingLobbyLookupCacheTtlMs;
    }

    public int getMaxTransientLookupCacheEntries() {
        return maxTransientLookupCacheEntries;
    }

    public void setMaxTransientLookupCacheEntries(int maxTransientLookupCacheEntries) {
        this.maxTransientLookupCacheEntries = maxTransientLookupCacheEntries;
    }

    public long getAuthTokenLookupCacheTtlMs() {
        return authTokenLookupCacheTtlMs;
    }

    public void setAuthTokenLookupCacheTtlMs(long authTokenLookupCacheTtlMs) {
        this.authTokenLookupCacheTtlMs = authTokenLookupCacheTtlMs;
    }

    public long getSyncStateCacheTtlMs() {
        return syncStateCacheTtlMs;
    }

    public void setSyncStateCacheTtlMs(long syncStateCacheTtlMs) {
        this.syncStateCacheTtlMs = syncStateCacheTtlMs;
    }

    public long getRematchDuplicateDecisionWindowMs() {
        return rematchDuplicateDecisionWindowMs;
    }

    public void setRematchDuplicateDecisionWindowMs(long rematchDuplicateDecisionWindowMs) {
        this.rematchDuplicateDecisionWindowMs = rematchDuplicateDecisionWindowMs;
    }

    public long getWebsocketSessionStaleAfterMs() {
        return websocketSessionStaleAfterMs;
    }

    public void setWebsocketSessionStaleAfterMs(long websocketSessionStaleAfterMs) {
        this.websocketSessionStaleAfterMs = websocketSessionStaleAfterMs;
    }

    public long getWebsocketSessionPruneIntervalMs() {
        return websocketSessionPruneIntervalMs;
    }

    public void setWebsocketSessionPruneIntervalMs(long websocketSessionPruneIntervalMs) {
        this.websocketSessionPruneIntervalMs = websocketSessionPruneIntervalMs;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }
}
