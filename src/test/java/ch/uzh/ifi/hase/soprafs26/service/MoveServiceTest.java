package ch.uzh.ifi.hase.soprafs26.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

import ch.uzh.ifi.hase.soprafs26.entity.Move;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.MoveRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.MoveLogEntryDTO;

public class MoveServiceTest {

    @Mock private MoveRepository moveRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private MoveService moveService;

    private static final String SESSION_ID = "S-1";
    private static final String TOKEN_REQUESTER = "token-requester";

    private User requester;
    private Session session;

    // set up for each test
    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);

        requester = new User();
        requester.setId(1L);
        requester.setUsername("me");
        requester.setToken(TOKEN_REQUESTER);

        session = new Session();
        session.setSessionId(SESSION_ID);
        // totalScores map will determine what users are part of this session, based on user ids
        Map<Long, Integer> totalScores = new HashMap<>();
        totalScores.put(1L, 0);
        totalScores.put(2L, 0);
        totalScores.put(3L, 0);
        session.setTotalScoreByUserId(totalScores);
    }

    // helper move object
    private static Move move(Long userId, String action, Boolean isPublic, long timeStamp) {
        Move m = new Move();
        m.setUserId(userId);
        m.setSessionId(SESSION_ID);
        m.setActionType(action);
        m.setIsPublic(isPublic);
        m.setTimestamp(Instant.ofEpochSecond(timeStamp));
        return m;
    }

    // #111 requester's own moves + others' public moves are returned in correct order
    // others' private moves are skipped. ownMove flag is set correctly
    @Test
    public void getSessionLog_returnsOwnAndOthersPublicMoves_hidesOthersPrivateMoves() {
        Move ownPrivateMove = move(1L, "DRAW", false, 100);
        Move ownPublicMove = move(1L, "PEEK", true, 110);
        Move opponentPublicMove = move(2L, "SWAP", true, 120);
        Move opponentPrivateMove = move(3L, "CABO", false, 130);

        // mock repository return values
        when(userRepository.findByToken(TOKEN_REQUESTER)).thenReturn(requester);
        when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(session);
        when(moveRepository.findTop500BySessionIdOrderByTimestampDesc(SESSION_ID))
                .thenReturn(List.of(opponentPrivateMove, opponentPublicMove, ownPublicMove, ownPrivateMove));

        User opponent = new User();
        opponent.setId(2L);
        opponent.setUsername("opponent");
        // mock repository's return of all users
        when(userRepository.findAllById(org.mockito.ArgumentMatchers.anyIterable()))
                .thenReturn(List.of(requester, opponent));

        List<MoveLogEntryDTO> log = moveService.getSessionLog(SESSION_ID, TOKEN_REQUESTER);

        // we see 2 own moves and 1 public move of an opponent
        assertEquals(3, log.size());

        assertEquals(1L, log.get(0).getUserId());
        assertEquals("DRAW", log.get(0).getActionType());
        assertEquals(true, log.get(0).getOwnMove());
        assertEquals("me", log.get(0).getUsername());

        assertEquals(1L, log.get(1).getUserId());
        assertEquals("PEEK", log.get(1).getActionType());
        assertEquals(true, log.get(1).getOwnMove());

        assertEquals(2L, log.get(2).getUserId());
        assertEquals("SWAP", log.get(2).getActionType());
        assertEquals(false, log.get(2).getOwnMove());
        assertEquals("opponent", log.get(2).getUsername());
    }

    // #111 requester must be a session participant; invalid tokens / sessions / players are rejected
    @Test
    public void getSessionLog_authenticationChecks() {
        // mock user repository to return null for bad token
        when(userRepository.findByToken("bad_token")).thenReturn(null);
        // validate exception is thrown for bad token and save exception object for further validation
        ResponseStatusException unauthorized = assertThrows(ResponseStatusException.class,
                () -> moveService.getSessionLog(SESSION_ID, "bad_token"));
        // check status code of exception
        assertEquals(401, unauthorized.getStatusCode().value());

        // mock user repository returning the requester
        when(userRepository.findByToken(TOKEN_REQUESTER)).thenReturn(requester);
        // mock session repository to return null for missing session
        when(sessionRepository.findBySessionId("missing_session")).thenReturn(null);
        // validate exception is thrown for bad session and save exception object for further validation
        ResponseStatusException notFound = assertThrows(ResponseStatusException.class,
                () -> moveService.getSessionLog("missing_session", TOKEN_REQUESTER));
        // check status code of exception
        assertEquals(404, notFound.getStatusCode().value());

        User outsider = new User();
        outsider.setId(99L);
        outsider.setUsername("outsider");
        outsider.setToken("token-outsider");
        // mock repositories to return existing user and session
        when(userRepository.findByToken("token-outsider")).thenReturn(outsider);
        when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(session);
        // validate exception is thrown because user is not part of this session; save exception object for further validation
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> moveService.getSessionLog(SESSION_ID, "token-outsider"));
        // validate exception code
        assertEquals(403, forbidden.getStatusCode().value());
    }

    @Test
    public void cleanupStaleSessionMovesJob_deletesMovesForOldEndedSessions() {
        when(moveRepository.deleteStaleEndedSessionMovesBatch(any(), anyInt())).thenReturn(12);

        moveService.cleanupStaleSessionMovesJob();

        verify(moveRepository).deleteStaleEndedSessionMovesBatch(any(), anyInt());
    }

    @Test
    public void cleanupStaleSessionMovesJob_hasEffectiveTransactionalBoundary() throws Exception {
        assertNotNull(MoveService.class
                .getDeclaredMethod("cleanupStaleSessionMovesJob")
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }

    @Test
    public void cleanupStaleSessionMovesJob_fullBatchesContinuesUntilPartialBatch() {
        when(moveRepository.deleteStaleEndedSessionMovesBatch(any(), anyInt()))
                .thenReturn(1000, 1000, 7);

        moveService.cleanupStaleSessionMovesJob();

        verify(moveRepository, times(3)).deleteStaleEndedSessionMovesBatch(any(), anyInt());
    }
}
