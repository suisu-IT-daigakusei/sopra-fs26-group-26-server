package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.config.settings.LobbySettingsProperties;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.GameRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbySettingsPatchDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.WaitingLobbyViewDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class LobbyServiceTest {

	@Mock
	private LobbyRepository lobbyRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private GameRepository gameRepository;

	@Mock
	private LobbyEventPublisher lobbyEventPublisher;

	@Mock
	private OnlineUsersEventPublisher onlineUsersEventPublisher;

	@Mock
	private DisconnectService disconnectService;

	@Mock
	private GameService gameService;

	@Mock
	private LobbySettingsProperties lobbySettingsProperties;

	@Mock
	private LobbyChatService lobbyChatService;

	@InjectMocks
	private LobbyService lobbyService;

	@BeforeEach
	public void setup() {
		MockitoAnnotations.openMocks(this);
		Mockito.when(gameRepository.findGamesByPlayerId(Mockito.anyLong())).thenReturn(List.of());
		Mockito.when(lobbySettingsProperties.getAfkTimeoutDefaultSeconds()).thenReturn(300L);
		Mockito.when(lobbySettingsProperties.getInitialPeekDefaultSeconds()).thenReturn(10L);
		Mockito.when(lobbySettingsProperties.getTurnDefaultSeconds()).thenReturn(30L);
		Mockito.when(lobbySettingsProperties.getAbilityRevealDefaultSeconds()).thenReturn(5L);
			Mockito.when(lobbySettingsProperties.getAbilitySwapDefaultSeconds()).thenReturn(10L);
			Mockito.when(lobbySettingsProperties.getWebsocketGraceDefaultSeconds()).thenReturn(300L);
			Mockito.when(lobbySettingsProperties.getChatCooldownDefaultSeconds()).thenReturn(3L);
		Mockito.when(lobbySettingsProperties.getAfkTimeoutMinSeconds()).thenReturn(180L);
		Mockito.when(lobbySettingsProperties.getAfkTimeoutMaxSeconds()).thenReturn(1200L);
		Mockito.when(lobbySettingsProperties.getInitialPeekMinSeconds()).thenReturn(3L);
		Mockito.when(lobbySettingsProperties.getInitialPeekMaxSeconds()).thenReturn(60L);
		Mockito.when(lobbySettingsProperties.getTurnMinSeconds()).thenReturn(10L);
		Mockito.when(lobbySettingsProperties.getTurnMaxSeconds()).thenReturn(60L);
		Mockito.when(lobbySettingsProperties.getAbilityRevealMinSeconds()).thenReturn(3L);
		Mockito.when(lobbySettingsProperties.getAbilityRevealMaxSeconds()).thenReturn(10L);
		Mockito.when(lobbySettingsProperties.getAbilitySwapMinSeconds()).thenReturn(5L);
			Mockito.when(lobbySettingsProperties.getAbilitySwapMaxSeconds()).thenReturn(30L);
			Mockito.when(lobbySettingsProperties.getWebsocketGraceMinSeconds()).thenReturn(180L);
			Mockito.when(lobbySettingsProperties.getWebsocketGraceMaxSeconds()).thenReturn(600L);
			Mockito.when(lobbySettingsProperties.getChatCooldownMinSeconds()).thenReturn(1L);
			Mockito.when(lobbySettingsProperties.getChatCooldownMaxSeconds()).thenReturn(60L);
		// by default return empty list
		Mockito.when(lobbyRepository.findByStatusAndParticipantId(Mockito.anyString(), Mockito.anyLong()))
				.thenReturn(List.of());
	}

	private record FreshRematchThreePlayerFixture(Lobby playingLobby, User p1, User p2, User p3) {
	}


	// re-used by different tests
	// reduces code repetition
	private FreshRematchThreePlayerFixture givenPlayingLobbyForFreshRematchScenario(
			long playingLobbyId,
			String sessionId,
			long newLobbyIdWhenSaved) {
		Lobby playingLobby = new Lobby();
		playingLobby.setId(playingLobbyId);
		playingLobby.setSessionId(sessionId);
		playingLobby.setSessionHostUserId(1L);
		playingLobby.setStatus("PLAYING");
		playingLobby.setIsPublic(true);
		playingLobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L)));
		playingLobby.setAfkTimeoutSeconds(360L);
		playingLobby.setInitialPeekSeconds(12L);
		playingLobby.setTurnSeconds(25L);
		playingLobby.setAbilityRevealSeconds(7L);
		playingLobby.setAbilitySwapSeconds(13L);
		playingLobby.setAbsentRoundPoints(22L);
		playingLobby.setWebsocketGraceSeconds(333L);
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(playingLobby));
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(invocation -> {
			Lobby saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(newLobbyIdWhenSaved);
			}
			return saved;
		});
		User p1 = new User();
		p1.setId(1L);
		p1.setStatus(UserStatus.PLAYING);
		User p2 = new User();
		p2.setId(2L);
		p2.setStatus(UserStatus.PLAYING);
		User p3 = new User();
		p3.setId(3L);
		p3.setStatus(UserStatus.PLAYING);
		stubUserRepositoryForPlayers123(p1, p2, p3);
		return new FreshRematchThreePlayerFixture(playingLobby, p1, p2, p3);
	}

	private void stubUserRepositoryForPlayers123(User p1, User p2, User p3) {
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(p1, p2, p3));
		Mockito.when(userRepository.saveAll(Mockito.anyIterable())).thenAnswer(invocation -> invocation.getArgument(0));
		Mockito.when(userRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(p1, p2));
		Mockito.when(userRepository.findAllById(List.of(3L))).thenReturn(List.of(p3));
	}

	@Test
	public void updateLobbySettings_userIsHostAndLobbyIsWaiting_updatesIsPublic() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token1")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setIsPublic(true);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		LobbySettingsPatchDTO body = new LobbySettingsPatchDTO();
		body.setIsPublic(false);

		Lobby updated = lobbyService.updateLobbySettings("token1", "S1", body);

		assertFalse(updated.getIsPublic());
		Mockito.verify(lobbyEventPublisher, Mockito.times(1)).broadcastLobbyUpdate(Mockito.eq(10L), Mockito.any());
	}

	@Test
	public void updateLobbySettings_notHost_throwsForbidden() {
		User other = new User();
		other.setId(2L);
		Mockito.when(userRepository.findByToken("token1")).thenReturn(other);

		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setIsPublic(true);
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		LobbySettingsPatchDTO body = new LobbySettingsPatchDTO();
		body.setIsPublic(false);

		assertThrows(ResponseStatusException.class, () -> lobbyService.updateLobbySettings("token1", "S1", body));
	}

	@Test
	public void getWaitingLobbyView_member_isPublicMatchesLobby() {
		User member = new User();
		member.setId(2L);
		member.setUsername("guest");
		Mockito.when(userRepository.findByToken("token1")).thenReturn(member);

		User host = new User();
		host.setId(1L);
		host.setUsername("host");
		Mockito.when(userRepository.findAllById(Mockito.anyIterable()))
				.thenReturn(List.of(host, member));

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("SID1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setIsPublic(false);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		Mockito.when(lobbyRepository.findBySessionId("SID1")).thenReturn(lobby);

		WaitingLobbyViewDTO dto = lobbyService.getWaitingLobbyView("token1", "SID1");

		assertFalse(dto.getIsPublic());
		assertEquals("SID1", dto.getSessionId());
		assertEquals(2, dto.getPlayers().size());
	}

	@Test
	public void joinLobby_invalidToken_throwsUnauthorized() {
		Mockito.when(userRepository.findByToken("invalid-token")).thenReturn(null);

		// verify that an exception is thrown upon a lobby join with an invalid token
		// and save the exception for further verification of its status code
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.joinLobby("S1", "invalid-token"));

		// verify exception's status code
		assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
		// lobbyRepository should not be called after an attempt to join lobby with an invalid token
		Mockito.verify(lobbyRepository, Mockito.never()).findBySessionId(Mockito.anyString());
	}

	@Test
	public void joinLobby_validToken_addsUserToLobby() {
		User joiner = new User();
		joiner.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(joiner);

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		// upon calling lobbyRepository.save and passing to it an instance of Lobby
		// return the lobby that's being passed
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		Lobby result = lobbyService.joinLobby("S1", "token");

		assertTrue(result.getPlayerIds().contains(2L));
		// verify that broadcastLobbyUpdate was called once within the lobbyEventPublisher for lobby with id 10L
		Mockito.verify(lobbyEventPublisher, Mockito.times(1)).broadcastLobbyUpdate(Mockito.eq(10L), Mockito.any());
		Mockito.verify(onlineUsersEventPublisher, Mockito.times(1)).broadcastOnlineUsers();
	}

	@Test
	public void joinLobby_invalidSessionId() {
		// create a user object
		User joiner = new User();
		joiner.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(joiner);

		// passing falseSessionId will will result in a null lobby since this sessionId doesnt exist
		// then we execute the method and assert it throws an exception
		ResponseStatusException ex = assertThrows(ResponseStatusException.class, 
										() -> lobbyService.joinLobby("falseSessionId", "token"));

		// we check that the thrown exception is what we expect
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());

		// verify that we never saved anything to the DB or broadcast an event
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
		Mockito.verify(lobbyEventPublisher, Mockito.never()).broadcastLobbyUpdate(Mockito.anyLong(), Mockito.any());
	}

	@Test
	public void joinLobby_lobbyFull() {
		// create user to join
		User joiner = new User();
		joiner.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(joiner);

		// create lobby
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 3L, 4L, 5L)));
		// mock finding the lobby by session id to return the desired lobby
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		// call joinLobby method and assert it throws an exception
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
										() -> lobbyService.joinLobby("S1", "token"));
		// make sure the exception is what we expect
		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		// verify external methods were called 
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
		Mockito.verify(lobbyEventPublisher, Mockito.never()).broadcastLobbyUpdate(Mockito.anyLong(), Mockito.any());
	}

	@Test
	public void joinLobby_userInActiveGame_throwsConflict() {
		User joiner = new User();
		joiner.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(joiner);

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		Game game = new Game();
		game.setId("G1");
		game.setStatus(GameStatus.ROUND_ACTIVE);
		game.setOrderedPlayerIds(new ArrayList<>(List.of(2L, 1L)));
		Mockito.when(lobbyRepository.existsByStatusAndPlayerId("PLAYING", 2L)).thenReturn(true);
		Mockito.when(gameRepository.findGamesByPlayerId(2L)).thenReturn(List.of(game));

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.joinLobby("S1", "token"));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void createLobby_validUser() {
		// setup creator
		User creator = new User();
		creator.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(creator);
		// mock active lobby check to return empty list meaning this user does not have an active lobby yet
		Mockito.when(lobbyRepository.findBySessionHostUserId(2L)).thenReturn(new ArrayList<>());
		// mock save method
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		Lobby result = lobbyService.createLobby("token", true);

		assertNotNull(result.getSessionId());
		assertEquals(2L, result.getSessionHostUserId());
		assertTrue(result.getIsPublic());
		assertTrue(result.getPlayerIds().contains(2L));
		// verify that external tools were called
		Mockito.verify(lobbyRepository, Mockito.times(1)).save(Mockito.any(Lobby.class));
		Mockito.verify(lobbyEventPublisher, Mockito.times(1)).broadcastLobbyUpdate(Mockito.any(), Mockito.any());
		Mockito.verify(onlineUsersEventPublisher, Mockito.times(1)).broadcastOnlineUsers();
	}

	@Test
	public void createLobby_userInActiveGame_throwsConflict() {
		User creator = new User();
		creator.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(creator);

		Game game = new Game();
		game.setId("G2");
		game.setStatus(GameStatus.ROUND_ACTIVE);
		game.setOrderedPlayerIds(new ArrayList<>(List.of(2L, 7L)));
		Mockito.when(lobbyRepository.existsByStatusAndPlayerId("PLAYING", 2L)).thenReturn(true);
		Mockito.when(gameRepository.findGamesByPlayerId(2L)).thenReturn(List.of(game));

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.createLobby("token", true));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void verifyLobbyCanStart_notHost_throwsForbidden() {
		User guest = new User();
		guest.setId(2L);
		Mockito.when(userRepository.findByToken("guest-token")).thenReturn(guest);

		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.verifyLobbyCanStart("guest-token", "S1"));
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
	}

	@Test
	public void markLobbyAsPlaying_setsLobbyAndPlayersToPlaying() {
		Lobby lobby = new Lobby();
		lobby.setId(22L);
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		User u1 = new User();
		u1.setId(1L);
		u1.setStatus(UserStatus.LOBBY);
		User u2 = new User();
		u2.setId(2L);
		u2.setStatus(UserStatus.LOBBY);
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(new ArrayList<>(List.of(u1, u2)));
		Mockito.when(userRepository.saveAll(Mockito.anyIterable())).thenAnswer(inv -> inv.getArgument(0));

		lobbyService.markLobbyAsPlaying("S1");

		assertEquals("PLAYING", lobby.getStatus());
		assertEquals(UserStatus.PLAYING, u1.getStatus());
		assertEquals(UserStatus.PLAYING, u2.getStatus());
		Mockito.verify(lobbyEventPublisher, Mockito.times(1)).broadcastLobbyUpdate(Mockito.eq(22L), Mockito.any());
		Mockito.verify(onlineUsersEventPublisher, Mockito.times(1)).broadcastOnlineUsers();
	}

	// player can leave lobby, host migrates if host leaves
	@Test
	public void removePlayerFromLobby_hostLeaves_migratesHostToNextPlayer() {
    	User host = new User();
    	host.setId(1L);
    	Mockito.when(userRepository.findByToken("host-token")).thenReturn(host);

    	Lobby lobby = new Lobby();
    	lobby.setId(10L);
    	lobby.setSessionId("S1");
    	lobby.setSessionHostUserId(1L);
    	lobby.setStatus("WAITING");
    	lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L)));
    	Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
    	Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

    	lobbyService.removePlayerFromLobby("S1", "host-token", 1L);

    	// host should be migrated to next player
    	assertEquals(2L, lobby.getSessionHostUserId());
    	assertFalse(lobby.getPlayerIds().contains(1L));
    	Mockito.verify(lobbyEventPublisher, Mockito.times(1)).broadcastLobbyUpdate(Mockito.eq(10L), Mockito.any());
	}

	// host can kick another player
	@Test
	public void removePlayerFromLobby_hostKicksPlayer_removesPlayer() {
    	User host = new User();
    	host.setId(1L);
    	Mockito.when(userRepository.findByToken("host-token")).thenReturn(host);

    	Lobby lobby = new Lobby();
    	lobby.setId(10L);
    	lobby.setSessionId("S1");
    	lobby.setSessionHostUserId(1L);
    	lobby.setStatus("WAITING");
    	lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
    	Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
    	Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

    	lobbyService.removePlayerFromLobby("S1", "host-token", 2L);

    	assertFalse(lobby.getPlayerIds().contains(2L));
    	assertEquals(1L, lobby.getSessionHostUserId()); // host unchanged
    	Mockito.verify(lobbyEventPublisher, Mockito.times(1)).broadcastLobbyUpdate(Mockito.eq(10L), Mockito.any());
	}

	// non-host cannot kick another player
	@Test
	public void removePlayerFromLobby_nonHostKicksOther_throwsForbidden() {
    	User guest = new User();
    	guest.setId(2L);
    	Mockito.when(userRepository.findByToken("guest-token")).thenReturn(guest);

    	Lobby lobby = new Lobby();
    	lobby.setId(10L);
    	lobby.setSessionId("S1");
    	lobby.setSessionHostUserId(1L);
    	lobby.setStatus("WAITING");
    	lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L)));
    	Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

    	ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            	() -> lobbyService.removePlayerFromLobby("S1", "guest-token", 3L));

    	assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    	Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void removePlayerFromLobby_playingLobby_throwsConflictAndKeepsPlayers() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("host-token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("PLAYING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L)));
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.removePlayerFromLobby("S1", "host-token", 2L));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertTrue(lobby.getPlayerIds().contains(2L));
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void handleRoundResolvedForGamePlayers_hostVotesNo_hostMigratesToRematchVoter() {
		Lobby playingLobby = new Lobby();
		playingLobby.setId(10L);
		playingLobby.setSessionId("PLAY123");
		playingLobby.setSessionHostUserId(1L);
		playingLobby.setStatus("PLAYING");
		playingLobby.setIsPublic(true);
		playingLobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L, 4L)));
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(playingLobby));
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(invocation -> {
			Lobby saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(99L);
			}
			return saved;
		});

		for (long id = 1L; id <= 4L; id++) {
			User user = new User();
			user.setId(id);
			user.setStatus(UserStatus.PLAYING);
			Mockito.when(userRepository.findById(id)).thenReturn(Optional.of(user));
		}
		Mockito.when(userRepository.save(Mockito.any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		lobbyService.handleRoundResolvedForGamePlayers(
				List.of(1L, 2L, 3L, 4L),
				List.of(3L, 4L),
				List.of()
		);

		ArgumentCaptor<Lobby> savedLobbyCaptor = ArgumentCaptor.forClass(Lobby.class);
		Mockito.verify(lobbyRepository).save(savedLobbyCaptor.capture());
		Lobby rematchLobby = savedLobbyCaptor.getValue();

		assertEquals("WAITING", rematchLobby.getStatus());
		assertEquals(3L, rematchLobby.getSessionHostUserId());
		assertEquals(List.of(3L, 4L), rematchLobby.getPlayerIds());
		Mockito.verify(lobbyRepository, Mockito.never()).delete(playingLobby);
	}

	@Test
	public void handleRoundResolvedForGamePlayers_singleFreshVote_doesNotLeavePlayerInPlaying() {
		Lobby playingLobby = new Lobby();
		playingLobby.setId(11L);
		playingLobby.setSessionId("PLAY-SINGLE-FRESH");
		playingLobby.setSessionHostUserId(1L);
		playingLobby.setStatus("PLAYING");
		playingLobby.setIsPublic(true);
		playingLobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L)));
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(playingLobby));
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(invocation -> invocation.getArgument(0));

		User p1 = new User();
		p1.setId(1L);
		p1.setStatus(UserStatus.PLAYING);
		User p2 = new User();
		p2.setId(2L);
		p2.setStatus(UserStatus.PLAYING);
		User p3 = new User();
		p3.setId(3L);
		p3.setStatus(UserStatus.PLAYING);
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(p1, p2, p3));
		Mockito.when(userRepository.saveAll(Mockito.anyIterable())).thenAnswer(invocation -> invocation.getArgument(0));
		Mockito.when(userRepository.findAllById(List.of(2L, 3L))).thenReturn(List.of(p2, p3));
		Mockito.when(userRepository.findAllById(List.of(1L))).thenReturn(List.of(p1));

		lobbyService.handleRoundResolvedForGamePlayers(
				List.of(1L, 2L, 3L),
				List.of(2L, 3L),
				List.of(1L)
		);

		assertEquals(UserStatus.ONLINE, p1.getStatus());
		assertEquals(UserStatus.LOBBY, p2.getStatus());
		assertEquals(UserStatus.LOBBY, p3.getStatus());
		Mockito.verify(lobbyRepository, Mockito.never()).delete(playingLobby);
	}

	@Test
	public void handleRoundResolvedForGamePlayers_twoFreshVotes_createsFreshLobbyAndDeletesCurrent() {
		FreshRematchThreePlayerFixture fx = givenPlayingLobbyForFreshRematchScenario(12L, "PLAY-FRESH", 1200L);

		lobbyService.handleRoundResolvedForGamePlayers(
				List.of(1L, 2L, 3L),
				List.of(),
				List.of(1L, 2L)
		);

		ArgumentCaptor<Lobby> savedLobbyCaptor = ArgumentCaptor.forClass(Lobby.class);
		Mockito.verify(lobbyRepository).save(savedLobbyCaptor.capture());
		Lobby freshLobby = savedLobbyCaptor.getValue();
		assertEquals("WAITING", freshLobby.getStatus());
		assertEquals(1L, freshLobby.getSessionHostUserId());
		assertEquals(List.of(1L, 2L), freshLobby.getPlayerIds());
		assertEquals(360L, freshLobby.getAfkTimeoutSeconds());
		assertEquals(333L, freshLobby.getWebsocketGraceSeconds());
		Mockito.verify(lobbyRepository).delete(fx.playingLobby());
		assertEquals(UserStatus.LOBBY, fx.p1().getStatus());
		assertEquals(UserStatus.LOBBY, fx.p2().getStatus());
		assertEquals(UserStatus.ONLINE, fx.p3().getStatus());
	}

	@Test
	public void handleRoundResolvedForGamePlayers_twoFreshVotes_usesFreshRematchRequesterAsHost() {
		FreshRematchThreePlayerFixture fx = givenPlayingLobbyForFreshRematchScenario(12L, "PLAY-FRESH-HOST", 1201L);

		lobbyService.handleRoundResolvedForGamePlayers(
				List.of(1L, 2L, 3L),
				List.of(),
				List.of(1L, 2L),
				2L
		);

		ArgumentCaptor<Lobby> savedLobbyCaptor = ArgumentCaptor.forClass(Lobby.class);
		Mockito.verify(lobbyRepository).save(savedLobbyCaptor.capture());
		Lobby freshLobby = savedLobbyCaptor.getValue();
		assertEquals("WAITING", freshLobby.getStatus());
		assertEquals(2L, freshLobby.getSessionHostUserId());
		assertEquals(List.of(1L, 2L), freshLobby.getPlayerIds());
		Mockito.verify(lobbyRepository).delete(fx.playingLobby());
	}

	@Test
	public void handleRoundResolvedForGamePlayers_twoFreshVotes_carriesSpectatorsToFreshLobby() {
		FreshRematchThreePlayerFixture fx = givenPlayingLobbyForFreshRematchScenario(14L, "PLAY-FRESH-SPEC", 1401L);
		User spectator = new User();
		spectator.setId(9L);
		spectator.setStatus(UserStatus.SPECTATING);
		fx.playingLobby().setSpectatorIds(new ArrayList<>(List.of(9L)));
		Mockito.when(userRepository.findAllById(List.of(9L))).thenReturn(List.of(spectator));

		lobbyService.handleRoundResolvedForGamePlayers(
				List.of(1L, 2L, 3L),
				List.of(),
				List.of(1L, 2L)
		);

		ArgumentCaptor<Lobby> savedLobbyCaptor = ArgumentCaptor.forClass(Lobby.class);
		Mockito.verify(lobbyRepository).save(savedLobbyCaptor.capture());
		Lobby freshLobby = savedLobbyCaptor.getValue();
		assertEquals("WAITING", freshLobby.getStatus());
		assertEquals(List.of(1L, 2L), freshLobby.getPlayerIds());
		assertEquals(List.of(9L), freshLobby.getSpectatorIds());
		assertEquals(UserStatus.SPECTATING, spectator.getStatus());
		assertEquals(UserStatus.LOBBY, fx.p1().getStatus());
		assertEquals(UserStatus.LOBBY, fx.p2().getStatus());
		assertEquals(UserStatus.ONLINE, fx.p3().getStatus());
		Mockito.verify(lobbyRepository).delete(fx.playingLobby());
	}

	@Test
	public void handleRoundResolvedForGamePlayers_twoFreshVotes_invalidFreshRematchRequesterId_fallsBackToHostBasedOnTurnOrder() {
		givenPlayingLobbyForFreshRematchScenario(13L, "PLAY-FRESH-FALLBACK", 1301L);

		lobbyService.handleRoundResolvedForGamePlayers(
				List.of(1L, 2L, 3L),
				List.of(),
				List.of(1L, 2L),
				999L
		);

		ArgumentCaptor<Lobby> savedLobbyCaptor = ArgumentCaptor.forClass(Lobby.class);
		Mockito.verify(lobbyRepository).save(savedLobbyCaptor.capture());
		Lobby freshLobby = savedLobbyCaptor.getValue();
		assertEquals(1L, freshLobby.getSessionHostUserId());
		assertEquals(List.of(1L, 2L), freshLobby.getPlayerIds());
	}

	@Test
	public void findWaitingSessionIdForPlayer_prefersNewestWaitingLobby() {
		Lobby olderLobby = new Lobby();
		olderLobby.setId(10L);
		olderLobby.setSessionId("OLD-WAITING");
		olderLobby.setStatus("WAITING");
		olderLobby.setPlayerIds(new ArrayList<>(List.of(7L)));

		Lobby newerLobby = new Lobby();
		newerLobby.setId(20L);
		newerLobby.setSessionId("NEW-WAITING");
		newerLobby.setStatus("WAITING");
		newerLobby.setPlayerIds(new ArrayList<>(List.of(7L)));

		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 7L))
				.thenReturn(List.of(olderLobby, newerLobby));

		String waitingSessionId = lobbyService.findWaitingSessionIdForPlayer(7L);

		assertEquals("NEW-WAITING", waitingSessionId);
	}

	@Test
	public void findPlayingSessionIdForPlayers_exactSetMatch_returnsSessionId() {
		// matching lobby
		Lobby exactMatch = new Lobby();
		exactMatch.setSessionId("PLAY123");
		exactMatch.setStatus("PLAYING");
		exactMatch.setPlayerIds(new ArrayList<>(List.of(2L, 1L, 3L)));

		// non matching lobby
		Lobby nonMatch = new Lobby();
		nonMatch.setSessionId("OTHER");
		nonMatch.setStatus("PLAYING");
		nonMatch.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));

		// both lobbies returned when queried by status PLAYING
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(nonMatch, exactMatch));

		// the matching lobby's session id
		String sessionId = lobbyService.findPlayingSessionIdForPlayers(List.of(1L, 2L, 3L));

		assertEquals("PLAY123", sessionId);
	}

	@Test
	public void findPlayingSessionIdForPlayers_noExactMatch_usesBestOverlapFallback_orEmptyInputReturnsNull() {
		Lobby onlyLobby = new Lobby();
		onlyLobby.setSessionId("PLAY123");
		onlyLobby.setStatus("PLAYING");
		onlyLobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L)));
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(onlyLobby));

		assertEquals("PLAY123", lobbyService.findPlayingSessionIdForPlayers(List.of(1L, 2L, 4L)));
		assertNull(lobbyService.findPlayingSessionIdForPlayers(List.of()));
		assertNull(lobbyService.findPlayingSessionIdForPlayers(null));
	}

	// #116 join as spectator: sets spectatorIds, SPECTATING status and broadcasts result
	@Test
	public void joinWaitingLobbyAsSpectator_addsSpectatorSetsStatusAndBroadcasts() {
		User spectator = new User();
		spectator.setId(5L);
		spectator.setStatus(UserStatus.ONLINE);
		Mockito.when(userRepository.findByToken("token")).thenReturn(spectator);
		Mockito.when(userRepository.findById(5L)).thenReturn(Optional.of(spectator));

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		// when we call lobby repository to save a lobby, return the lobby that is being passed as argument
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		Lobby result = lobbyService.joinLobbyAsSpectator("S1", "token");

		assertTrue(result.getSpectatorIds().contains(5L));
		assertFalse(result.getPlayerIds().contains(5L));
		assertEquals(UserStatus.SPECTATING, spectator.getStatus());
		// verify that we have called broadcastLobbyUpdate once for lobby with id 10
		Mockito.verify(lobbyEventPublisher, Mockito.times(1)).broadcastLobbyUpdate(Mockito.eq(10L), Mockito.any());
		// verify that we have called broadcastOnlineUsers once
		Mockito.verify(onlineUsersEventPublisher, Mockito.times(1)).broadcastOnlineUsers();
	}

	// #116: spectator joins as player on same lobby — removed from spectatorIds
	@Test
	public void joinLobby_changesSpectatorToPlayer_clearsSpectatorList() {
		User joiner = new User();
		joiner.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(joiner);
		Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(joiner));

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		// add joiner's id to spectator's list
		lobby.setSpectatorIds(new ArrayList<>(List.of(2L)));
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		// when we call lobby repository to save a lobby, return the lobby that is being passed as argument
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// join as player
		lobbyService.joinLobby("S1", "token");

		assertTrue(lobby.getPlayerIds().contains(2L));
		assertFalse(lobby.getSpectatorIds().contains(2L));
		// joiner's status is LOBBY, which is a status for players (not spectators) waiting in the lobby
		assertEquals(UserStatus.LOBBY, joiner.getStatus());
	}
}
