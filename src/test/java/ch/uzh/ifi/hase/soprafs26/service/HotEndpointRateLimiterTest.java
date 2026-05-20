package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.config.settings.ServerSettingsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HotEndpointRateLimiterTest {

    private HotEndpointRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        ServerSettingsProperties settings = new ServerSettingsProperties();
        settings.setHotEndpointMinIntervalMs(200L);

        @SuppressWarnings("unchecked")
        ObjectProvider<ServerSettingsProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(settings);
        rateLimiter = new HotEndpointRateLimiter(provider);
    }

    @Test
    void enforceHotReadLimit_sameCallerTooFast_throwsTooManyRequests() {
        rateLimiter.enforceHotReadLimit("sync-state", "token-1", "1.1.1.1");

        HotEndpointRateLimitException exception = assertThrows(
                HotEndpointRateLimitException.class,
                () -> rateLimiter.enforceHotReadLimit("sync-state", "token-1", "1.1.1.1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
        assertTrue(exception.getRetryAfterSeconds() >= 1L);
    }

    @Test
    void enforceHotReadLimit_differentCallersAllowedImmediately() {
        rateLimiter.enforceHotReadLimit("sync-state", "token-1", "1.1.1.1");
        rateLimiter.enforceHotReadLimit("sync-state", "token-2", "1.1.1.1");
    }
}
