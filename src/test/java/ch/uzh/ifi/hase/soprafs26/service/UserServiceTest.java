package ch.uzh.ifi.hase.soprafs26.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendOnlineSummaryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendRequestIncomingDTO;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UserServiceTest {

    // sum users' scores across rounds so sessions' totalScores match round history
    private static Map<Long, Integer> totalScoresFromRounds(List<Map<Long, Integer>> perRound) {
        Map<Long, Integer> totals = new HashMap<>();
        // if no input given - return empty map
        if (perRound == null) {
            return totals;
        }
        // get every round's map ("user id - round score" pairs) from list of rounds
        for (Map<Long, Integer> roundMap : perRound) {
            // skip invalid maps
            if (roundMap == null) {
                continue;
            }
            // iterate every "user id - round score" pair from current round's map
            for (Map.Entry<Long, Integer> e : roundMap.entrySet()) {
                Long userId = e.getKey();
                Integer score = e.getValue();
                // skip invalid "user id - round score" pairs
                if (userId == null || score == null) {
                    continue;
                }
                // add current round's score to user's total score
                totals.merge(userId, score, Integer::sum);
            }
        }
        return totals;
    }

    private static void setSessionRoundScoresWithMatchingTotalScores(Session session, List<Map<Long, Integer>> rounds) {
        session.setUserScoresPerRound(rounds);
        session.setTotalScoreByUserId(new HashMap<>(totalScoresFromRounds(rounds)));
    }

	@Mock
	private UserRepository userRepository;

	@Mock
	private LobbyRepository lobbyRepository;

	@Mock
	private SessionRepository sessionRepository;

	@Mock
	private OnlineUsersEventPublisher onlineUsersEventPublisher;

	@Mock
	private DisconnectService disconnectService;

	@InjectMocks
	private UserService userService;

	private User testUser;

	@BeforeEach
	public void setup() {
		MockitoAnnotations.openMocks(this);

		// given
		testUser = new User();
		testUser.setId(1L);
		testUser.setName("testName");
		testUser.setUsername("testUsername");
		testUser.setPassword("TestPassword#1");
		testUser.setCreationDate(LocalDate.now());

		// when -> any object is being save in the userRepository -> return the dummy
		// testUser
		Mockito.when(userRepository.save(Mockito.any())).thenReturn(testUser);
	}

	@Test
	public void createUser_validInputs_success() {
		// when -> any object is being save in the userRepository -> return the dummy
		// testUser
		User createdUser = userService.createUser(testUser);

		// then
		Mockito.verify(userRepository, Mockito.times(1)).save(Mockito.any());

		assertEquals(testUser.getId(), createdUser.getId());
		assertEquals(testUser.getName(), createdUser.getName());
		assertEquals(testUser.getUsername(), createdUser.getUsername());
        assertEquals(10, createdUser.getMusicVolume());
        assertEquals(30, createdUser.getSoundEffectsVolume());
		assertNotNull(createdUser.getToken());
		//assertEquals(UserStatus.OFFLINE, createdUser.getStatus());
	}

// this test is commented out because UserService.java says we allow duplicate names if the username differs
/*
	@Test
	public void createUser_duplicateName_throwsException() {
		// given -> a first user has already been created
		userService.createUser(testUser);

		// when -> setup additional mocks for UserRepository
		Mockito.when(userRepository.findByName(Mockito.any())).thenReturn(testUser);
		Mockito.when(userRepository.findByUsername(Mockito.any())).thenReturn(null);

		// then -> attempt to create second user with same user -> check that an error
		// is thrown
		assertThrows(ResponseStatusException.class, () -> userService.createUser(testUser));
	}
*/

	@Test
	public void createUser_duplicateInputs_throwsException() {
		// given -> a first user has already been created
		userService.createUser(testUser);

		// when -> setup additional mocks for UserRepository
		Mockito.when(userRepository.findByName(Mockito.any())).thenReturn(testUser);
		Mockito.when(userRepository.findByUsername(Mockito.any())).thenReturn(testUser);

		// then -> attempt to create second user with same user -> check that an error
		// is thrown
		assertThrows(ResponseStatusException.class, () -> userService.createUser(testUser));
	}

	@Test
	public void logoutUser_changesStatusToOffline_success() {
		User user = new User();
		user.setId(1L);
		user.setToken("token");
		user.setStatus(UserStatus.ONLINE);

		when(userRepository.findByToken("token")).thenReturn(user);
        when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L)).thenReturn(List.of());

		userService.logoutUser("token");
		
		assertEquals(UserStatus.OFFLINE, user.getStatus());
		verify(userRepository, Mockito.times(1)).save(user);
	}

    @Test
    public void logoutUser_whileInPlayingLobby_throwsConflict() {
        User user = new User();
        user.setId(7L);
        user.setToken("token");
        user.setStatus(UserStatus.PLAYING);

        Lobby playingLobby = new Lobby();
        playingLobby.setStatus("PLAYING");
        playingLobby.setPlayerIds(List.of(7L, 8L));

        when(userRepository.findByToken("token")).thenReturn(user);
        when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 7L)).thenReturn(List.of(playingLobby));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> userService.logoutUser("token"));
        assertEquals(409, ex.getStatusCode().value());
        verify(userRepository, Mockito.never()).save(user);
    }

	@Test
    public void createUser_usernameTooLong_throwsBadRequest() {
        // 1. GIVEN: A user object with a 17-character username
        User newRestrictedUser = new User();
        newRestrictedUser.setUsername("12345678901234567"); // 17 chars!
        newRestrictedUser.setPassword("SecurePass#1");

        // 2. WHEN / THEN: Attempting to save it throws an error
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, 
            () -> userService.createUser(newRestrictedUser)); // (Adjust method name if needed)

        // Verify the server rejects it with a 400 Bad Request
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        
        // Verify the database was never touched
        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    public void createUser_usernameContainsNonAlnum_throwsBadRequest() {
        User user = new User();
        user.setUsername("bad_name");
        user.setPassword("ValidPass#1");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> userService.createUser(user));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    public void createUser_passwordWithoutUppercase_throwsBadRequest() {
        User user = new User();
        user.setUsername("validuser");
        user.setPassword("validpass#1");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> userService.createUser(user));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    public void createUser_passwordWithSpace_throwsBadRequest() {
        User user = new User();
        user.setUsername("validuser");
        user.setPassword("Valid #123");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> userService.createUser(user));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    public void updateUser_invalidPassword_throwsBadRequestAndDoesNotPersist() {
        User existing = new User();
        existing.setId(42L);
        existing.setUsername("user42");
        existing.setPassword("ValidPass#1");

        when(userRepository.findById(42L)).thenReturn(Optional.of(existing));

        User updatePayload = new User();
        updatePayload.setPassword("short#1");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> userService.updateUser(42L, updatePayload));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        Mockito.verify(userRepository, Mockito.never()).save(existing);
    }

    @Test
    public void loginUser_blankUsername_throwsUnauthorized() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> userService.loginUser("   ", "ValidPass#1"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        Mockito.verify(userRepository, Mockito.never()).findByUsername(Mockito.anyString());
    }

    @Test
    public void loginUser_trimmedUsername_usedForLookup() {
        User existing = new User();
        existing.setId(77L);
        existing.setUsername("trimUser");
        existing.setPassword("ValidPass#1");
        existing.setStatus(UserStatus.OFFLINE);

        when(userRepository.findByUsername("trimUser")).thenReturn(existing);
        when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 77L)).thenReturn(List.of());
        when(lobbyRepository.findByStatusAndParticipantId("WAITING", 77L)).thenReturn(List.of());

        User loggedIn = userService.loginUser("  trimUser  ", "ValidPass#1");

        assertEquals("trimUser", loggedIn.getUsername());
        assertEquals(UserStatus.ONLINE, loggedIn.getStatus());
        Mockito.verify(userRepository, Mockito.times(1)).findByUsername("trimUser");
        Mockito.verify(userRepository, Mockito.times(1)).save(existing);
    }

    @Test
    public void heartbeat_offlineUserWithoutLobby_setsOnline() {
        User user = new User();
        user.setId(10L);
        user.setToken("token-online");
        user.setStatus(UserStatus.OFFLINE);
        user.setLastHeartbeat(Instant.now());

        when(userRepository.findByToken("token-online")).thenReturn(user);
        when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 10L)).thenReturn(List.of());
        when(lobbyRepository.findByStatusAndParticipantId("WAITING", 10L)).thenReturn(List.of());

        userService.heartbeat("token-online");

        assertEquals(UserStatus.ONLINE, user.getStatus());
        verify(userRepository, Mockito.atLeastOnce()).save(user);
        verify(onlineUsersEventPublisher, Mockito.times(1)).broadcastOnlineUsers();
        verify(disconnectService, Mockito.times(1)).handleReconnect(10L);
    }

    @Test
    public void heartbeat_offlineUserInWaitingLobby_setsLobby() {
        User user = new User();
        user.setId(11L);
        user.setToken("token-lobby");
        user.setStatus(UserStatus.OFFLINE);
        user.setLastHeartbeat(Instant.now());

        when(userRepository.findByToken("token-lobby")).thenReturn(user);
        when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 11L)).thenReturn(List.of());
        Lobby waitingLobby = new Lobby();
        waitingLobby.setPlayerIds(List.of(11L, 1L));
        when(lobbyRepository.findByStatusAndParticipantId("WAITING", 11L)).thenReturn(List.of(waitingLobby));

        userService.heartbeat("token-lobby");

        assertEquals(UserStatus.LOBBY, user.getStatus());
        verify(userRepository, Mockito.atLeastOnce()).save(user);
        verify(onlineUsersEventPublisher, Mockito.times(1)).broadcastOnlineUsers();
        verify(disconnectService, Mockito.times(1)).handleReconnect(11L);
    }

    @Test
    public void heartbeat_offlineUserInPlayingLobby_setsPlaying() {
        User user = new User();
        user.setId(12L);
        user.setToken("token-playing");
        user.setStatus(UserStatus.OFFLINE);
        user.setLastHeartbeat(Instant.now());

        when(userRepository.findByToken("token-playing")).thenReturn(user);
        Lobby playingLobby = new Lobby();
        playingLobby.setPlayerIds(List.of(12L, 1L));
        when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 12L)).thenReturn(List.of(playingLobby));

        userService.heartbeat("token-playing");

        assertEquals(UserStatus.PLAYING, user.getStatus());
        verify(userRepository, Mockito.atLeastOnce()).save(user);
        verify(onlineUsersEventPublisher, Mockito.times(1)).broadcastOnlineUsers();
        verify(disconnectService, Mockito.times(1)).handleReconnect(12L);
    }

    // #116 heartbeat method in user service sets SPECTATING status to a user based on their id
    // logout allowed when not in playerIds of PLAYING lobby 
    @Test
    public void spectator_heartbeatSetsSpectatingStatus_logoutAllowedForSpectator() {
        User user = new User();
        user.setId(13L);
        user.setToken("t13");
        user.setStatus(UserStatus.OFFLINE);

        Lobby waitingLobby = new Lobby();
        waitingLobby.setPlayerIds(List.of(1L));
        waitingLobby.setSpectatorIds(new ArrayList<>(List.of(13L)));

        when(userRepository.findByToken("t13")).thenReturn(user);
        // return empty list for playing lobbies and user with id 13
        when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 13L)).thenReturn(List.of());
        // return the waiting lobby for same user
        when(lobbyRepository.findByStatusAndParticipantId("WAITING", 13L)).thenReturn(List.of(waitingLobby));
        // heartbeat will set the SPECTATING status based on id
        userService.heartbeat("t13");
        assertEquals(UserStatus.SPECTATING, user.getStatus());

        User userLogsOut = new User();
        userLogsOut.setId(9L);
        userLogsOut.setToken("t9");
        userLogsOut.setStatus(UserStatus.SPECTATING);

        Lobby playingLobby = new Lobby();
        playingLobby.setPlayerIds(List.of(1L, 2L));
        playingLobby.setSpectatorIds(new ArrayList<>(List.of(9L)));

        when(userRepository.findByToken("t9")).thenReturn(userLogsOut);
        when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 9L)).thenReturn(List.of(playingLobby));
        // log out spectator 
        userService.logoutUser("t9");
        // log out worked 
        assertEquals(UserStatus.OFFLINE, userLogsOut.getStatus());
        // logged out user was persisted
        verify(userRepository, Mockito.times(1)).save(userLogsOut);
    }

    // in this test we set scores and respective totals directly 
    // because setting scores and inferring totals from them in gameplay flow is in the scope of another task
    @Test
    public void getUsers_recalculateRankingFromEndedSessions_updatesWinsAverageAndRank() {
        User u1 = new User();
        u1.setId(1L);
        u1.setUsername("u1");
        u1.setPassword("pw");
        u1.setCreationDate(LocalDate.now());

        User u2 = new User();
        u2.setId(2L);
        u2.setUsername("u2");
        u2.setPassword("pw");
        u2.setCreationDate(LocalDate.now());

        User u3 = new User();
        u3.setId(3L);
        u3.setUsername("u3");
        u3.setPassword("pw");
        u3.setCreationDate(LocalDate.now());

        // when we query repository for all users, return the 3 users
        when(userRepository.findAll()).thenReturn(new ArrayList<>(List.of(u1, u2, u3)));
        // when we save a user via repository - return the user that is passed as argument
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Session ended1 = new Session();
        ended1.setEnded(true);
        List<Map<Long, Integer>> ended1Rounds = new ArrayList<>();
        // first round. user1: 10; user2: 60. user1 wins
        ended1Rounds.add(new HashMap<>(Map.of(1L, 10, 2L, 60)));
        // second round. user1: 10; user2: -10 (100 -> 50 rule). user2 wins
        ended1Rounds.add(new HashMap<>(Map.of(1L, 10, 2L, -10)));
        // total scores. user1: 20; user2: 50. user1 wins session/game
        setSessionRoundScoresWithMatchingTotalScores(ended1, ended1Rounds);

        // second session
        Session ended2 = new Session();
        ended2.setEnded(true);
        List<Map<Long, Integer>> ended2Rounds = new ArrayList<>();
        // one round only. user1: 40; user2: 5; user3: 25. user2 wins round and session
        ended2Rounds.add(new HashMap<>(Map.of(1L, 40, 2L, 5, 3L, 25)));
        setSessionRoundScoresWithMatchingTotalScores(ended2, ended2Rounds);

        // third session, not ended
        Session open = new Session();
        open.setEnded(false);
        // no rounds played yet - empty scores
        open.setTotalScoreByUserId(new HashMap<>());

        // when session repository is queried for all sessions - return 3 created sessions
        when(sessionRepository.findAll()).thenReturn(List.of(ended1, ended2, open));
        // getUsers() calls recalculateGlobalRankingFromSessions()
        List<User> users = userService.getUsers();

        assertEquals(3, users.size());
        assertEquals(1, u1.getGamesWon());
        assertEquals(1, u2.getGamesWon());
        assertEquals(0, u3.getGamesWon());
        assertEquals(30, u1.getAverageScorePerSession());
        assertEquals(28, u2.getAverageScorePerSession());
        assertEquals(25, u3.getAverageScorePerSession());
        assertEquals(1, u1.getRoundsWon());
        assertEquals(2, u2.getRoundsWon());
        assertEquals(0, u3.getRoundsWon());
        assertEquals(20, u1.getAverageScorePerRound());
        assertEquals(18, u2.getAverageScorePerRound());
        assertEquals(25, u3.getAverageScorePerRound());
        // user1 and user2 have same number of games won
        // but user2 has a lower average score per session
        assertEquals(2, u1.getOverallRank());
        assertEquals(1, u2.getOverallRank());
        assertEquals(3, u3.getOverallRank());
    }

    // user3 joins session during 2nd round (round index=1)
    // average score / win stats over rounds and sessions are consistent 
    // (if user is missing in a round, that round is ignored for that user)
    @Test
    public void getUsers_userJoinsDuringSecondRound_roundAndSessionMetricsAreCorrect() {
        User u1 = new User();
        u1.setId(1L);
        u1.setUsername("u1");
        u1.setPassword("pw");
        u1.setCreationDate(LocalDate.now());

        User u2 = new User();
        u2.setId(2L);
        u2.setUsername("u2");
        u2.setPassword("pw");
        u2.setCreationDate(LocalDate.now());

        User u3 = new User();
        u3.setId(3L);
        u3.setUsername("u3");
        u3.setPassword("pw");
        u3.setCreationDate(LocalDate.now());

        // when we query repository for all users, return the 3 users
        when(userRepository.findAll()).thenReturn(new ArrayList<>(List.of(u1, u2, u3)));
        // when we save a user via repository - return the user that is passed as argument
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Session ended = new Session();
        ended.setEnded(true);
        List<Map<Long, Integer>> rounds = new ArrayList<>();
        // round1. user1: 10; user2: 20
        // user1 wins round
        rounds.add(new HashMap<>(Map.of(1L, 10, 2L, 20)));
        // round2. user1: 15; user2: 10; user3: 5
        // user3 wins round and session
        rounds.add(new HashMap<>(Map.of(1L, 15, 2L, 10, 3L, 5)));
        setSessionRoundScoresWithMatchingTotalScores(ended, rounds);

        // when querying session repository for sessions, return created session
        when(sessionRepository.findAll()).thenReturn(List.of(ended));
        // getUsers() calls recalculateGlobalRankingFromSessions()
        userService.getUsers();

        assertEquals(0, u1.getGamesWon());
        assertEquals(0, u2.getGamesWon());
        assertEquals(1, u3.getGamesWon());
        assertEquals(25, u1.getAverageScorePerSession());
        assertEquals(30, u2.getAverageScorePerSession());
        assertEquals(5, u3.getAverageScorePerSession());

        assertEquals(1, u1.getRoundsWon());
        assertEquals(0, u2.getRoundsWon());
        assertEquals(1, u3.getRoundsWon());
        assertEquals(13, u1.getAverageScorePerRound());
        assertEquals(15, u2.getAverageScorePerRound());
        assertEquals(5, u3.getAverageScorePerRound());

        // user3 won 1 game -> rank 1
        // user1 and user2 won 0 games
        // user1 has lower session average than user2
        assertEquals(2, u1.getOverallRank());
        assertEquals(3, u2.getOverallRank());
        assertEquals(1, u3.getOverallRank());
    }

    @Test
    public void getUsers_ongoingSessionRoundsAffectRoundBasedMetricsOnly_notSessionBasedMetrics() {
        User u1 = new User();
        u1.setId(1L);
        u1.setUsername("u1");
        u1.setPassword("pw");
        u1.setCreationDate(LocalDate.now());

        User u2 = new User();
        u2.setId(2L);
        u2.setUsername("u2");
        u2.setPassword("pw");
        u2.setCreationDate(LocalDate.now());

        // when we query repository for all users, return the 2 users
        when(userRepository.findAll()).thenReturn(new ArrayList<>(List.of(u1, u2)));
        // when we save a user via repository - return the user that is passed as argument
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // a session that has not ended yet
        Session ongoing = new Session();
        ongoing.setEnded(false);
        List<Map<Long, Integer>> rounds = new ArrayList<>();
        // round1. user1: 4; user2: 10
        // user1 wins round but not session (since its ongoing)
        rounds.add(new HashMap<>(Map.of(1L, 4, 2L, 10)));
        setSessionRoundScoresWithMatchingTotalScores(ongoing, rounds);

        // when querying session repository for sessions, return created session
        when(sessionRepository.findAll()).thenReturn(List.of(ongoing));
        // getUsers() calls recalculateGlobalRankingFromSessions()
        userService.getUsers();

        // neither user has won the game - session has not ended yet
        assertEquals(0, u1.getGamesWon());
        assertEquals(0, u2.getGamesWon());
        assertEquals(0, u1.getAverageScorePerSession());
        assertEquals(0, u2.getAverageScorePerSession());
        // round based metrics can be computed from an ongoing session
        assertEquals(1, u1.getRoundsWon());
        assertEquals(0, u2.getRoundsWon());
        assertEquals(4, u1.getAverageScorePerRound());
        assertEquals(10, u2.getAverageScorePerRound());
    }

    // #109: PUT applies isPublicLog when present and leaves it untouched when null
    @Test
    public void updateUser_isPublicLog_setsWhenProvidedAndIgnoresWhenNull() {
        User user = new User();
        user.setId(42L);
        user.setUsername("u42");
        user.setIsPublicLog(false);

        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User userChangeToPublic = new User();
        userChangeToPublic.setIsPublicLog(true);
        // old user object updated with attribute values from new one
        userService.updateUser(42L, userChangeToPublic);
        assertEquals(true, user.getIsPublicLog());

        User passwordOnly = new User();
        passwordOnly.setPassword("NewPass#1");
        passwordOnly.setIsPublicLog(null); // simulate what UserPutDTO->User mapping produces
        // old user object updated with attribute values from new one
        userService.updateUser(42L, passwordOnly);
        assertEquals(true, user.getIsPublicLog());
        assertEquals("NewPass#1", user.getPassword());
    }

    @Test
    public void getUsers_noRoundsAndEndedSessions_setsAllScoreStatsFieldsToZeroExceptRank() {
        User u1 = new User();
        u1.setId(1L);
        u1.setUsername("u1");
        u1.setPassword("pw");
        u1.setCreationDate(LocalDate.now());

        User u2 = new User();
        u2.setId(2L);
        u2.setUsername("u2");
        u2.setPassword("pw");
        u2.setCreationDate(LocalDate.now());

        // when we query repository for all users, return the 2 users
        when(userRepository.findAll()).thenReturn(new ArrayList<>(List.of(u1, u2)));
        // when we save a user via repository - return the user that is passed as argument
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // session that has not ended yet
        Session open = new Session();
        open.setEnded(false);
        // no rounds, empty total scores
        open.setTotalScoreByUserId(new HashMap<>());
        
        // when querying session repository for sessions, return created session
        when(sessionRepository.findAll()).thenReturn(List.of(open));
        // getUsers() calls recalculateGlobalRankingFromSessions()
        userService.getUsers();

        // all metrics equal to 0
        assertEquals(0, u1.getGamesWon());
        assertEquals(0, u2.getGamesWon());
        assertEquals(0, u1.getAverageScorePerSession());
        assertEquals(0, u2.getAverageScorePerSession());
        assertEquals(0, u1.getRoundsWon());
        assertEquals(0, u2.getRoundsWon());
        assertEquals(0, u1.getAverageScorePerRound());
        assertEquals(0, u2.getAverageScorePerRound());
        // ranking falls back to a ranking based on user ids
        assertEquals(1, u1.getOverallRank());
        assertEquals(2, u2.getOverallRank());
    }

    @Test
    public void getAcceptedFriendIds_returnsOnlyMutualFriends() {
        User me = new User();
        me.setId(1L);
        me.setFriendUserIds(new ArrayList<>(List.of(2L, 3L)));

        User friendAccepted = new User();
        friendAccepted.setId(2L);
        friendAccepted.setFriendUserIds(new ArrayList<>(List.of(1L)));

        User friendPending = new User();
        friendPending.setId(3L);
        friendPending.setFriendUserIds(new ArrayList<>(List.of()));

        when(userRepository.findByToken("token")).thenReturn(me);
        when(userRepository.findAllById(Mockito.anyCollection())).thenReturn(List.of(friendAccepted, friendPending));

        List<Long> ids = userService.getAcceptedFriendIds("token");

        assertEquals(List.of(2L), ids);
    }

    @Test
    public void getIncomingFriendRequests_returnsOnlyIncomingNonMutualRequests() {
        User me = new User();
        me.setId(1L);
        me.setFriendUserIds(new ArrayList<>(List.of(4L))); // outgoing to 4, not incoming

        User requesterA = new User();
        requesterA.setId(2L);
        requesterA.setUsername("alice");
        requesterA.setFriendUserIds(new ArrayList<>(List.of(1L)));

        User requesterB = new User();
        requesterB.setId(3L);
        requesterB.setUsername("bob");
        requesterB.setFriendUserIds(new ArrayList<>(List.of(1L)));

        User outgoingOnly = new User();
        outgoingOnly.setId(4L);
        outgoingOnly.setUsername("charlie");
        outgoingOnly.setFriendUserIds(new ArrayList<>());

        User unrelated = new User();
        unrelated.setId(5L);
        unrelated.setUsername("dora");
        unrelated.setFriendUserIds(new ArrayList<>(List.of(9L)));

        when(userRepository.findByToken("token")).thenReturn(me);
        when(userRepository.findAll()).thenReturn(List.of(requesterB, outgoingOnly, unrelated, requesterA, me));

        List<FriendRequestIncomingDTO> incoming = userService.getIncomingFriendRequests("token");

        assertEquals(2, incoming.size());
        assertEquals(2L, incoming.get(0).getRequesterUserId());
        assertEquals("alice", incoming.get(0).getRequesterUsername());
        assertEquals(3L, incoming.get(1).getRequesterUserId());
        assertEquals("bob", incoming.get(1).getRequesterUsername());
    }

    @Test
    public void getOutgoingPendingFriendRequestIds_returnsOnlyNonMutualSelections() {
        User me = new User();
        me.setId(1L);
        me.setFriendUserIds(new ArrayList<>(List.of(2L, 3L, 4L)));

        User acceptedA = new User();
        acceptedA.setId(2L);
        acceptedA.setFriendUserIds(new ArrayList<>(List.of(1L)));

        User outgoingOnly = new User();
        outgoingOnly.setId(3L);
        outgoingOnly.setFriendUserIds(new ArrayList<>());

        User acceptedB = new User();
        acceptedB.setId(4L);
        acceptedB.setFriendUserIds(new ArrayList<>(List.of(1L)));

        when(userRepository.findByToken("token")).thenReturn(me);
        when(userRepository.findAllById(Mockito.anyCollection())).thenReturn(List.of(acceptedA, outgoingOnly, acceptedB));

        List<Long> pending = userService.getOutgoingPendingFriendRequestIds("token");

        assertEquals(List.of(3L), pending);
    }

    @Test
    public void getFriendOnlineSummary_countsOnlyMutualFriendsByStatus() {
        User me = new User();
        me.setId(1L);
        me.setFriendUserIds(new ArrayList<>(List.of(2L, 3L, 4L, 5L, 6L)));

        User online = new User();
        online.setId(2L);
        online.setStatus(UserStatus.ONLINE);
        online.setFriendUserIds(new ArrayList<>(List.of(1L)));

        User playing = new User();
        playing.setId(3L);
        playing.setStatus(UserStatus.PLAYING);
        playing.setFriendUserIds(new ArrayList<>(List.of(1L)));

        User lobby = new User();
        lobby.setId(4L);
        lobby.setStatus(UserStatus.LOBBY);
        lobby.setFriendUserIds(new ArrayList<>(List.of(1L)));

        User pending = new User();
        pending.setId(5L);
        pending.setStatus(UserStatus.ONLINE);
        pending.setFriendUserIds(new ArrayList<>());

        User spectating = new User();
        spectating.setId(6L);
        spectating.setStatus(UserStatus.SPECTATING);
        spectating.setFriendUserIds(new ArrayList<>(List.of(1L)));

        when(userRepository.findByToken("token")).thenReturn(me);
        when(userRepository.findAllById(Mockito.anyCollection())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            Map<Long, User> usersById = Map.of(
                    2L, online,
                    3L, playing,
                    4L, lobby,
                    5L, pending,
                    6L, spectating);
            List<User> resolved = new ArrayList<>();
            for (Long id : ids) {
                User resolvedUser = usersById.get(id);
                if (resolvedUser != null) {
                    resolved.add(resolvedUser);
                }
            }
            return resolved;
        });

        FriendOnlineSummaryDTO summary = userService.getFriendOnlineSummary("token");

        assertEquals(4, summary.getFriendsOnline());
        assertEquals(1, summary.getPlaying());
        assertEquals(1, summary.getLobby());
        assertEquals(1, summary.getSpectating());
    }

    @Test
    public void sendFriendRequest_addsTargetToOwnSelection() {
        User me = new User();
        me.setId(1L);
        me.setFriendUserIds(new ArrayList<>(List.of(3L)));

        User target = new User();
        target.setId(2L);

        when(userRepository.findByToken("token")).thenReturn(me);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.sendFriendRequest("token", 2L);

        assertEquals(List.of(2L, 3L), me.getFriendUserIds());
        verify(userRepository, Mockito.atLeastOnce()).save(me);
        verify(userRepository, Mockito.atLeastOnce()).flush();
    }

    @Test
    public void acceptFriendRequest_withoutIncomingRequest_throwsConflict() {
        User me = new User();
        me.setId(1L);
        me.setFriendUserIds(new ArrayList<>());

        User requester = new User();
        requester.setId(2L);
        requester.setFriendUserIds(new ArrayList<>());

        when(userRepository.findByToken("token")).thenReturn(me);
        when(userRepository.findById(2L)).thenReturn(Optional.of(requester));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> userService.acceptFriendRequest("token", 2L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(userRepository, Mockito.never()).save(me);
    }

    @Test
    public void acceptFriendRequest_withIncomingRequest_addsRequesterToOwnSelection() {
        User me = new User();
        me.setId(1L);
        me.setFriendUserIds(new ArrayList<>());

        User requester = new User();
        requester.setId(2L);
        requester.setFriendUserIds(new ArrayList<>(List.of(1L)));

        when(userRepository.findByToken("token")).thenReturn(me);
        when(userRepository.findById(2L)).thenReturn(Optional.of(requester));
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.acceptFriendRequest("token", 2L);

        assertEquals(List.of(2L), me.getFriendUserIds());
        verify(userRepository, Mockito.atLeastOnce()).save(me);
        verify(userRepository, Mockito.atLeastOnce()).flush();
    }

    @Test
    public void removeFriendOrRequest_removesEntryFromBothSides() {
        User me = new User();
        me.setId(1L);
        me.setFriendUserIds(new ArrayList<>(List.of(2L, 3L)));

        User other = new User();
        other.setId(2L);
        other.setFriendUserIds(new ArrayList<>(List.of(1L, 9L)));

        when(userRepository.findByToken("token")).thenReturn(me);
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.removeFriendOrRequest("token", 2L);

        assertEquals(List.of(3L), me.getFriendUserIds());
        assertEquals(List.of(9L), other.getFriendUserIds());
        verify(userRepository, Mockito.atLeastOnce()).save(me);
        verify(userRepository, Mockito.atLeastOnce()).save(other);
        verify(userRepository, Mockito.atLeastOnce()).flush();
    }

}
