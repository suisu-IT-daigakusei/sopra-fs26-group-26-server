package ch.uzh.ifi.hase.soprafs26.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
public class HistoryServiceTest {
    
    @Mock
    private UserRepository userRepository;

    @Mock
    private SessionRepository sessionRepository;

    @InjectMocks
    private HistoryService historyService;

    @Test
    public void requestSessionHistory_success() {

        // Setup User
        User testUser = new User();
        testUser.setId(1L);

        // Setup Session
        Session testSession = new Session();
        testSession.setId(10L); // Internal ID is now a Long
        testSession.setSessionId("testSessionId"); // The string identifier
        
        // Add the user to the scores map so it passes the filter in HistoryService
        testSession.getTotalScoreByUserId().put(1L, 150); 

        // Mock repository behavior
        Mockito.when(sessionRepository.findAll()).thenReturn(List.of(testSession));
        Mockito.when(userRepository.findById(any())).thenReturn(Optional.of(testUser));

        // Execute service method
        List<Session> result = historyService.getUserSessionHistory(1L);

        // Assertions
        assertEquals(1, result.size());
        assertEquals(10L, result.get(0).getId());
        assertEquals("testSessionId", result.get(0).getSessionId());
    }

    @Test
    void getUserSessionHistory_userExists_returnsOnlyFilteredSessions() {
        // 1. Setup: Create a mock user
        User mockUser = new User();
        mockUser.setId(1L);

        // Setup: Create a session where User 1 participated
        Session session1 = new Session();
        session1.setSessionId("session-1");
        session1.setTotalScoreByUserId(Map.of(1L, 50, 2L, 40)); 

        // Setup: Create a session where User 1 DID NOT participate
        Session session2 = new Session();
        session2.setSessionId("session-2");
        session2.setTotalScoreByUserId(Map.of(2L, 100, 3L, 20)); 

        // 2. Mock Repository Behavior
        Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));
        
        // Mock findAll to return BOTH sessions
        Mockito.when(sessionRepository.findAll()).thenReturn(List.of(session1, session2));

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
        Mockito.when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // 2. Action & Assertion: Expect a 404 NOT FOUND
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            historyService.getUserSessionHistory(99L);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("User not found!", exception.getReason());
        
        // Verify the system never tried to load all sessions from the database
        Mockito.verify(sessionRepository, Mockito.never()).findAll();
    }

    @Test
    void getUserSessionHistory_usesShortLivedCacheToAvoidDuplicateDbReads() {
        User mockUser = new User();
        mockUser.setId(5L);

        Session session = new Session();
        session.setSessionId("cached-session");
        session.setTotalScoreByUserId(Map.of(5L, 10));

        Mockito.when(userRepository.findById(5L)).thenReturn(Optional.of(mockUser));
        Mockito.when(sessionRepository.findAll()).thenReturn(List.of(session));

        List<Session> first = historyService.getUserSessionHistory(5L);
        List<Session> second = historyService.getUserSessionHistory(5L);

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals("cached-session", second.get(0).getSessionId());
        Mockito.verify(sessionRepository, Mockito.times(1)).findAll();
    }

    @Test
    void getUserSessionHistory_sortsDescendingAndSkipsMalformedSessions() {
        User mockUser = new User();
        mockUser.setId(6L);

        Session newest = new Session();
        newest.setSessionId("newest");
        newest.setStartTime(Instant.now());
        newest.setTotalScoreByUserId(Map.of(6L, 20));

        Session older = new Session();
        older.setSessionId("older");
        older.setStartTime(Instant.now().minusSeconds(120));
        older.setTotalScoreByUserId(Map.of(6L, 30));

        Session malformed = new Session();
        malformed.setSessionId("malformed");
        malformed.setStartTime(Instant.now().minusSeconds(60));
        malformed.setTotalScoreByUserId(null);

        Mockito.when(userRepository.findById(6L)).thenReturn(Optional.of(mockUser));
        Mockito.when(sessionRepository.findAll()).thenReturn(java.util.Arrays.asList(malformed, older, newest, null));

        List<Session> history = historyService.getUserSessionHistory(6L);

        assertEquals(2, history.size());
        assertEquals("newest", history.get(0).getSessionId());
        assertEquals("older", history.get(1).getSessionId());
    }
}
