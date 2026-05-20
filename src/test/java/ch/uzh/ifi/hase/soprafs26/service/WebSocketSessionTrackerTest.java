package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.config.settings.ServerSettingsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketSessionTrackerTest {

    @Mock
    private ObjectProvider<ServerSettingsProperties> settingsProvider;

    private WebSocketSessionTracker tracker;

    @BeforeEach
    void setUp() {
        ServerSettingsProperties settings = new ServerSettingsProperties();
        settings.setMaxWebsocketSessionsPerUser(2);
        when(settingsProvider.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(settings);
        tracker = new WebSocketSessionTracker(settingsProvider);
    }

    @Test
    void registerSession_overCapacity_evictsOldestAndKeepsLatest() {
        tracker.registerSession(5L, "s1");
        tracker.registerSession(5L, "s2");
        List<String> evicted = tracker.registerSession(5L, "s3");

        assertEquals(List.of("s1"), evicted);
        assertEquals(List.of("s2", "s3"), tracker.getTrackedSessionIds(5L));
    }

    @Test
    void registerSession_existingSession_movesToNewestPosition() {
        tracker.registerSession(7L, "alpha");
        tracker.registerSession(7L, "beta");
        tracker.registerSession(7L, "alpha");

        assertEquals(List.of("beta", "alpha"), tracker.getTrackedSessionIds(7L));
    }

    @Test
    void unregisterSession_removesLastSessionAndClearsPresence() {
        tracker.registerSession(9L, "x");
        assertTrue(tracker.hasActiveSession(9L));

        tracker.unregisterSession(9L, "x");

        assertFalse(tracker.hasActiveSession(9L));
        assertEquals(List.of(), tracker.getTrackedSessionIds(9L));
    }
}
