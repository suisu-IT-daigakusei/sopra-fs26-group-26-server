package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.is;

class SessionControllerTest {

    private MockMvc mockMvc;
    private SessionRepository sessionRepository;
    private UserRepository userRepository;

    @BeforeEach
    void setup() {
        // Manually mock the repositories
        sessionRepository = Mockito.mock(SessionRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        
        // Inject the mocked repositories directly into the controller
        SessionController sessionController = new SessionController(sessionRepository, userRepository);
        
        // Build the standalone MockMvc environment
        mockMvc = MockMvcBuilders.standaloneSetup(sessionController).build();
    }

    @Test
    void getSessionHistory_authenticatedUser_returnsHistory() throws Exception {
        // 1. Setup
        User mockUser = new User();
        mockUser.setId(7L);
        mockUser.setToken("valid-token");
        
        Session mockSession = new Session();
        mockSession.setSessionId("session-123");
        mockSession.setTotalScoreByUserId(Map.of(7L, 42));

        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(mockUser);
        Mockito.when(sessionRepository.findBySessionId("session-123")).thenReturn(mockSession);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/sessions/session-123/history")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()) // 200 OK
               .andExpect(jsonPath("$.sessionId", is("session-123"))); 
    }

    @Test
    void getSessionHistory_authenticatedNonParticipant_returnsHistory() throws Exception {
        User authenticatedUser = new User();
        authenticatedUser.setId(99L);

        Session session = new Session();
        session.setSessionId("session-123");
        session.setTotalScoreByUserId(Map.of(7L, 42));

        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(authenticatedUser);
        Mockito.when(sessionRepository.findBySessionId("session-123")).thenReturn(session);

        mockMvc.perform(get("/sessions/session-123/history")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.sessionId", is("session-123")));
    }

    @Test
    void getSessionHistory_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup: The user repository cannot find the token (returns null)
        Mockito.when(userRepository.findByToken("bad-token")).thenReturn(null);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/sessions/session-123/history")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
               
        // Verify we never even tried to look up the session because the token failed first
        Mockito.verify(sessionRepository, Mockito.never()).findBySessionId(Mockito.anyString());
    }

    @Test
    void getSessionHistory_missingToken_throwsUnauthorized() throws Exception {
        mockMvc.perform(get("/sessions/session-123/history"))
               .andExpect(status().isUnauthorized());

        Mockito.verifyNoInteractions(userRepository, sessionRepository);
    }

    @Test
    void getSessionHistory_sessionNotFound_throwsNotFound() throws Exception {
        // 1. Setup: The token is valid, but the session doesn't exist
        User mockUser = new User();
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(mockUser);
        Mockito.when(sessionRepository.findBySessionId("missing-session")).thenReturn(null);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/sessions/missing-session/history")
                .header("Authorization", "valid-token"))
               .andExpect(status().isNotFound()); // 404 NOT FOUND
    }
}
