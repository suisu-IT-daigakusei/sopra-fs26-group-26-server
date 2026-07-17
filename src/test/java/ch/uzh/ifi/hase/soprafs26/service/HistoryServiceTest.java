package ch.uzh.ifi.hase.soprafs26.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.repository.SessionHistoryQueryRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
public class HistoryServiceTest {
    
    @Mock
    private UserRepository userRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionHistoryQueryRepository sessionHistoryQueryRepository;

    @InjectMocks
    private HistoryService historyService;

    @Test
    public void requestSessionHistory_success() {

        // Setup Session
        Session testSession = new Session();
        testSession.setId(10L); // Internal ID is now a Long
        testSession.setSessionId("testSessionId"); // The string identifier
        
        // Add the user to the scores map so it passes the filter in HistoryService
        testSession.getTotalScoreByUserId().put(1L, 150); 

        // Mock repository behavior
        Mockito.when(sessionHistoryQueryRepository.findRecentSessionIdsForUser(1L, 200, 0))
                .thenReturn(List.of(10L));
        Mockito.when(sessionRepository.findAllById(List.of(10L))).thenReturn(List.of(testSession));
        Mockito.when(userRepository.existsById(1L)).thenReturn(true);

        // Execute service method
        List<Session> result = historyService.getUserSessionHistory(1L);

        // Assertions
        assertEquals(1, result.size());
        assertEquals(10L, result.get(0).getId());
        assertEquals("testSessionId", result.get(0).getSessionId());
    }

    @Test
    void getUserSessionHistory_userExists_returnsOnlyFilteredSessions() {
        // Setup: Create a session where User 1 participated
        Session session1 = new Session();
        session1.setId(11L);
        session1.setSessionId("session-1");
        session1.setTotalScoreByUserId(Map.of(1L, 50, 2L, 40)); 

        // Setup: Create a session where User 1 DID NOT participate
        Session session2 = new Session();
        session2.setSessionId("session-2");
        session2.setTotalScoreByUserId(Map.of(2L, 100, 3L, 20)); 

        // 2. Mock Repository Behavior
        Mockito.when(userRepository.existsById(1L)).thenReturn(true);
        
        // PostgreSQL applies the JSONB membership filter before returning rows.
        Mockito.when(sessionHistoryQueryRepository.findRecentSessionIdsForUser(1L, 200, 0))
                .thenReturn(List.of(11L));
        Mockito.when(sessionRepository.findAllById(List.of(11L))).thenReturn(List.of(session1));

        // 3. Action: Call the service method
        List<Session> history = historyService.getUserSessionHistory(1L);

        // 4. Assertion: Verify only session-1 was returned!
        assertNotNull(history);
        assertEquals(1, history.size(), "Should filter out sessions the user didn't play in");
        assertEquals("session-1", history.get(0).getSessionId());
    }

    @Test
    void getUserSessionHistory_userDoesNotExist_throwsNotFound() {
        // 1. Setup & Mock: Simulate a database that cannot find User 99
        Mockito.when(userRepository.existsById(99L)).thenReturn(false);

        // 2. Action & Assertion: Expect a 404 NOT FOUND
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            historyService.getUserSessionHistory(99L);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("User not found!", exception.getReason());
        
        Mockito.verifyNoInteractions(sessionHistoryQueryRepository, sessionRepository);
    }

    @Test
    void getUserSessionHistory_usesBoundedDatabaseQuery() {
        Session session = new Session();
        session.setId(15L);
        session.setSessionId("cached-session");
        session.setTotalScoreByUserId(Map.of(5L, 10));

        Mockito.when(userRepository.existsById(5L)).thenReturn(true);
        Mockito.when(sessionHistoryQueryRepository.findRecentSessionIdsForUser(5L, 200, 0))
                .thenReturn(List.of(15L));
        Mockito.when(sessionRepository.findAllById(List.of(15L))).thenReturn(List.of(session));

        List<Session> first = historyService.getUserSessionHistory(5L);
        List<Session> second = historyService.getUserSessionHistory(5L);

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals("cached-session", second.get(0).getSessionId());
        Mockito.verify(sessionHistoryQueryRepository, Mockito.times(2))
                .findRecentSessionIdsForUser(5L, 200, 0);
        Mockito.verify(sessionRepository, Mockito.times(2)).findAllById(List.of(15L));
    }

    @Test
    void getUserSessionHistory_preservesDatabaseOrdering() {
        Session newest = new Session();
        newest.setId(16L);
        newest.setSessionId("newest");
        newest.setStartTime(Instant.now());
        newest.setTotalScoreByUserId(Map.of(6L, 20));

        Session older = new Session();
        older.setId(17L);
        older.setSessionId("older");
        older.setStartTime(Instant.now().minusSeconds(120));
        older.setTotalScoreByUserId(Map.of(6L, 30));

        Mockito.when(userRepository.existsById(6L)).thenReturn(true);
        Mockito.when(sessionHistoryQueryRepository.findRecentSessionIdsForUser(6L, 200, 0))
                .thenReturn(List.of(16L, 17L));
        Mockito.when(sessionRepository.findAllById(List.of(16L, 17L)))
                .thenReturn(List.of(older, newest));

        List<Session> history = historyService.getUserSessionHistory(6L);

        assertEquals(2, history.size());
        assertEquals("newest", history.get(0).getSessionId());
        assertEquals("older", history.get(1).getSessionId());
    }

    @Test
    void getUserSessionHistory_threadsLimitAndOffset() {
        Mockito.when(userRepository.existsById(7L)).thenReturn(true);
        Mockito.when(sessionHistoryQueryRepository.findRecentSessionIdsForUser(7L, 25, 50))
                .thenReturn(List.of());

        assertTrue(historyService.getUserSessionHistory(7L, 25, 50).isEmpty());

        Mockito.verify(sessionHistoryQueryRepository).findRecentSessionIdsForUser(7L, 25, 50);
        Mockito.verifyNoInteractions(sessionRepository);
    }

    @Test
    void getUserSessionHistory_rejectsInvalidBoundsBeforeQuerying() {
        ResponseStatusException zeroLimit = assertThrows(
                ResponseStatusException.class,
                () -> historyService.getUserSessionHistory(7L, 0, 0));
        ResponseStatusException excessiveLimit = assertThrows(
                ResponseStatusException.class,
                () -> historyService.getUserSessionHistory(7L, 201, 0));
        ResponseStatusException negativeOffset = assertThrows(
                ResponseStatusException.class,
                () -> historyService.getUserSessionHistory(7L, 10, -1));
        ResponseStatusException excessiveOffset = assertThrows(
                ResponseStatusException.class,
                () -> historyService.getUserSessionHistory(7L, 10, 10_001));

        assertEquals(HttpStatus.BAD_REQUEST, zeroLimit.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, excessiveLimit.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, negativeOffset.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, excessiveOffset.getStatusCode());
        Mockito.verifyNoInteractions(sessionHistoryQueryRepository, sessionRepository);
    }
}
