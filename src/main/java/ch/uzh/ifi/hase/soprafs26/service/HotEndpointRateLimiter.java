package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.config.settings.ServerSettingsProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class HotEndpointRateLimiter {

    private static final long CLEANUP_INTERVAL_MS = 30000L;
    private static final double MIN_REFILL_TOKENS_PER_SECOND = 0.1d;
    private static final int MAX_BUCKET_ENTRY_MULTIPLIER = 4;

    private static final class TokenBucketState {
        private double tokens;
        private long lastRefillEpochMs;
        private long lastAcceptedEpochMs;
    }

    private static final class EndpointLimitProfile {
        private final int burstCapacity;
        private final double refillTokensPerSecond;
        private final long minIntervalMs;

        private EndpointLimitProfile(int burstCapacity, double refillTokensPerSecond, long minIntervalMs) {
            this.burstCapacity = Math.max(1, burstCapacity);
            this.refillTokensPerSecond = Math.max(MIN_REFILL_TOKENS_PER_SECOND, refillTokensPerSecond);
            this.minIntervalMs = Math.max(1L, minIntervalMs);
        }
    }

    private final ServerSettingsProperties serverSettings;
    private final Map<String, TokenBucketState> tokenBucketByKey = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupEpochMs = new AtomicLong(0L);

    public HotEndpointRateLimiter(ObjectProvider<ServerSettingsProperties> serverSettingsProvider) {
        this.serverSettings = serverSettingsProvider.getIfAvailable(ServerSettingsProperties::new);
    }

    public void enforceHotReadLimit(String endpointKey, String authorizationToken, String remoteAddress) {
        enforceHotReadLimit(endpointKey, authorizationToken, null, remoteAddress);
    }

    public void enforceHotReadLimit(
            String endpointKey,
            String authorizationToken,
            String forwardedForHeader,
            String remoteAddress
    ) {
        if (endpointKey == null || endpointKey.isBlank()) {
            return;
        }

        EndpointLimitProfile profile = resolveProfile(endpointKey);
        long nowMs = System.currentTimeMillis();
        String callerKey = resolveCallerKey(authorizationToken, forwardedForHeader, remoteAddress);
        String rateLimitKey = endpointKey + "|" + callerKey;
        TokenBucketState bucketState = tokenBucketByKey.computeIfAbsent(rateLimitKey, ignored -> {
            TokenBucketState created = new TokenBucketState();
            created.tokens = profile.burstCapacity;
            created.lastRefillEpochMs = nowMs;
            created.lastAcceptedEpochMs = 0L;
            return created;
        });

        synchronized (bucketState) {
            long elapsedSinceRefillMs = Math.max(0L, nowMs - bucketState.lastRefillEpochMs);
            if (elapsedSinceRefillMs > 0L) {
                double replenishedTokens = (elapsedSinceRefillMs / 1000.0d) * profile.refillTokensPerSecond;
                bucketState.tokens = Math.min(profile.burstCapacity, bucketState.tokens + replenishedTokens);
                bucketState.lastRefillEpochMs = nowMs;
            }

            long elapsedSinceAcceptedMs = nowMs - bucketState.lastAcceptedEpochMs;
            if (bucketState.lastAcceptedEpochMs > 0L && elapsedSinceAcceptedMs < profile.minIntervalMs) {
                long retryAfterMs = Math.max(1L, profile.minIntervalMs - elapsedSinceAcceptedMs);
                long retryAfterSeconds = Math.max(1L, (long) Math.ceil(retryAfterMs / 1000.0d));
                throw new HotEndpointRateLimitException(
                        "Too many sync requests; retry shortly",
                        retryAfterSeconds);
            }

            if (bucketState.tokens < 1.0d) {
                double missingTokens = 1.0d - bucketState.tokens;
                long retryAfterSeconds = Math.max(
                        1L,
                        (long) Math.ceil(missingTokens / profile.refillTokensPerSecond));
                throw new HotEndpointRateLimitException(
                        "Too many sync requests; retry shortly",
                        retryAfterSeconds);
            }

            bucketState.tokens -= 1.0d;
            bucketState.lastAcceptedEpochMs = nowMs;
        }

        maybeCleanupStaleEntries(nowMs);
    }

    private EndpointLimitProfile resolveProfile(String endpointKey) {
        long minIntervalMs = Math.max(1L, serverSettings.getHotEndpointMinIntervalMs());
        if ("sync-state".equals(endpointKey)) {
            return new EndpointLimitProfile(
                    serverSettings.getHotEndpointSyncStateBurstCapacity(),
                    serverSettings.getHotEndpointSyncStateRefillTokensPerSecond(),
                    minIntervalMs);
        }
        if ("post-round-lobby".equals(endpointKey)) {
            return new EndpointLimitProfile(
                    serverSettings.getHotEndpointPostRoundBurstCapacity(),
                    serverSettings.getHotEndpointPostRoundRefillTokensPerSecond(),
                    minIntervalMs);
        }
        return new EndpointLimitProfile(
                serverSettings.getHotEndpointBurstCapacity(),
                serverSettings.getHotEndpointRefillTokensPerSecond(),
                minIntervalMs);
    }

    private String resolveCallerKey(String authorizationToken, String forwardedForHeader, String remoteAddress) {
        if (authorizationToken != null) {
            String normalizedToken = authorizationToken.trim();
            if (!normalizedToken.isEmpty()) {
                return "token:" + normalizedToken;
            }
        }

        String forwardedAddress = extractForwardedAddress(forwardedForHeader);
        if (forwardedAddress != null) {
            return "ip:" + forwardedAddress;
        }

        if (remoteAddress != null) {
            String normalizedAddress = remoteAddress.trim();
            if (!normalizedAddress.isEmpty()) {
                return "ip:" + normalizedAddress;
            }
        }

        return "anonymous";
    }

    private String extractForwardedAddress(String forwardedForHeader) {
        if (forwardedForHeader == null || forwardedForHeader.isBlank()) {
            return null;
        }
        String[] candidates = forwardedForHeader.split(",");
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String normalized = candidate.trim();
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return null;
    }

    private void maybeCleanupStaleEntries(long nowMs) {
        long previousCleanup = lastCleanupEpochMs.get();
        if (nowMs - previousCleanup < CLEANUP_INTERVAL_MS) {
            return;
        }
        if (!lastCleanupEpochMs.compareAndSet(previousCleanup, nowMs)) {
            return;
        }

        long staleEntryMs = Math.max(1000L, serverSettings.getHotEndpointEntryTtlMs());
        Iterator<Map.Entry<String, TokenBucketState>> iterator = tokenBucketByKey.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TokenBucketState> entry = iterator.next();
            TokenBucketState state = entry.getValue();
            if (state == null) {
                iterator.remove();
                continue;
            }
            long lastAcceptedMs;
            synchronized (state) {
                lastAcceptedMs = state.lastAcceptedEpochMs;
            }
            if (lastAcceptedMs <= 0L || nowMs - lastAcceptedMs > staleEntryMs) {
                iterator.remove();
            }
        }

        int maxEntries = Math.max(512, serverSettings.getMaxTransientLookupCacheEntries() * MAX_BUCKET_ENTRY_MULTIPLIER);
        int overflowEntries = tokenBucketByKey.size() - maxEntries;
        if (overflowEntries <= 0) {
            return;
        }

        List<Map.Entry<String, TokenBucketState>> oldestEntries = tokenBucketByKey.entrySet().stream()
                .filter(entry -> entry != null && entry.getKey() != null && entry.getValue() != null)
                .sorted(Comparator.comparingLong(entry -> entry.getValue().lastAcceptedEpochMs))
                .limit(overflowEntries)
                .toList();
        for (Map.Entry<String, TokenBucketState> oldestEntry : oldestEntries) {
            tokenBucketByKey.remove(oldestEntry.getKey());
        }
    }
}
