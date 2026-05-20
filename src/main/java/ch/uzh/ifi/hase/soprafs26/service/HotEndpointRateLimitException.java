package ch.uzh.ifi.hase.soprafs26.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Signals rate-limited hot-read traffic and carries a Retry-After hint.
 */
public class HotEndpointRateLimitException extends ResponseStatusException {

    private final long retryAfterSeconds;

    public HotEndpointRateLimitException(String reason, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, reason);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
