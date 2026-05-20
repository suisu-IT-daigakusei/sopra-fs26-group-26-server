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
import ch.uzh.ifi.hase.soprafs26.rest.dto.WaitingLobbyPlayerRowDTO;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Method;
import java.util.Set;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.*;

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
	public void getWaitingLobbyView_preferredColorConflict_assignsUniqueColorsHostFirst() {
		User host = new User();
		host.setId(1L);
		host.setUsername("host");
		host.setPreferredColorPriority(new ArrayList<>(List.of("red", "orange", "pink", "purple")));
		Mockito.when(userRepository.findByToken("host-token")).thenReturn(host);

		User playerTwo = new User();
		playerTwo.setId(2L);
		playerTwo.setUsername("p2");
		playerTwo.setPreferredColorPriority(new ArrayList<>(List.of("red", "orange", "yellow", "purple")));

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("SID-COLOR");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setAssignedCharacterColorByUserId(new HashMap<>());
		Mockito.when(lobbyRepository.findBySessionId("SID-COLOR")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(host, playerTwo));

		WaitingLobbyViewDTO dto = lobbyService.getWaitingLobbyView("host-token", "SID-COLOR");

		assertEquals(2, dto.getPlayers().size());
		assertEquals("red", dto.getPlayers().get(0).getCharacterColorId());
		assertEquals("orange", dto.getPlayers().get(1).getCharacterColorId());
		assertEquals("red", lobby.getAssignedCharacterColorByUserId().get(1L));
		assertEquals("orange", lobby.getAssignedCharacterColorByUserId().get(2L));
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

	//#116 join as spectator: sets spectatorIds, SPECTATING status and broadcasts result
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

	@Test
    public void testGetWaitingLobbyView_IncludesSpectatorsCorrectly() {
        // 1. Setup: Testdaten erstellen (Ein Host-Spieler und ein Zuschauer)
        String token = "spectator-token-abc";
        String sessionId = "lobby-session-xyz";
        Long hostId = 1L;
        Long spectatorId = 2L;

        // Mock-Zuschauer (der die Anfrage stellt)
        User spectatorUser = new User();
        spectatorUser.setId(spectatorId);
        spectatorUser.setUsername("SpectatorJana");

        // Mock-Host (Spieler in der Lobby)
        User hostUser = new User();
        hostUser.setId(hostId);
        hostUser.setUsername("HostPlayer");

        // Lobby aufbauen mit einem Spieler und einem Zuschauer
        Lobby mockLobby = new Lobby();
        mockLobby.setSessionId(sessionId);
        // Nutzen wir playerIds, da das Service-File hier filtert
        mockLobby.setPlayerIds(new ArrayList<>(List.of(hostId)));
        mockLobby.setSpectatorIds(new ArrayList<>(List.of(spectatorId)));

        // Mocks für die Repositories konfigurieren
        org.mockito.Mockito.when(userRepository.findByToken(token)).thenReturn(spectatorUser);
        org.mockito.Mockito.when(lobbyRepository.findBySessionId(sessionId)).thenReturn(mockLobby);
        org.mockito.Mockito.when(userRepository.findAllById(org.mockito.Mockito.anyCollection()))
                .thenReturn(List.of(hostUser, spectatorUser));

        // 2. Ausführung der Service-Methode
        WaitingLobbyViewDTO resultDto = lobbyService.getWaitingLobbyView(token, sessionId);

        // 3. Assertions: Prüfen, ob die Spectator-Liste befüllt und korrekt ist
        assertNotNull(resultDto, "Das DTO darf nicht null sein.");
        assertNotNull(resultDto.getSpectators(), "Die Zuschauerliste darf nicht null sein.");
        assertEquals(1, resultDto.getSpectators().size(), "Es sollte genau 1 Zuschauer in der Liste sein.");
        
        WaitingLobbyPlayerRowDTO spectatorRow = resultDto.getSpectators().get(0);
        assertEquals("SpectatorJana", spectatorRow.getUsername(), "Der Username des Zuschauers stimmt nicht.");
        assertEquals("you", spectatorRow.getJoinStatus(), "Da der Zuschauer selbst anfragt, muss sein Status 'you' sein.");
    }

	@Test
    public void testRemoveSpectator_Success() {
        // 1. Setup
        String sessionId = "lobby-session-123";
        String token = "spectator-token-123";
        Long activePlayerId = 10L;
        Long spectatorId = 20L;

        User spectatorUser = new User();
        spectatorUser.setId(spectatorId);

        Lobby mockLobby = new Lobby();
        mockLobby.setId(999L);
        mockLobby.setSessionId(sessionId);
        mockLobby.setStatus("WAITING");
        mockLobby.setSessionHostUserId(activePlayerId);
        mockLobby.setPlayerIds(new ArrayList<>(List.of(activePlayerId)));
        mockLobby.setSpectatorIds(new ArrayList<>(List.of(spectatorId)));

        // Mocks konfigurieren
        org.mockito.Mockito.when(userRepository.findByToken(token)).thenReturn(spectatorUser);
        org.mockito.Mockito.when(lobbyRepository.findBySessionId(sessionId)).thenReturn(mockLobby);
        // Das hat gefehlt: Das veränderte Lobby-Objekt beim Speichern wieder zurückgeben
        org.mockito.Mockito.when(lobbyRepository.save(org.mockito.Mockito.any(Lobby.class))).thenReturn(mockLobby);

        // 2. Ausführung über die spezifische Zuschauer-Methode
        lobbyService.removeSpectatorFromLobby(sessionId, token, spectatorId);

        // 3. Assertions
        assertEquals(0, mockLobby.getSpectatorIds().size(), "Der Zuschauer haette entfernt werden muessen.");
        assertEquals(1, mockLobby.getPlayerIds().size(), "Die Liste der aktiven Spieler darf sich nicht aendern.");
    }

	/**
	 * Helper Method: Safely grabs the private timedOutInPlayingPlayerIds set
	 * so we can manipulate and assert against it directly in isolation.
	 */
	@SuppressWarnings("unchecked")
	private Set<Long> getTimedOutSet() {
		return (Set<Long>) ReflectionTestUtils.getField(lobbyService, "timedOutInPlayingPlayerIds");
	}

	@Test
	public void isPlayerTimedOutInPlaying_nullId_returnsFalse() {
		assertFalse(lobbyService.isPlayerTimedOutInPlaying(null));
	}

	@Test
	public void isPlayerTimedOutInPlaying_notInSet_returnsFalse() {
		getTimedOutSet().clear();
		assertFalse(lobbyService.isPlayerTimedOutInPlaying(1L));
	}

	@Test
	public void isPlayerTimedOutInPlaying_inSet_returnsTrue() {
		getTimedOutSet().add(1L);
		assertTrue(lobbyService.isPlayerTimedOutInPlaying(1L));
	}

	@Test
	public void clearTimedOutPlayingFlag_nullId_doesNothing() {
		getTimedOutSet().add(1L);
		lobbyService.clearTimedOutPlayingFlag(null);
		
		// Verifies the set was left untouched
		assertTrue(getTimedOutSet().contains(1L)); 
	}

	@Test
	public void clearTimedOutPlayingFlag_validId_removesFromSet() {
		getTimedOutSet().add(1L);
		lobbyService.clearTimedOutPlayingFlag(1L);
		
		// Verifies it was successfully cleared
		assertFalse(getTimedOutSet().contains(1L));
	}

	@Test
	public void clearTimedOutPlayingFlags_nullList_doesNothing() throws Exception {
		getTimedOutSet().add(1L);

		// Access the private method using Java Reflection
		Method method = LobbyService.class.getDeclaredMethod("clearTimedOutPlayingFlags", List.class);
		method.setAccessible(true);
		method.invoke(lobbyService, (List<Long>) null);

		// Verifies the method returned early without modifying the set
		assertTrue(getTimedOutSet().contains(1L));
	}

	@Test
	public void clearTimedOutPlayingFlags_validList_removesAll() throws Exception {
		getTimedOutSet().addAll(List.of(1L, 2L, 3L));

		// Access the private method using Java Reflection
		Method method = LobbyService.class.getDeclaredMethod("clearTimedOutPlayingFlags", List.class);
		method.setAccessible(true);
		method.invoke(lobbyService, List.of(1L, 2L));

		// Verifies the specified IDs were removed, but others were left intact
		assertFalse(getTimedOutSet().contains(1L));
		assertFalse(getTimedOutSet().contains(2L));
		assertTrue(getTimedOutSet().contains(3L)); 
	}

	@Test
	public void isUserInLobbyContext_nullId_returnsFalse() {
		assertFalse(lobbyService.isUserInLobbyContext(null));
		
		// Verify the repository was completely protected from the null value
		Mockito.verify(lobbyRepository, Mockito.never())
               .existsByStatusAndParticipantId(Mockito.anyString(), Mockito.anyLong());
	}

	@Test
	public void isUserInLobbyContext_inWaitingLobby_returnsTrue() {
		Mockito.when(lobbyRepository.existsByStatusAndParticipantId("WAITING", 1L)).thenReturn(true);
		
		assertTrue(lobbyService.isUserInLobbyContext(1L));

		// Verify short-circuiting: It should NOT check for PLAYING if WAITING is already true
		Mockito.verify(lobbyRepository, Mockito.never())
               .existsByStatusAndParticipantId("PLAYING", 1L);
	}

	@Test
	public void isUserInLobbyContext_inPlayingLobby_returnsTrue() {
		Mockito.when(lobbyRepository.existsByStatusAndParticipantId("WAITING", 1L)).thenReturn(false);
		Mockito.when(lobbyRepository.existsByStatusAndParticipantId("PLAYING", 1L)).thenReturn(true);
		
		assertTrue(lobbyService.isUserInLobbyContext(1L));
	}

	@Test
	public void isUserInLobbyContext_notInAnyLobby_returnsFalse() {
		Mockito.when(lobbyRepository.existsByStatusAndParticipantId("WAITING", 1L)).thenReturn(false);
		Mockito.when(lobbyRepository.existsByStatusAndParticipantId("PLAYING", 1L)).thenReturn(false);
		
		assertFalse(lobbyService.isUserInLobbyContext(1L));
	}

	@Test
	public void getPlayingLobbyPlayerIdsSnapshot_noPlayingLobbies_returnsEmptySet() {
		// 1. Setup: No active games
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of());

		// 2. Action
		Set<Long> result = lobbyService.getPlayingLobbyPlayerIdsSnapshot();

		// 3. Assertion
		assertTrue(result.isEmpty());
	}

	@Test
	public void getPlayingLobbyPlayerIdsSnapshot_lobbyOrPlayerIdsNull_skipsSafely() {
		// 1. Setup: Create a list with tricky null values to hit the inner `if` branches
		Lobby nullLobby = null;
		
		Lobby lobbyWithNullIds = new Lobby();
		lobbyWithNullIds.setPlayerIds(null); 
		
		Lobby validLobby = new Lobby();
		validLobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));

		// Pass all three into the mock
		Mockito.when(lobbyRepository.findByStatus("PLAYING"))
			   .thenReturn(java.util.Arrays.asList(nullLobby, lobbyWithNullIds, validLobby));

		// 2. Action
		Set<Long> result = lobbyService.getPlayingLobbyPlayerIdsSnapshot();

		// 3. Assertion: It should skip the bad data and only grab the valid IDs
		assertEquals(2, result.size());
		assertTrue(result.containsAll(List.of(1L, 2L)));
	}

	@Test
	public void getPlayingLobbyPlayerIdsSnapshot_validLobbies_returnsAggregatedAndDeduplicatedSet() {
		// 1. Setup: Two lobbies, where Player 3 is accidentally in both
		Lobby lobby1 = new Lobby();
		lobby1.setPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L)));
		
		Lobby lobby2 = new Lobby();
		lobby2.setPlayerIds(new ArrayList<>(List.of(3L, 4L, 5L))); 

		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby1, lobby2));

		// 2. Action
		Set<Long> result = lobbyService.getPlayingLobbyPlayerIdsSnapshot();

		// 3. Assertion: It should aggregate everyone and deduplicate Player 3 automatically
		assertEquals(5, result.size());
		assertTrue(result.containsAll(List.of(1L, 2L, 3L, 4L, 5L)));
	}

	@Test
	public void findLatestPlayingLobbyForSpectator_nullId_returnsEmpty() {
		// 1. Action
		Optional<Lobby> result = lobbyService.findLatestPlayingLobbyForSpectator(null);

		// 2. Assertion
		assertTrue(result.isEmpty());
		
		// Verify early exit protected the repository
		Mockito.verify(lobbyRepository, Mockito.never())
			   .findByStatusAndParticipantId(Mockito.anyString(), Mockito.anyLong());
	}

	@Test
	public void findLatestPlayingLobbyForSpectator_noMatchingLobbies_returnsEmpty() {
		// 1. Setup
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L))
			   .thenReturn(List.of());

		// 2. Action
		Optional<Lobby> result = lobbyService.findLatestPlayingLobbyForSpectator(1L);

		// 3. Assertion
		assertTrue(result.isEmpty());
	}

	@Test
	public void findLatestPlayingLobbyForSpectator_filtersOutInvalidLobbies_returnsEmpty() {
		// 1. Setup: Create messy lobbies to hit every single `null` and `contains` branch in the filter
		Lobby nullLobby = null;

		Lobby nullSpectatorsLobby = new Lobby();
		nullSpectatorsLobby.setId(10L);
		nullSpectatorsLobby.setSpectatorIds(null); // Hits the spectatorIds != null check

		Lobby missingSpectatorLobby = new Lobby();
		missingSpectatorLobby.setId(20L);
		missingSpectatorLobby.setSpectatorIds(new ArrayList<>(List.of(2L, 3L))); // Hits the contains(userId) check

		// The repository returns all this bad data
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L))
			   .thenReturn(java.util.Arrays.asList(nullLobby, nullSpectatorsLobby, missingSpectatorLobby));

		// 2. Action
		Optional<Lobby> result = lobbyService.findLatestPlayingLobbyForSpectator(1L);

		// 3. Assertion: The stream filter should successfully block all of them
		assertTrue(result.isEmpty());
	}

	@Test
	public void findLatestPlayingLobbyForSpectator_multipleValidLobbies_returnsLatestById() {
		// 1. Setup: User is a spectator in 3 different active lobbies
		Lobby olderLobby = new Lobby();
		olderLobby.setId(10L);
		olderLobby.setSpectatorIds(new ArrayList<>(List.of(1L)));

		Lobby newerLobby = new Lobby();
		newerLobby.setId(50L);
		newerLobby.setSpectatorIds(new ArrayList<>(List.of(1L)));

		Lobby middleLobby = new Lobby();
		middleLobby.setId(30L);
		middleLobby.setSpectatorIds(new ArrayList<>(List.of(1L)));

		// Note: Returned out of order to ensure the Comparator does the sorting
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L))
			   .thenReturn(List.of(olderLobby, middleLobby, newerLobby));

		// 2. Action
		Optional<Lobby> result = lobbyService.findLatestPlayingLobbyForSpectator(1L);
		
		// 3. Assertion: It should successfully isolate the lobby with ID 50
		assertTrue(result.isPresent());
		assertEquals(50L, result.get().getId());
	}

	@Test
	public void findWebsocketGraceSecondsForUser_nullId_returnsNull() {
		// 1. Action & Assertion
		assertNull(lobbyService.findWebsocketGraceSecondsForUser(null));
		
		// 2. Verify repository was completely protected
		Mockito.verify(lobbyRepository, Mockito.never())
			   .findByStatusAndParticipantId(Mockito.anyString(), Mockito.anyLong());
	}

	@Test
	public void findWebsocketGraceSecondsForUser_validWaitingLobby_returnsWaitingGrace() {
		// 1. Setup: User has a WAITING lobby with a valid grace period
		Lobby waitingLobby = new Lobby();
		waitingLobby.setWebsocketGraceSeconds(45L);
		
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L))
			   .thenReturn(List.of(waitingLobby));

		// 2. Action
		Long result = lobbyService.findWebsocketGraceSecondsForUser(1L);

		// 3. Assertion
		assertEquals(45L, result);
		
		// Verify short-circuiting: It should skip the PLAYING check entirely
		Mockito.verify(lobbyRepository, Mockito.never())
			   .findByStatusAndParticipantId("PLAYING", 1L);
	}

	@Test
	public void findWebsocketGraceSecondsForUser_waitingGraceNull_playingGraceValid_returnsPlayingGrace() {
		// 1. Setup: WAITING lobby exists but has a null grace period. PLAYING lobby has a valid one.
		Lobby waitingLobby = new Lobby();
		waitingLobby.setWebsocketGraceSeconds(null); // Hits the != null branch
		
		Lobby playingLobby = new Lobby();
		playingLobby.setWebsocketGraceSeconds(60L);

		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L))
			   .thenReturn(List.of(waitingLobby));
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L))
			   .thenReturn(List.of(playingLobby));

		// 2. Action
		Long result = lobbyService.findWebsocketGraceSecondsForUser(1L);

		// 3. Assertion: It successfully fell back to the PLAYING lobby
		assertEquals(60L, result);
	}

	@Test
	public void findWebsocketGraceSecondsForUser_waitingGraceZero_playingGraceZero_returnsNull() {
		// 1. Setup: Both lobbies exist, but both have 0 for their grace period
		Lobby waitingLobby = new Lobby();
		waitingLobby.setWebsocketGraceSeconds(0L); // Hits the > 0 branch
		
		Lobby playingLobby = new Lobby();
		playingLobby.setWebsocketGraceSeconds(0L); // Hits the > 0 branch

		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L))
			   .thenReturn(List.of(waitingLobby));
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L))
			   .thenReturn(List.of(playingLobby));

		// 2. Action
		Long result = lobbyService.findWebsocketGraceSecondsForUser(1L);

		// 3. Assertion: Both failed the validation, so it defaults to null
		assertNull(result);
	}

	@Test
	public void findWebsocketGraceSecondsForUser_noLobbies_returnsNull() {
		// 1. Setup: User is not in any lobbies
		Mockito.when(lobbyRepository.findByStatusAndParticipantId(Mockito.anyString(), Mockito.anyLong()))
			   .thenReturn(List.of());

		// 2. Action
		Long result = lobbyService.findWebsocketGraceSecondsForUser(1L);

		// 3. Assertion
		assertNull(result);
	}

	@Test
	public void resolveJoinableSessionIdsForUsers_nullOrEmptyInput_returnsEmptyMap() {
		// Tests null input
		assertTrue(lobbyService.resolveJoinableSessionIdsForUsers(null).isEmpty());
		
		// Tests empty list
		assertTrue(lobbyService.resolveJoinableSessionIdsForUsers(List.of()).isEmpty());

		// Tests a list that contains only null values (hits the private toRequestedUserIdSet null-filter)
		List<Long> listWithNulls = new ArrayList<>();
		listWithNulls.add(null);
		assertTrue(lobbyService.resolveJoinableSessionIdsForUsers(listWithNulls).isEmpty());
	}

	@Test
	public void resolveJoinableSessionIdsForUsers_processesLobbiesAndFiltersInvalidSessions() {
		// 1. Setup: Create mock lobbies to hit every possible session string state
		Lobby validPlayingLobby = new Lobby();
		validPlayingLobby.setId(10L);
		validPlayingLobby.setSessionId("PLAY-123");
		validPlayingLobby.setPlayerIds(new ArrayList<>(List.of(1L)));

		Lobby nullSessionWaitingLobby = new Lobby();
		nullSessionWaitingLobby.setId(20L);
		nullSessionWaitingLobby.setSessionId(null); // Hits the sessionId == null check
		nullSessionWaitingLobby.setPlayerIds(new ArrayList<>(List.of(2L)));

		Lobby blankSessionWaitingLobby = new Lobby();
		blankSessionWaitingLobby.setId(30L);
		blankSessionWaitingLobby.setSessionId("   "); // Hits the sessionId.isBlank() check
		blankSessionWaitingLobby.setPlayerIds(new ArrayList<>(List.of(3L)));

		// Define what the repositories return when the private helpers query them
		Mockito.when(lobbyRepository.findByStatus("PLAYING"))
			   .thenReturn(List.of(validPlayingLobby));
		Mockito.when(lobbyRepository.findByStatus("WAITING"))
			   .thenReturn(List.of(nullSessionWaitingLobby, blankSessionWaitingLobby));

		// Create a list with valid users, a user with no lobby (4L), and a null to hit every filter branch
		List<Long> inputIds = new ArrayList<>(List.of(1L, 2L, 3L, 4L));
		inputIds.add(null); 

		// 2. Action
		Map<Long, String> result = lobbyService.resolveJoinableSessionIdsForUsers(inputIds);

		// 3. Assertion
		assertEquals(1, result.size());
		assertEquals("PLAY-123", result.get(1L)); // User 1 successfully got their session
		assertFalse(result.containsKey(2L));      // Filtered out because session was null
		assertFalse(result.containsKey(3L));      // Filtered out because session was blank
		assertFalse(result.containsKey(4L));      // Filtered out because they had no lobby
	}

	@Test
	public void resolveJoinableSessionIdForUser_nullId_returnsNull() {
		// 1. Action & Assertion
		assertNull(lobbyService.resolveJoinableSessionIdForUser(null));
		
		// Verify we didn't touch the repository if the ID was null
		Mockito.verify(lobbyRepository, Mockito.never()).findByStatus(Mockito.anyString());
	}

	@Test
	public void resolveJoinableSessionIdForUser_userNotInLobby_returnsNull() {
		// 1. Setup: User is not in any active lobbies
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of());
		Mockito.when(lobbyRepository.findByStatus("WAITING")).thenReturn(List.of());

		// 2. Action
		String result = lobbyService.resolveJoinableSessionIdForUser(1L);

		// 3. Assertion: The plural method returns an empty map, so .get(1L) yields null
		assertNull(result);
	}

	@Test
	public void resolveJoinableSessionIdForUser_userInLobby_returnsSessionId() {
		// 1. Setup: User 1 is actively in a lobby
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("MY-SESSION-123");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));

		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby));
		Mockito.when(lobbyRepository.findByStatus("WAITING")).thenReturn(List.of());

		// 2. Action
		String result = lobbyService.resolveJoinableSessionIdForUser(1L);

		// 3. Assertion: It successfully navigated the map and extracted the correct string
		assertEquals("MY-SESSION-123", result);
	}

	@Test
	public void resolveLobbyPresenceStatusForUsers_emptyOrNullInput_returnsEmptyMap() {
		// Tests null input
		assertTrue(lobbyService.resolveLobbyPresenceStatusForUsers(null).isEmpty());
		
		// Tests empty list
		assertTrue(lobbyService.resolveLobbyPresenceStatusForUsers(List.of()).isEmpty());

		// Tests a list that contains only null values
		List<Long> listWithNulls = new ArrayList<>();
		listWithNulls.add(null);
		assertTrue(lobbyService.resolveLobbyPresenceStatusForUsers(listWithNulls).isEmpty());
	}

	@Test
	public void resolveLobbyPresenceStatusForUsers_evaluatesAllPlayerAndSpectatorRoles() {
		// 1. Setup: Create a PLAYING lobby with a Player (1L) and a Spectator (2L)
		Lobby playingLobby = new Lobby();
		playingLobby.setId(10L);
		playingLobby.setStatus("PLAYING");
		playingLobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		playingLobby.setSpectatorIds(new ArrayList<>(List.of(2L)));

		// 2. Setup: Create a WAITING lobby with a Player (3L) and a Spectator (4L)
		Lobby waitingLobby = new Lobby();
		waitingLobby.setId(20L);
		waitingLobby.setStatus("WAITING");
		waitingLobby.setPlayerIds(new ArrayList<>(List.of(3L)));
		waitingLobby.setSpectatorIds(new ArrayList<>(List.of(4L)));

		// Mock the repository responses
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(playingLobby));
		Mockito.when(lobbyRepository.findByStatus("WAITING")).thenReturn(List.of(waitingLobby));

		// Ask the service to resolve statuses for all 4 users, plus a user (5L) who isn't in any lobby
		List<Long> inputIds = List.of(1L, 2L, 3L, 4L, 5L);

		// 3. Action
		Map<Long, UserStatus> result = lobbyService.resolveLobbyPresenceStatusForUsers(inputIds);

		// 4. Assertion: Verify every branch assigned the correct enum
		assertEquals(4, result.size());
		assertEquals(UserStatus.PLAYING, result.get(1L));       // Player in a PLAYING lobby
		assertEquals(UserStatus.SPECTATING, result.get(2L));    // Spectator in a PLAYING lobby
		assertEquals(UserStatus.LOBBY, result.get(3L));         // Player in a WAITING lobby
		assertEquals(UserStatus.SPECTATING, result.get(4L));    // Spectator in a WAITING lobby
		
		assertFalse(result.containsKey(5L)); // User 5 is completely ignored
	}

	@Test
	public void resolveLobbyPresenceStatusForUser_nullId_returnsNull() {
		// 1. Action & Assertion
		assertNull(lobbyService.resolveLobbyPresenceStatusForUser(null));
		
		// Verify early exit protected the repository
		Mockito.verify(lobbyRepository, Mockito.never()).findByStatus(Mockito.anyString());
	}

	@Test
	public void resolveLobbyPresenceStatusForUser_userNotInLobby_returnsNull() {
		// 1. Setup: User is not in any active lobbies
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of());
		Mockito.when(lobbyRepository.findByStatus("WAITING")).thenReturn(List.of());

		// 2. Action
		UserStatus result = lobbyService.resolveLobbyPresenceStatusForUser(1L);

		// 3. Assertion: The plural method returns an empty map, so .get(1L) yields null
		assertNull(result);
	}

	@Test
	public void resolveLobbyPresenceStatusForUser_userInLobby_returnsStatus() {
		// 1. Setup: User 1 is actively playing in a lobby
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setStatus("PLAYING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));

		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby));
		Mockito.when(lobbyRepository.findByStatus("WAITING")).thenReturn(List.of());

		// 2. Action
		UserStatus result = lobbyService.resolveLobbyPresenceStatusForUser(1L);

		// 3. Assertion: It successfully navigated the map and extracted the correct enum
		assertEquals(UserStatus.PLAYING, result);
	}

	@Test
	public void createLobby_isPublicNull_defaultsToTrue() {
		// 1. Setup
		User creator = new User();
		creator.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(creator);
		Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(creator)); // Needed for setUserStatus
		
		Mockito.when(lobbyRepository.findBySessionHostUserId(2L)).thenReturn(new ArrayList<>());
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// 2. Action: Pass null for isPublic
		Lobby result = lobbyService.createLobby("token", null);

		// 3. Assertion: It should default to true
		assertTrue(result.getIsPublic());
		assertEquals(UserStatus.LOBBY, creator.getStatus()); // Verify status was updated
	}

	@Test
	public void createLobby_isPublicFalse_createsPrivateLobby() {
		// 1. Setup
		User creator = new User();
		creator.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(creator);
		Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(creator));
		
		Mockito.when(lobbyRepository.findBySessionHostUserId(2L)).thenReturn(new ArrayList<>());
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// 2. Action: Pass false for isPublic
		Lobby result = lobbyService.createLobby("token", false);

		// 3. Assertion: It should respect the false value
		assertFalse(result.getIsPublic());
	}

	@Test
	public void createLobby_hostAlreadyHasWaitingLobby_throwsConflict() {
		// 1. Setup
		User creator = new User();
		creator.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(creator);
		
		// Setup a mock lobby that the host is already waiting in
		Lobby existingWaitingLobby = new Lobby();
		existingWaitingLobby.setStatus("WAITING");
		
		Mockito.when(lobbyRepository.findBySessionHostUserId(2L))
			   .thenReturn(List.of(existingWaitingLobby));

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.createLobby("token", true));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertTrue(ex.getReason().contains("already have an active lobby"));
		
		// Verify we never attempted to save a new lobby
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void findWaitingLobbyForHost_noLobbies_returnsEmpty() {
		// 1. Setup: Repository returns an empty list for this host
		Mockito.when(lobbyRepository.findBySessionHostUserId(1L))
			   .thenReturn(List.of());

		// 2. Action
		Optional<Lobby> result = lobbyService.findWaitingLobbyForHost(1L);

		// 3. Assertion: The stream is empty, so it returns Optional.empty()
		assertTrue(result.isEmpty());
	}

	@Test
	public void findWaitingLobbyForHost_noWaitingLobbies_returnsEmpty() {
		// 1. Setup: Host has lobbies, but none of them are WAITING
		Lobby playingLobby = new Lobby();
		playingLobby.setStatus("PLAYING");
		
		Lobby endedLobby = new Lobby();
		endedLobby.setStatus("ENDED");

		Mockito.when(lobbyRepository.findBySessionHostUserId(1L))
			   .thenReturn(List.of(playingLobby, endedLobby));

		// 2. Action
		Optional<Lobby> result = lobbyService.findWaitingLobbyForHost(1L);

		// 3. Assertion: The filter successfully blocks the non-waiting lobbies
		assertTrue(result.isEmpty());
	}

	@Test
	public void findWaitingLobbyForHost_hasWaitingLobby_returnsFirstMatch() {
		// 1. Setup: Host has a mix of lobbies, including multiple WAITING ones
		Lobby playingLobby = new Lobby();
		playingLobby.setStatus("PLAYING");
		
		Lobby targetWaitingLobby = new Lobby();
		targetWaitingLobby.setId(10L);
		targetWaitingLobby.setStatus("WAITING");

		Lobby secondWaitingLobby = new Lobby();
		secondWaitingLobby.setId(20L);
		secondWaitingLobby.setStatus("WAITING");

		// Note: The list order matters to test findFirst()
		Mockito.when(lobbyRepository.findBySessionHostUserId(1L))
			   .thenReturn(List.of(playingLobby, targetWaitingLobby, secondWaitingLobby));

		// 2. Action
		Optional<Lobby> result = lobbyService.findWaitingLobbyForHost(1L);

		// 3. Assertion: It should skip PLAYING, find the first WAITING lobby (ID 10), and stop
		assertTrue(result.isPresent());
		assertEquals(10L, result.get().getId());
	}

	@Test
	public void requireWaitingLobbyForHost_lobbyExists_returnsLobby() {
		// 1. Setup: Host has a WAITING lobby (simulating Optional.isPresent)
		Lobby waitingLobby = new Lobby();
		waitingLobby.setId(10L);
		waitingLobby.setStatus("WAITING");
		
		Mockito.when(lobbyRepository.findBySessionHostUserId(1L))
			   .thenReturn(List.of(waitingLobby));

		// 2. Action
		Lobby result = lobbyService.requireWaitingLobbyForHost(1L);

		// 3. Assertion: It successfully extracted and returned the lobby
		assertNotNull(result);
		assertEquals(10L, result.getId());
	}

	@Test
	public void requireWaitingLobbyForHost_noLobbyExists_throwsConflict() {
		// 1. Setup: Host has NO waiting lobbies (simulating Optional.empty)
		Mockito.when(lobbyRepository.findBySessionHostUserId(1L))
			   .thenReturn(List.of());

		// 2. Action & 3. Assertion: It throws the expected exception
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.requireWaitingLobbyForHost(1L));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Create a lobby first"));
	}

	@Test
	public void isLobbyWaiting_nullId_returnsFalse() {
		// 1. Action & Assertion
		assertFalse(lobbyService.isLobbyWaiting(null));

		// 2. Verify the repository was completely protected by the early exit
		Mockito.verify(lobbyRepository, Mockito.never()).findById(Mockito.anyLong());
	}

	@Test
	public void isLobbyWaiting_lobbyNotFound_returnsFalse() {
		// 1. Setup: Repository returns empty Optional
		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.empty());

		// 2. Action & 3. Assertion: It should gracefully hit the .orElse(false)
		assertFalse(lobbyService.isLobbyWaiting(1L));
	}

	@Test
	public void isLobbyWaiting_lobbyStatusNotWaiting_returnsFalse() {
		// 1. Setup: Lobby exists, but is currently PLAYING
		Lobby playingLobby = new Lobby();
		playingLobby.setId(1L);
		playingLobby.setStatus("PLAYING");

		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(playingLobby));

		// 2. Action & 3. Assertion
		assertFalse(lobbyService.isLobbyWaiting(1L));
	}

	@Test
	public void isLobbyWaiting_lobbyStatusIsWaiting_returnsTrue() {
		// 1. Setup: Lobby exists and is actively WAITING
		Lobby waitingLobby = new Lobby();
		waitingLobby.setId(1L);
		waitingLobby.setStatus("WAITING");

		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(waitingLobby));

		// 2. Action & 3. Assertion
		assertTrue(lobbyService.isLobbyWaiting(1L));
	}

	@Test
	public void addPlayerToLobby_lobbyNotFound_returnsEarly() {
		// 1. Setup
		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.empty());

		// 2. Action
		lobbyService.addPlayerToLobby(1L, 1L, 2L);

		// 3. Assertion: Never reaches the save method
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void addPlayerToLobby_wrongHost_returnsEarly() {
		// 1. Setup: Real host is 99L, but 1L was provided
		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(99L);
		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(lobby));

		// 2. Action
		lobbyService.addPlayerToLobby(1L, 1L, 2L); 

		// 3. Assertion
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void addPlayerToLobby_notWaiting_returnsEarly() {
		// 1. Setup: Lobby is already PLAYING
		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("PLAYING");
		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(lobby));

		// 2. Action
		lobbyService.addPlayerToLobby(1L, 1L, 2L);

		// 3. Assertion
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void addPlayerToLobby_lobbyFull_returnsEarly() {
		// 1. Setup: Lobby already has 4 players
		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 3L, 4L, 5L))); 
		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(lobby));

		// 2. Action
		lobbyService.addPlayerToLobby(1L, 1L, 2L);

		// 3. Assertion
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void addPlayerToLobby_guestInActiveGame_throwsConflict() {
		// 1. Setup: Valid lobby
		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(lobby));

		// Mock isUserInActiveGame to return true for the guest (2L)
		Game activeGame = new Game();
		activeGame.setStatus(GameStatus.ROUND_ACTIVE);
		activeGame.setOrderedPlayerIds(new ArrayList<>(List.of(2L)));
		Mockito.when(lobbyRepository.existsByStatusAndPlayerId("PLAYING", 2L)).thenReturn(true);
		Mockito.when(gameRepository.findGamesByPlayerId(2L)).thenReturn(List.of(activeGame));

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.addPlayerToLobby(1L, 1L, 2L));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void addPlayerToLobby_alreadyInLobby_skipsSaveAndReturns() {
		// 1. Setup: Guest (2L) is already inside the playerIds list
		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L))); 
		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(lobby));

		// 2. Action
		lobbyService.addPlayerToLobby(1L, 1L, 2L);

		// 3. Assertion: This covers the final "uncovered" closing brace! It should skip the entire save block.
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void addPlayerToLobby_validGuest_clearsListsAndSaves() {
		// 1. Setup: Perfect lobby, but the guest was previously kicked and is currently spectating
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("SESSION-123");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		
		// Fill the lists with the guest (2L) to trigger the cleanup branches
		lobby.setKickedUserIds(new ArrayList<>(List.of(2L)));
		lobby.setSpectatorIds(new ArrayList<>(List.of(2L)));
		
		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(lobby));
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// Provide a valid User for the status update
		User guestUser = new User();
		guestUser.setId(2L);
		guestUser.setStatus(UserStatus.ONLINE);
		Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(guestUser));

		// 2. Action
		lobbyService.addPlayerToLobby(1L, 1L, 2L);

		// 3. Assertions
		assertTrue(lobby.getPlayerIds().contains(2L));            // Successfully added
		assertFalse(lobby.getKickedUserIds().contains(2L));       // Successfully forgiven/removed
		assertFalse(lobby.getSpectatorIds().contains(2L));        // Successfully promoted from spectator
		assertEquals(UserStatus.LOBBY, guestUser.getStatus());    // Status updated

		Mockito.verify(lobbyRepository).save(lobby);
		Mockito.verify(lobbyEventPublisher).broadcastLobbyUpdate(10L, lobby);
		Mockito.verify(onlineUsersEventPublisher).broadcastOnlineUsers();
	}

	@Test
	public void getWaitingSessionIdIfPlayerInLobby_lobbyNotFound_returnsNull() {
		// 1. Setup: Repository returns empty
		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.empty());

		// 2. Action
		String result = lobbyService.getWaitingSessionIdIfPlayerInLobby(1L, 2L);

		// 3. Assertion: Stream instantly hits .orElse(null)
		assertNull(result);
	}

	@Test
	public void getWaitingSessionIdIfPlayerInLobby_statusNotWaiting_returnsNull() {
		// 1. Setup: Lobby exists and guest (2L) is in it, but status is wrong
		Lobby playingLobby = new Lobby();
		playingLobby.setStatus("PLAYING");
		playingLobby.setPlayerIds(new ArrayList<>(List.of(2L)));

		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(playingLobby));

		// 2. Action
		String result = lobbyService.getWaitingSessionIdIfPlayerInLobby(1L, 2L);

		// 3. Assertion: Filter fails on the first condition
		assertNull(result);
	}

	@Test
	public void getWaitingSessionIdIfPlayerInLobby_userNotInLobby_returnsNull() {
		// 1. Setup: Lobby exists and is waiting, but guest (2L) is missing
		Lobby waitingLobby = new Lobby();
		waitingLobby.setStatus("WAITING");
		waitingLobby.setPlayerIds(new ArrayList<>(List.of(3L, 4L))); 

		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(waitingLobby));

		// 2. Action
		String result = lobbyService.getWaitingSessionIdIfPlayerInLobby(1L, 2L);

		// 3. Assertion: Filter fails on the second condition
		assertNull(result);
	}

	@Test
	public void getWaitingSessionIdIfPlayerInLobby_validWaitingLobbyWithUser_returnsSessionId() {
		// 1. Setup: Perfect match
		Lobby validLobby = new Lobby();
		validLobby.setSessionId("EXPECTED-SESSION-ID");
		validLobby.setStatus("WAITING");
		validLobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));

		Mockito.when(lobbyRepository.findById(1L)).thenReturn(Optional.of(validLobby));

		// 2. Action
		String result = lobbyService.getWaitingSessionIdIfPlayerInLobby(1L, 2L);

		// 3. Assertion: Stream successfully maps the session string
		assertEquals("EXPECTED-SESSION-ID", result);
	}

	@Test
	public void getMyWaitingLobbyAsHost_invalidToken_throwsUnauthorized() {
		// 1. Setup: Invalid token returns null user
		Mockito.when(userRepository.findByToken("bad-token")).thenReturn(null);

		// 2. Action & 3. Assertion: The private getUserByToken helper throws a 401
		ResponseStatusException ex = assertThrows(ResponseStatusException.class, 
				() -> lobbyService.getMyWaitingLobbyAsHost("bad-token"));
		
		assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
	}

	@Test
	public void getMyWaitingLobbyAsHost_foundAsParticipant_returnsMaxIdLobby() {
		// 1. Setup: Valid user
		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);

		// Create lobbies to test the max() comparator (Removed the null ID lobby)
		Lobby olderLobby = new Lobby();
		olderLobby.setId(10L);
		
		Lobby newerLobby = new Lobby();
		newerLobby.setId(50L);

		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L))
			   .thenReturn(List.of(olderLobby, newerLobby));

		// 2. Action
		Lobby result = lobbyService.getMyWaitingLobbyAsHost("token");

		// 3. Assertion: It correctly grabs the one with the highest ID (50L)
		assertEquals(50L, result.getId());
	}
	
	@Test
	public void getMyWaitingLobbyAsHost_notFoundAsParticipant_foundAsHost_returnsLobby() {
		// 1. Setup: Valid user
		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);

		// Fails the first check
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L))
			   .thenReturn(List.of());

		// Create lobbies for the fallback check (host check)
		Lobby playingLobby = new Lobby();
		playingLobby.setId(20L);
		playingLobby.setStatus("PLAYING"); // Should be filtered out

		Lobby hostWaitingLobby = new Lobby();
		hostWaitingLobby.setId(30L);
		hostWaitingLobby.setStatus("WAITING"); // The target

		Mockito.when(lobbyRepository.findBySessionHostUserId(1L))
			   .thenReturn(List.of(playingLobby, hostWaitingLobby));

		// 2. Action
		Lobby result = lobbyService.getMyWaitingLobbyAsHost("token");

		// 3. Assertion: It successfully fell back and filtered out the PLAYING lobby
		assertEquals(30L, result.getId());
	}

	@Test
	public void getMyWaitingLobbyAsHost_noWaitingLobbies_throwsNotFound() {
		// 1. Setup: Valid user
		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);

		// Fails both checks
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L))
			   .thenReturn(List.of());
		Mockito.when(lobbyRepository.findBySessionHostUserId(1L))
			   .thenReturn(List.of());

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class, 
				() -> lobbyService.getMyWaitingLobbyAsHost("token"));
		
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
		assertTrue(ex.getReason().contains("No waiting lobby"));
	}

	@Test
	public void getWaitingLobbyView_notPlayerNotSpectator_throwsForbidden() {
		// 1. Setup: A complete stranger tries to view the lobby
		User stranger = new User();
		stranger.setId(99L);
		Mockito.when(userRepository.findByToken("stranger-token")).thenReturn(stranger);

		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L))); // Stranger is not a player
		lobby.setSpectatorIds(new ArrayList<>(List.of(2L))); // Stranger is not a spectator
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action & 3. Assertion: Hits the "if (!isPlayer && !isSpectator)" block
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.getWaitingLobbyView("stranger-token", "S1"));
		
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Not part of this lobby"));
	}

	@Test
	public void getWaitingLobbyView_assignedColorsDiffer_savesAndUsesPersistedLobby() {
		// 1. Setup: Host exists, but the lobby is missing its assigned colors map
		User host = new User();
		host.setId(1L);
		host.setPreferredColorPriority(new ArrayList<>(List.of("navy_blue")));
		
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);
		Mockito.when(userRepository.findAllById(Mockito.any())).thenReturn(List.of(host));

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		// Force the map to be null so the method has to compute and save new colors
		lobby.setAssignedCharacterColorByUserId(null); 

		Lobby persistedLobby = new Lobby();
		persistedLobby.setId(10L);
		persistedLobby.setSessionId("S1");
		persistedLobby.setSessionHostUserId(1L);
		persistedLobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		persistedLobby.setAssignedCharacterColorByUserId(Map.of(1L, "navy_blue"));

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		// Mock the repository to return our persistedLobby, hitting the 'if (persistedLobby != null)' branch
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenReturn(persistedLobby);

		// 2. Action
		WaitingLobbyViewDTO dto = lobbyService.getWaitingLobbyView("token", "S1");

		// 3. Assertion: The colors were successfully mapped, saved, and retrieved
		assertEquals(1, dto.getPlayers().size());
		assertEquals("navy_blue", dto.getPlayers().get(0).getCharacterColorId());
	}

	@Test
	public void getWaitingLobbyView_playerReadyMapIsNull_handlesSafely() {
		// 1. Setup: A spectator views an empty lobby (meaning normalizeLobbyPlayerState might skip initializing the map)
		User spectator = new User();
		spectator.setId(2L);
		
		Mockito.when(userRepository.findByToken("token")).thenReturn(spectator);
		Mockito.when(userRepository.findAllById(Mockito.any())).thenReturn(List.of(spectator));

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setPlayerIds(new ArrayList<>()); // No players
		lobby.setSpectatorIds(new ArrayList<>(List.of(2L))); // Spectator allows access
		lobby.setPlayerReadyByUserId(null); // Explicitly null to hit the ternary fallback

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// 2. Action
		WaitingLobbyViewDTO dto = lobbyService.getWaitingLobbyView("token", "S1");

		// 3. Assertion: Method executes safely without throwing a NullPointerException
		assertTrue(dto.getPlayers().isEmpty());
		assertEquals(1, dto.getSpectators().size());
	}

	@Test
	public void setPlayerReady_readyIsNull_throwsBadRequest() {
		// 1. Action & 2. Assertion: Fails on the very first line
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.setPlayerReady("S1", "token", null));
		
		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Missing ready state"));
	}

	@Test
	public void setPlayerReady_lobbyNotFound_throwsNotFound() {
		// 1. Setup
		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(null);

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.setPlayerReady("S1", "token", true));
		
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
	}

	@Test
	public void setPlayerReady_lobbyNotWaiting_throwsConflict() {
		// 1. Setup: Lobby is active
		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);
		
		Lobby lobby = new Lobby();
		lobby.setStatus("PLAYING");
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.setPlayerReady("S1", "token", true));
		
		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
	}

	@Test
	public void setPlayerReady_userNotPlayer_throwsForbidden() {
		// 1. Setup: User is trying to ready up, but they aren't in the player list
		User stranger = new User();
		stranger.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(stranger);
		
		Lobby lobby = new Lobby();
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L))); // Missing 2L
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.setPlayerReady("S1", "token", true));
		
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
	}

	@Test
	public void setPlayerReady_userIsHost_skipsSaveAndReturnsView() {
		// 1. Setup: Host toggles ready state
		User host = new User();
		host.setId(1L);
		host.setUsername("HostUser");
		
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(host));
		
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		// Allow any side-effect saves that might be triggered by view normalization
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// 2. Action
		WaitingLobbyViewDTO result = lobbyService.setPlayerReady("S1", "token", true);

		// 3. Assertion: Verify the view DTO was returned successfully
		assertNotNull(result);
		
		// Verify that the regular player broadcast block was completely bypassed
		Mockito.verify(lobbyEventPublisher, Mockito.never())
               .broadcastLobbyUpdate(Mockito.anyLong(), Mockito.any());
	}

	@Test
	public void setPlayerReady_playerReadyMapIsNull_createsMapAndSaves() {
		// 1. Setup: Guest toggles state on a fresh lobby with no ready-map
		User host = new User();
		host.setId(1L);
		
		User guest = new User();
		guest.setId(2L);
		guest.setUsername("GuestUser");
		
		Mockito.when(userRepository.findByToken("token")).thenReturn(guest);
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(host, guest));
		
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setPlayerReadyByUserId(null); 
		
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// 2. Action
		WaitingLobbyViewDTO result = lobbyService.setPlayerReady("S1", "token", true);

		// 3. Assertion: It created the map, updated it, and saved at least once
		assertNotNull(result);
		assertTrue(lobby.getPlayerReadyByUserId().get(2L));
		Mockito.verify(lobbyRepository, Mockito.atLeastOnce()).save(lobby);
		Mockito.verify(lobbyEventPublisher).broadcastLobbyUpdate(10L, lobby);
	}

	@Test
	public void setPlayerReady_playerReadyMapExists_updatesMapAndSaves() {
		// 1. Setup: Guest updates their existing ready state
		User host = new User();
		host.setId(1L);
		
		User guest = new User();
		guest.setId(2L);
		guest.setUsername("GuestUser");
		
		Mockito.when(userRepository.findByToken("token")).thenReturn(guest);
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(host, guest));
		
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		
		Map<Long, Boolean> existingMap = new HashMap<>();
		existingMap.put(2L, false); 
		lobby.setPlayerReadyByUserId(existingMap);
		
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// 2. Action: Set to true
		WaitingLobbyViewDTO result = lobbyService.setPlayerReady("S1", "token", true);

		// 3. Assertion: The map was mutated and saved at least once
		assertNotNull(result);
		assertTrue(lobby.getPlayerReadyByUserId().get(2L)); 
		Mockito.verify(lobbyRepository, Mockito.atLeastOnce()).save(lobby);
		Mockito.verify(lobbyEventPublisher).broadcastLobbyUpdate(10L, lobby);
	}

	@Test
	public void updateLobbySettings_bodyIsNull_throwsBadRequest() {
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.updateLobbySettings("token", "S1", null));
		
		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertTrue(ex.getReason().contains("No settings to update"));
	}

	@Test
	public void updateLobbySettings_bodyHasNoSettings_throwsBadRequest() {
		// 1. Setup: DTO is completely empty
		LobbySettingsPatchDTO emptyBody = new LobbySettingsPatchDTO();

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.updateLobbySettings("token", "S1", emptyBody));
		
		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
	}

	@Test
	public void updateLobbySettings_lobbyNotFound_throwsNotFound() {
		// 1. Setup
		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(null);

		LobbySettingsPatchDTO body = new LobbySettingsPatchDTO();
		body.setIsPublic(false);

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.updateLobbySettings("token", "S1", body));
		
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
	}

	@Test
	public void updateLobbySettings_lobbyNotWaiting_throwsConflict() {
		// 1. Setup
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("PLAYING"); // Not WAITING
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		LobbySettingsPatchDTO body = new LobbySettingsPatchDTO();
		body.setIsPublic(false);

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.updateLobbySettings("token", "S1", body));
		
		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Invalid lobby settings update"));
	}

	@Test
	public void updateLobbySettings_updatesAllTimers_clampsToMinimums() {
		// 1. Setup: Mock the absent round points which aren't in the global setup
		Mockito.when(lobbySettingsProperties.getAbsentRoundPointsMin()).thenReturn(0L);
		Mockito.when(lobbySettingsProperties.getAbsentRoundPointsMax()).thenReturn(50L);
		Mockito.when(lobbySettingsProperties.getAbsentRoundPointsDefault()).thenReturn(10L);

		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// 2. Setup DTO with values WAY below the minimums
		LobbySettingsPatchDTO body = new LobbySettingsPatchDTO();
		body.setAfkTimeoutSeconds(0L);
		body.setInitialPeekSeconds(0L);
		body.setTurnSeconds(0L);
		body.setAbilityRevealSeconds(0L);
		body.setAbilitySwapSeconds(0L);
		body.setAbsentRoundPoints(-10L);
		body.setWebsocketGraceSeconds(0L);
		body.setChatCooldownSeconds(0L);

		// 3. Action
		Lobby updated = lobbyService.updateLobbySettings("token", "S1", body);

		// 4. Assertion: Verify everything was clamped up to its minimum limit
		assertEquals(180L, updated.getAfkTimeoutSeconds());
		assertEquals(3L, updated.getInitialPeekSeconds());
		assertEquals(10L, updated.getTurnSeconds());
		assertEquals(3L, updated.getAbilityRevealSeconds());
		assertEquals(5L, updated.getAbilitySwapSeconds());
		assertEquals(0L, updated.getAbsentRoundPoints());
		assertEquals(180L, updated.getWebsocketGraceSeconds());
		assertEquals(1L, updated.getChatCooldownSeconds());
		
		Mockito.verify(lobbyEventPublisher).broadcastLobbyUpdate(10L, updated);
	}

	@Test
	public void updateLobbySettings_updatesAllTimers_clampsToMaximums() {
		// 1. Setup: Mock the absent round points
		Mockito.when(lobbySettingsProperties.getAbsentRoundPointsMin()).thenReturn(0L);
		Mockito.when(lobbySettingsProperties.getAbsentRoundPointsMax()).thenReturn(50L);
		Mockito.when(lobbySettingsProperties.getAbsentRoundPointsDefault()).thenReturn(10L);

		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any(Lobby.class))).thenAnswer(inv -> inv.getArgument(0));

		// 2. Setup DTO with values WAY above the maximums
		LobbySettingsPatchDTO body = new LobbySettingsPatchDTO();
		body.setAfkTimeoutSeconds(9999L);
		body.setInitialPeekSeconds(9999L);
		body.setTurnSeconds(9999L);
		body.setAbilityRevealSeconds(9999L);
		body.setAbilitySwapSeconds(9999L);
		body.setAbsentRoundPoints(9999L);
		body.setWebsocketGraceSeconds(9999L);
		body.setChatCooldownSeconds(9999L);

		// 3. Action
		Lobby updated = lobbyService.updateLobbySettings("token", "S1", body);

		// 4. Assertion: Verify everything was clamped down to its maximum limit
		assertEquals(1200L, updated.getAfkTimeoutSeconds());
		assertEquals(60L, updated.getInitialPeekSeconds());
		assertEquals(60L, updated.getTurnSeconds());
		assertEquals(10L, updated.getAbilityRevealSeconds());
		assertEquals(30L, updated.getAbilitySwapSeconds());
		assertEquals(50L, updated.getAbsentRoundPoints());
		assertEquals(600L, updated.getWebsocketGraceSeconds());
		assertEquals(60L, updated.getChatCooldownSeconds());
	}

	@Test
	public void joinLobby_lobbyNotWaiting_throwsConflict() {
		// 1. Setup: Valid user
		User joiner = new User();
		joiner.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(joiner);

		// Lobby is active (PLAYING) instead of WAITING
		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setStatus("PLAYING"); 
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action & 3. Assertion: Hits the status check exception
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.joinLobby("S1", "token"));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Lobby is not in waiting state"));
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void joinLobby_alreadyInLobby_throwsConflict() {
		// 1. Setup: User is already listed in the lobby's player list
		User joiner = new User();
		joiner.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(joiner);

		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L))); // User 2L is already here

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action & 3. Assertion: Hits the duplicate player check exception
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.joinLobby("S1", "token"));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Already in lobby"));
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void joinLobby_userWasKicked_throwsForbidden() {
		// 1. Setup: User belongs to the kicked list
		User joiner = new User();
		joiner.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(joiner);

		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		// Populating the list ensures we satisfy both "getKickedUserIds() != null" and ".contains(user.getId())"
		lobby.setKickedUserIds(new ArrayList<>(List.of(2L))); 

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action & 3. Assertion: Hits the ban validation check exception
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.joinLobby("S1", "token"));

		assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
		assertTrue(ex.getReason().contains("You were kicked from this lobby"));
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void joinLobbyAsSpectator_lobbyStatusInvalid_throwsConflict() {
		// 1. Setup: User is valid
		User user = new User();
		user.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);

		// Lobby has an invalid status (e.g., ENDED)
		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setStatus("ENDED"); 
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action & 3. Assertion: Hits the status check exception
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.joinLobbyAsSpectator("S1", "token"));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Lobby is not joinable as spectator"));
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void joinLobbyAsSpectator_userWasKicked_throwsForbidden() {
		// 1. Setup
		User user = new User();
		user.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);

		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setKickedUserIds(new ArrayList<>(List.of(2L))); // User 2L is banned

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.joinLobbyAsSpectator("S1", "token"));

		assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
		assertTrue(ex.getReason().contains("You were kicked from this lobby"));
	}

	@Test
	public void joinLobbyAsSpectator_userIsAlreadyPlayer_throwsConflict() {
		// 1. Setup: A user attempts to spectate a lobby they are actively playing in
		User user = new User();
		user.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);

		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setStatus("PLAYING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L))); // Already a player

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.joinLobbyAsSpectator("S1", "token"));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Already a player in this lobby"));
	}

	@Test
	public void joinLobbyAsSpectator_userInActiveGameElsewhere_throwsConflict() {
		// 1. Setup
		User user = new User();
		user.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);

		Lobby lobby = new Lobby();
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// Mock active game check to return true for user 2L
		Game activeGame = new Game();
		activeGame.setStatus(GameStatus.ROUND_ACTIVE);
		activeGame.setOrderedPlayerIds(new ArrayList<>(List.of(2L)));
		Mockito.when(lobbyRepository.existsByStatusAndPlayerId("PLAYING", 2L)).thenReturn(true);
		Mockito.when(gameRepository.findGamesByPlayerId(2L)).thenReturn(List.of(activeGame));

		// 2. Action & 3. Assertion
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.joinLobbyAsSpectator("S1", "token"));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Cannot spectate during an active game"));
	}

	@Test
	public void joinLobbyAsSpectator_alreadySpectator_rebroadcastsAndReturns() {
		// 1. Setup: User is already inside the spectator list
		User user = new User();
		user.setId(2L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(user);

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));
		lobby.setSpectatorIds(new ArrayList<>(List.of(2L))); // Already spectating

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		// 2. Action
		Lobby result = lobbyService.joinLobbyAsSpectator("S1", "token");

		// 3. Assertion: Bypasses mutation steps, broadcasts current state, and returns early
		assertNotNull(result);
		assertEquals(10L, result.getId());
		Mockito.verify(lobbyEventPublisher).broadcastLobbyUpdate(10L, lobby);
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void verifyLobbyCanStart_lobbyNotFound_throwsNotFound() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(null);

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.verifyLobbyCanStart("token", "S1"));
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
	}

	@Test
	public void verifyLobbyCanStart_statusNotWaiting_throwsConflict() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("PLAYING");
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.verifyLobbyCanStart("token", "S1"));
		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
	}

	@Test
	public void verifyLobbyCanStart_playerIdsNull_throwsBadRequest() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(null); // Explicitly null to hit the first half of the OR condition
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.verifyLobbyCanStart("token", "S1"));
		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertTrue(ex.getReason().contains("At least 2 players are required"));
	}

	@Test
	public void verifyLobbyCanStart_notEnoughPlayers_throwsBadRequest() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L))); // Only 1 player (Host)
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.verifyLobbyCanStart("token", "S1"));
		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
	}

	@Test
	public void verifyLobbyCanStart_playerNotReady_throwsBadRequest() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setPlayerReadyByUserId(null); 

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		
		// FIX: Mock the save method to return the lobby instead of null!
		Mockito.when(lobbyRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.verifyLobbyCanStart("token", "S1"));
		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertTrue(ex.getReason().contains("All non-host players must be ready"));
	}

	@Test
	public void verifyLobbyCanStart_playerInGracePeriod_noFreshHeartbeat_throwsBadRequest() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setPlayerReadyByUserId(Map.of(1L, true, 2L, true));

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		
		// FIX: Mock the save method here as well
		Mockito.when(lobbyRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));
		
		Mockito.when(disconnectService.isPlayerInGracePeriod(2L)).thenReturn(true);

		User guest = new User();
		guest.setId(2L);
		guest.setLastHeartbeat(java.time.Instant.now().minusSeconds(100));
		Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(guest));

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.verifyLobbyCanStart("token", "S1"));
		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Player disconnected"));
	}

	@Test
	public void verifyLobbyCanStart_playerInGracePeriod_withFreshHeartbeat_cancelsTimerAndSucceeds() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(host);

		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionHostUserId(1L);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setPlayerReadyByUserId(Map.of(1L, true, 2L, true));

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));
		
		// Flag the guest as being in the disconnect grace period
		Mockito.when(disconnectService.isPlayerInGracePeriod(2L)).thenReturn(true);

		// Mock guest (2L) with a FRESH heartbeat
		User guest = new User();
		guest.setId(2L);
		guest.setLastHeartbeat(java.time.Instant.now());
		Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(guest));

		// Action
		Lobby result = lobbyService.verifyLobbyCanStart("token", "S1");

		// Assertion
		assertNotNull(result);
		// Verify the stale flag was cleared successfully
		Mockito.verify(disconnectService).cancelDisconnectTimer(2L);
	}

	@Test
	public void handleRoundEndedForGamePlayers_oneArgWrapper_delegatesCorrectly() {
		// 1. Action: Call the 1-argument wrapper
		lobbyService.handleRoundEndedForGamePlayers(List.of(1L));

		// 2. Assertion: Verify it successfully delegated to the deeper method without crashing. 
		// Since we didn't mock an active PLAYING lobby for this user, the downstream method gracefully exits.
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void handleRoundResolvedForGamePlayers_twoArgsWrapper_delegatesCorrectly() {
		// 1. Action: Call the 2-argument wrapper
		lobbyService.handleRoundResolvedForGamePlayers(List.of(1L), List.of(1L));

		// 2. Assertion: Verify it successfully delegated to the 3-argument version.
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void handleRoundResolvedForGamePlayers_nullOrEmptyGamePlayers_returnsEarly() {
		// 1. Action: Test both null and empty lists
		lobbyService.handleRoundResolvedForGamePlayers(null, List.of(), List.of(), null);
		lobbyService.handleRoundResolvedForGamePlayers(List.of(), List.of(), List.of(), null);

		// 2. Assertion: Verifies it exits before ever touching the database
		Mockito.verify(lobbyRepository, Mockito.never()).findByStatus(Mockito.anyString());
	}

	@Test
	public void handleRoundResolvedForGamePlayers_noMatchingLobby_clearsFlagsAndSetsUsersOnline() {
		// 1. Setup: No active PLAYING lobbies exist for these players
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of());

		User p1 = new User(); 
		p1.setId(1L); 
		p1.setStatus(UserStatus.PLAYING);
		
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(p1));
		Mockito.when(userRepository.saveAll(Mockito.anyIterable())).thenAnswer(inv -> inv.getArgument(0));

		// 2. Action
		lobbyService.handleRoundResolvedForGamePlayers(List.of(1L), List.of(), List.of(), null);

		// 3. Assertion: Hits the 'if (currentLobby == null)' block perfectly
		assertEquals(UserStatus.ONLINE, p1.getStatus());
		Mockito.verify(onlineUsersEventPublisher).broadcastOnlineUsers();
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void handleRoundResolvedForGamePlayers_partialLobbyMatch_selectsBestOverlap() {
		// 1. Setup: Create multiple lobbies to trigger the 'max()' comparator fallback logic.
		// Expected players: 1, 2
		Lobby zeroOverlap = new Lobby(); 
		zeroOverlap.setId(10L); 
		zeroOverlap.setPlayerIds(new ArrayList<>(List.of(3L, 4L))); // 0 overlapping players
		
		Lobby partialOverlap = new Lobby(); 
		partialOverlap.setId(20L); 
		partialOverlap.setPlayerIds(new ArrayList<>(List.of(1L, 3L))); // 1 overlapping player
		
		Lobby bestOverlap = new Lobby(); 
		bestOverlap.setId(30L); 
		bestOverlap.setPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L))); // 2 overlapping players (Winner!)

		Mockito.when(lobbyRepository.findByStatus("PLAYING"))
			   .thenReturn(List.of(zeroOverlap, partialOverlap, bestOverlap));
		Mockito.when(lobbyRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

		User p1 = new User(); p1.setId(1L);
		User p2 = new User(); p2.setId(2L);
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(p1, p2));

		// 2. Action: Provide 1,2 as continueRematch so we can track which lobby gets saved
		lobbyService.handleRoundResolvedForGamePlayers(List.of(1L, 2L), List.of(1L, 2L), List.of(), null);

		// 3. Assertion: Verify it correctly identified the 30L lobby as the 'currentLobby'
		ArgumentCaptor<Lobby> savedLobbyCaptor = ArgumentCaptor.forClass(Lobby.class);
		Mockito.verify(lobbyRepository).save(savedLobbyCaptor.capture());
		assertEquals(30L, savedLobbyCaptor.getValue().getId());
	}

	@Test
	public void handleRoundResolvedForGamePlayers_noRematches_deletesLobbyAndReleasesSpectatorsOnline() {
		// 1. Setup: Exact match lobby with a spectator
		Lobby exactMatch = new Lobby();
		exactMatch.setId(50L);
		exactMatch.setSessionId("S50");
		exactMatch.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		exactMatch.setSpectatorIds(new ArrayList<>(List.of(9L))); // Spectator to release!

		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(exactMatch));

		User p1 = new User(); p1.setId(1L);
		User p2 = new User(); p2.setId(2L);
		User spec = new User(); spec.setId(9L); spec.setStatus(UserStatus.SPECTATING);

		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(p1, p2, spec));
		Mockito.when(userRepository.saveAll(Mockito.anyIterable())).thenAnswer(inv -> inv.getArgument(0));

		// 2. Action: NO continue votes, NO fresh votes. The lobby dies.
		lobbyService.handleRoundResolvedForGamePlayers(List.of(1L, 2L), List.of(), List.of(), null);

		// 3. Assertions
		Mockito.verify(lobbyRepository).delete(exactMatch); // Verify it hits the 'else' block to delete
		
		// Verify the specific block '!spectatorsToReleaseOnline.isEmpty()' was hit 
		// and the spectator's status was safely downgraded to ONLINE
		assertEquals(UserStatus.ONLINE, spec.getStatus());
	}

	@Test
	public void resolvePlayingAssignedCharacterColorsForPlayers_noLobbyOrEmptyPlayers_returnsEmpty() {
		// 1. Setup: No PLAYING lobbies at all
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of());
		assertTrue(lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(List.of(1L)).isEmpty());

		// 2. Setup: Lobby exists, but playerIds list is null
		Lobby lobbyWithNullPlayers = new Lobby();
		lobbyWithNullPlayers.setPlayerIds(null);
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobbyWithNullPlayers));
		assertTrue(lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(List.of(1L)).isEmpty());

		// 3. Setup: Lobby exists, but playerIds list is empty
		Lobby lobbyWithEmptyPlayers = new Lobby();
		lobbyWithEmptyPlayers.setPlayerIds(new ArrayList<>());
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobbyWithEmptyPlayers));
		assertTrue(lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(List.of(1L)).isEmpty());
	}

	@Test
	public void resolvePlayingAssignedCharacterColorsForPlayers_existingColorsValid_noSave() {
		// 1. Setup: Lobby has valid, game-approved colors assigned
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		
		Map<Long, String> existingMap = new HashMap<>();
		existingMap.put(1L, "red");
		// FIX: Use the exact recognized internal color string
		existingMap.put(2L, "navy_blue"); 
		lobby.setAssignedCharacterColorByUserId(existingMap);
		
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby));
		
		User host = new User(); host.setId(1L);
		User guest = new User(); guest.setId(2L);
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(host, guest));

		// 2. Action
		Map<Long, String> result = lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(List.of(1L, 2L));

		// 3. Assertion: Colors stayed exactly the same, and .save() was NEVER called
		assertEquals("red", result.get(1L));
		assertEquals("navy_blue", result.get(2L));
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void resolvePlayingAssignedCharacterColorsForPlayers_mapIsNull_assignsPrefsAndSaves() {
		// 1. Setup: Brand new lobby where the color map is explicitly null
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setAssignedCharacterColorByUserId(null); 
		
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby));
		
		// FIX: Use exact game-approved internal color strings for their preferences
		User host = new User(); 
		host.setId(1L); 
		host.setPreferredColorPriority(new ArrayList<>(List.of("light_green")));
		
		User guest = new User(); 
		guest.setId(2L); 
		guest.setPreferredColorPriority(new ArrayList<>(List.of("navy_blue")));
		
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(host, guest));
		
		// 2. Action
		Map<Long, String> result = lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(List.of(1L, 2L));

		// 3. Assertion: Map was generated based on preferences and saved
		assertEquals("light_green", result.get(1L));
		assertEquals("navy_blue", result.get(2L));
		Mockito.verify(lobbyRepository, Mockito.atLeastOnce()).save(lobby);
	}
	
	@Test
	public void resolvePlayingAssignedCharacterColorsForPlayers_conflictingPrefs_usesGlobalFallbackAndSaves() {
		// 1. Setup
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setAssignedCharacterColorByUserId(new HashMap<>());
		
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby));
		
		// BOTH users desperately want "red"
		User host = new User(); host.setId(1L); host.setPreferredColorPriority(new ArrayList<>(List.of("red")));
		User guest = new User(); guest.setId(2L); guest.setPreferredColorPriority(new ArrayList<>(List.of("red")));
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(host, guest));
		
		// 2. Action
		Map<Long, String> result = lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(List.of(1L, 2L));

		// 3. Assertion: Host gets red. Guest gets bumped to the first available in CHARACTER_COLOR_ORDER
		assertEquals("red", result.get(1L));
		assertNotEquals("red", result.get(2L)); 
		assertNotNull(result.get(2L)); 
		Mockito.verify(lobbyRepository, Mockito.atLeastOnce()).save(lobby);
	}

	@Test
	public void resolvePlayingAssignedCharacterColorsForPlayers_colorsExhausted_hitsAbsoluteFailsafe() {
		// 1. Setup: Create a massive lobby (e.g., 20 players) to mathematically exhaust the CHARACTER_COLOR_ORDER list
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionHostUserId(1L);
		
		List<Long> manyPlayers = new ArrayList<>();
		List<User> userMocks = new ArrayList<>();
		
		for (long i = 1L; i <= 20L; i++) {
			manyPlayers.add(i);
			User u = new User(); u.setId(i); u.setPreferredColorPriority(new ArrayList<>());
			userMocks.add(u);
		}
		
		lobby.setPlayerIds(manyPlayers);
		lobby.setAssignedCharacterColorByUserId(new HashMap<>());
		
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby));
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(userMocks);
		
		// 2. Action
		Map<Long, String> result = lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(manyPlayers);

		// 3. Assertion: Hits the final `selectedColor = CHARACTER_COLOR_ORDER.get(0);` after unique colors run out
		assertEquals(20, result.size());
		assertNotNull(result.get(20L));
		Mockito.verify(lobbyRepository, Mockito.atLeastOnce()).save(lobby);
	}

	@Test
	public void findPlayingSpectatorIdsForPlayers_noMatchingLobby_returnsEmptyList() {
		// 1. Setup: No PLAYING lobbies available
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of());

		// 2. Action
		List<Long> result = lobbyService.findPlayingSpectatorIdsForPlayers(List.of(1L));

		// 3. Assertion: Fails the matchingLobby == null check
		assertTrue(result.isEmpty());
	}

	@Test
	public void findPlayingSpectatorIdsForPlayers_spectatorListNull_returnsEmptyList() {
		// 1. Setup: Lobby matches, but spectatorIds is null
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setSpectatorIds(null); // Explicitly hits the second half of the OR condition
		
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby));

		// 2. Action
		List<Long> result = lobbyService.findPlayingSpectatorIdsForPlayers(List.of(1L, 2L));

		// 3. Assertion
		assertTrue(result.isEmpty());
	}

	@Test
	public void findPlayingSpectatorIdsForPlayers_hasMessySpectators_filtersNullsAndDuplicates() {
		// 1. Setup: Lobby matches perfectly
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		
		// Create a messy list with duplicates and a null value
		// Note: We use Arrays.asList because List.of() crashes if you try to put a null inside it!
		lobby.setSpectatorIds(new ArrayList<>(java.util.Arrays.asList(3L, 3L, null, 4L)));
		
		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby));

		// 2. Action
		List<Long> result = lobbyService.findPlayingSpectatorIdsForPlayers(List.of(1L, 2L));

		// 3. Assertion: Stream successfully stripped the null and the extra 3L
		assertEquals(2, result.size());
		assertTrue(result.contains(3L));
		assertTrue(result.contains(4L));
	}

	@Test
	public void resolvePlayingLobbySnapshotForPlayers_returnsSessionColorsAndDistinctSpectators() {
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("PLAY-ABCD");
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setSpectatorIds(new ArrayList<>(java.util.Arrays.asList(3L, null, 3L, 4L)));
		lobby.setAssignedCharacterColorByUserId(new HashMap<>(Map.of(
				1L, "navy_blue",
				2L, "light_blue")));

		Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(lobby));
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of());

		LobbyService.PlayingLobbySnapshot snapshot =
				lobbyService.resolvePlayingLobbySnapshotForPlayers(List.of(1L, 2L));

		assertNotNull(snapshot);
		assertEquals("PLAY-ABCD", snapshot.getSessionId());
		assertEquals(Map.of(1L, "navy_blue", 2L, "light_blue"), snapshot.getAssignedCharacterColorsByUserId());
		assertEquals(List.of(3L, 4L), snapshot.getSpectatorIds());
	}

	@Test
	public void refreshWaitingLobbyPresentationForUser_userIdNull_returnsEarly() {
		// 1. Action
		lobbyService.refreshWaitingLobbyPresentationForUser(null);

		// 2. Assertion: Never hits the repository
		Mockito.verify(lobbyRepository, Mockito.never()).findByStatusAndParticipantId(Mockito.anyString(), Mockito.anyLong());
	}

	@Test
	public void refreshWaitingLobbyPresentationForUser_noAffectedLobbies_returnsEarly() {
		// 1. Setup: User has no active WAITING lobbies
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L)).thenReturn(List.of());

		// 2. Action
		lobbyService.refreshWaitingLobbyPresentationForUser(1L);

		// 3. Assertion: Never loops, never saves
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void refreshWaitingLobbyPresentationForUser_lobbyStateNeedsFix_savesAndBroadcasts() {
		// 1. Setup: A broken lobby (missing its ready map)
		Lobby brokenLobby = new Lobby();
		brokenLobby.setId(10L);
		brokenLobby.setSessionHostUserId(1L);
		brokenLobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		brokenLobby.setPlayerReadyByUserId(null); // Forces normalizeLobbyPlayerStateInPlace to return true

		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L)).thenReturn(List.of(brokenLobby));
		Mockito.when(lobbyRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

		// 2. Action
		lobbyService.refreshWaitingLobbyPresentationForUser(1L);

		// 3. Assertion: Successfully bypasses 'continue', saves, and broadcasts
		Mockito.verify(lobbyRepository).save(brokenLobby);
		Mockito.verify(lobbyEventPublisher).broadcastLobbyUpdate(10L, brokenLobby);
	}

	@Test
	public void getPublicLobbies_noLobbies_returnsEmpty() {
		// 1. Setup
		User requester = new User();
		requester.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(requester);
		Mockito.when(lobbyRepository.findByIsPublicTrueAndStatus("WAITING")).thenReturn(List.of());

		// 2. Action
		List<Lobby> result = lobbyService.getPublicLobbies("token");

		// 3. Assertion
		assertTrue(result.isEmpty());
	}

	@Test
	public void getPublicLobbies_filtersOutFullLobbies() {
		// 1. Setup
		User requester = new User();
		requester.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(requester);

		Lobby fullLobby = new Lobby();
		fullLobby.setPlayerIds(new ArrayList<>(List.of(2L, 3L, 4L, 5L))); // 4 players means it's full
		
		Mockito.when(lobbyRepository.findByIsPublicTrueAndStatus("WAITING")).thenReturn(List.of(fullLobby));

		// 2. Action
		List<Lobby> result = lobbyService.getPublicLobbies("token");

		// 3. Assertion: The first filter caught it and removed it
		assertTrue(result.isEmpty());
	}

	@Test
	public void getPublicLobbies_filtersOutKickedLobbies() {
		// 1. Setup
		User requester = new User();
		requester.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(requester);

		Lobby kickedLobby = new Lobby();
		kickedLobby.setPlayerIds(new ArrayList<>(List.of(2L))); // Only 1 player, so it passes the first filter
		kickedLobby.setKickedUserIds(new ArrayList<>(List.of(1L))); // Requester is kicked!
		
		Mockito.when(lobbyRepository.findByIsPublicTrueAndStatus("WAITING")).thenReturn(List.of(kickedLobby));

		// 2. Action
		List<Lobby> result = lobbyService.getPublicLobbies("token");

		// 3. Assertion: The second filter caught it and removed it
		assertTrue(result.isEmpty());
	}

	@Test
	public void getPublicLobbies_returnsValidLobbies_handlesNullAndEmptyKickedLists() {
		// 1. Setup
		User requester = new User();
		requester.setId(1L);
		Mockito.when(userRepository.findByToken("token")).thenReturn(requester);

		// Valid lobby 1: Tests the "getKickedUserIds() == null" half of the OR condition
		Lobby validNullKicked = new Lobby();
		validNullKicked.setId(10L);
		validNullKicked.setPlayerIds(new ArrayList<>(List.of(2L))); 
		validNullKicked.setKickedUserIds(null);

		// Valid lobby 2: Tests the "!l.getKickedUserIds().contains(requester.getId())" half
		Lobby validOtherKicked = new Lobby();
		validOtherKicked.setId(20L);
		validOtherKicked.setPlayerIds(new ArrayList<>(List.of(2L, 3L)));
		validOtherKicked.setKickedUserIds(new ArrayList<>(List.of(99L))); // Someone else is kicked

		Mockito.when(lobbyRepository.findByIsPublicTrueAndStatus("WAITING"))
			   .thenReturn(List.of(validNullKicked, validOtherKicked));

		// 2. Action
		List<Lobby> result = lobbyService.getPublicLobbies("token");

		// 3. Assertion: Both lobbies survived the filters
		assertEquals(2, result.size());
		assertEquals(10L, result.get(0).getId());
		assertEquals(20L, result.get(1).getId());
	}

	@Test
	public void getLobbyById_lobbyExists_returnsLobby() {
		// 1. Setup: Repository successfully finds the lobby
		Lobby expectedLobby = new Lobby();
		expectedLobby.setId(10L);
		expectedLobby.setSessionId("S1");

		Mockito.when(lobbyRepository.findById(10L)).thenReturn(Optional.of(expectedLobby));

		// 2. Action
		Lobby result = lobbyService.getLobbyById(10L);

		// 3. Assertion: The lobby is returned without throwing an exception
		assertNotNull(result);
		assertEquals(10L, result.getId());
		assertEquals("S1", result.getSessionId());
	}

	@Test
	public void getLobbyById_lobbyNotFound_throwsNotFound() {
		// 1. Setup: Repository returns an empty optional
		Mockito.when(lobbyRepository.findById(10L)).thenReturn(Optional.empty());

		// 2. Action & 3. Assertion: The orElseThrow triggers the expected exception
		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> lobbyService.getLobbyById(10L));

		assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
		assertTrue(ex.getReason().contains("Session could not be found!"));
	}

	@Test
	public void findLobbyById_nullId_returnsEmptyOptional() {
		// 1. Action
		Optional<Lobby> result = lobbyService.findLobbyById(null);

		// 2. Assertion: Returns empty and completely protects the repository
		assertTrue(result.isEmpty());
		Mockito.verify(lobbyRepository, Mockito.never()).findById(Mockito.any());
	}

	@Test
	public void findLobbyById_lobbyExists_returnsOptionalWithLobby() {
		// 1. Setup
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		Mockito.when(lobbyRepository.findById(10L)).thenReturn(Optional.of(lobby));

		// 2. Action
		Optional<Lobby> result = lobbyService.findLobbyById(10L);

		// 3. Assertion
		assertTrue(result.isPresent());
		assertEquals(10L, result.get().getId());
	}

	@Test
	public void findLobbyById_lobbyDoesNotExist_returnsEmptyOptional() {
		// 1. Setup
		Mockito.when(lobbyRepository.findById(10L)).thenReturn(Optional.empty());

		// 2. Action
		Optional<Lobby> result = lobbyService.findLobbyById(10L);

		// 3. Assertion
		assertTrue(result.isEmpty());
	}

	@Test
	public void handlePermanentDisconnect_noLobbiesFound_setsOfflineAndReturns() {
		// 1. Setup: User is not in any waiting or playing lobbies
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L)).thenReturn(List.of());
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L)).thenReturn(List.of());

		User user = new User();
		user.setId(1L);
		user.setStatus(UserStatus.ONLINE);
		Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(user));

		// 2. Action
		lobbyService.handlePermanentDisconnect(1L);

		// 3. Assertion: Properly set offline and broadcasted, no lobby saved
		assertEquals(UserStatus.OFFLINE, user.getStatus());
		Mockito.verify(onlineUsersEventPublisher).broadcastOnlineUsers();
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void handlePermanentDisconnect_waitingLobby_delegatesToRemovePlayer() {
		// 1. Setup: User is in a WAITING lobby
		Lobby waitingLobby = new Lobby();
		waitingLobby.setSessionId("S1");
		waitingLobby.setStatus("WAITING");
		
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L)).thenReturn(List.of(waitingLobby));
		
		// Mock findBySessionId to prevent the downstream 'removePlayerFromDisconnect' from throwing an NPE
		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(waitingLobby);

		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(user));

		// 2. Action
		lobbyService.handlePermanentDisconnect(1L);

		// 3. Assertion: Handled status update (removePlayerFromDisconnect will handle the rest)
		assertEquals(UserStatus.OFFLINE, user.getStatus());
	}

	@Test
	public void handlePermanentDisconnect_playingLobbyAsPlayer_flagsTimeoutOnly() {
		// 1. Setup: User is an active PLAYER in a PLAYING lobby
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L)).thenReturn(List.of());

		Lobby playingLobby = new Lobby();
		playingLobby.setId(10L);
		playingLobby.setStatus("PLAYING");
		playingLobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L))); // User 1L is a player
		
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L)).thenReturn(List.of(playingLobby));

		// 2. Action
		lobbyService.handlePermanentDisconnect(1L);

		// 3. Assertion: Because they are an active player, they are NOT set to offline immediately
		// They are just added to the internal timeout flag set, so setUserStatus is never called!
		Mockito.verify(userRepository, Mockito.never()).findById(Mockito.anyLong()); 
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
		Mockito.verify(onlineUsersEventPublisher).broadcastOnlineUsers();
	}

	@Test
	public void handlePermanentDisconnect_playingLobbyAsSpectator_removesAndSaves() {
		// 1. Setup: User is a SPECTATOR in a PLAYING lobby
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L)).thenReturn(List.of());

		Lobby playingLobby = new Lobby();
		playingLobby.setId(10L);
		playingLobby.setStatus("PLAYING");
		playingLobby.setPlayerIds(new ArrayList<>(List.of(2L, 3L))); // 1L is NOT a player
		playingLobby.setSpectatorIds(new ArrayList<>(List.of(1L)));  // 1L is a spectator
		
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L)).thenReturn(List.of(playingLobby));

		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(user));

		// 2. Action
		lobbyService.handlePermanentDisconnect(1L);

		// 3. Assertion: Spectator is booted out, status updated, and lobby saved
		assertFalse(playingLobby.getSpectatorIds().contains(1L));
		assertEquals(UserStatus.OFFLINE, user.getStatus());
		Mockito.verify(lobbyRepository).save(playingLobby);
		Mockito.verify(lobbyEventPublisher).broadcastLobbyUpdate(10L, playingLobby);
		Mockito.verify(onlineUsersEventPublisher).broadcastOnlineUsers();
	}

	@Test
	public void handlePermanentDisconnect_playingLobbyNullLists_safelyHandledAsSpectator() {
		// 1. Setup: PLAYING lobby with completely null player and spectator lists
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L)).thenReturn(List.of());

		Lobby brokenLobby = new Lobby();
		brokenLobby.setId(10L);
		brokenLobby.setStatus("PLAYING");
		brokenLobby.setPlayerIds(null);    // Hits the '!= null' check for isPlayer
		brokenLobby.setSpectatorIds(null); // Hits the '!= null' check before removing spectator
		
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("PLAYING", 1L)).thenReturn(List.of(brokenLobby));

		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(user));

		// 2. Action
		lobbyService.handlePermanentDisconnect(1L);

		// 3. Assertion: Safely falls back to the spectator clean-up branch without throwing NPE
		assertEquals(UserStatus.OFFLINE, user.getStatus());
		Mockito.verify(lobbyRepository).save(brokenLobby);
		Mockito.verify(lobbyEventPublisher).broadcastLobbyUpdate(10L, brokenLobby);
	}

	@Test
	public void handlePermanentDisconnect_impossibleStatus_hitsElseBlock() {
		// 1. Setup: We trick the repository into returning an "ENDED" lobby when it searches for "WAITING".
		// This forces the code to fall all the way down to the final `else` block.
		Lobby endedLobby = new Lobby();
		endedLobby.setStatus("ENDED"); 
		Mockito.when(lobbyRepository.findByStatusAndParticipantId("WAITING", 1L)).thenReturn(List.of(endedLobby));

		User user = new User();
		user.setId(1L);
		Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(user));

		// 2. Action
		lobbyService.handlePermanentDisconnect(1L);

		// 3. Assertion: The final unreachable else block is hit perfectly
		assertEquals(UserStatus.OFFLINE, user.getStatus());
		Mockito.verify(onlineUsersEventPublisher).broadcastOnlineUsers();
		Mockito.verify(lobbyRepository, Mockito.never()).save(Mockito.any());
	}

	@Test
	public void removePlayerFromDisconnect_hostLeavesWithPlayersRemaining_reassignsHost() {
		// 1. Setup: WAITING lobby with 2 players. User 1L is the Host.
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setSpectatorIds(new ArrayList<>());
		lobby.setKickedUserIds(new ArrayList<>());
		
		// Perfect state maps to prevent normalization traps
		Map<Long, String> colors = new HashMap<>();
		colors.put(1L, "light_green"); colors.put(2L, "navy_blue");
		lobby.setAssignedCharacterColorByUserId(colors);
		
		Map<Long, Boolean> readyMap = new HashMap<>();
		readyMap.put(1L, true); readyMap.put(2L, true);
		lobby.setPlayerReadyByUserId(readyMap);

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

		User host = new User(); host.setId(1L);
		User guest = new User(); guest.setId(2L);
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(host, guest));

		// 2. Action: The HOST disconnects
		lobbyService.removePlayerFromDisconnect("S1", 1L);

		// 3. Assertion: Player 1 is removed, and Player 2 becomes the new Host!
		assertFalse(lobby.getPlayerIds().contains(1L));
		assertTrue(lobby.getPlayerIds().contains(2L));
		
		// Verify the specific "Uncovered code" block was hit:
		assertEquals(2L, lobby.getSessionHostUserId()); 
		
		Mockito.verify(lobbyRepository).save(lobby);
		Mockito.verify(lobbyEventPublisher).broadcastLobbyUpdate(10L, lobby);
		Mockito.verify(onlineUsersEventPublisher).broadcastOnlineUsers();
	}

	@Test
	public void removePlayerFromDisconnect_guestLeavesWithPlayersRemaining_keepsHost() {
		// 1. Setup: WAITING lobby with 2 players. User 1L is the Host.
		Lobby lobby = new Lobby();
		lobby.setId(10L);
		lobby.setSessionId("S1");
		lobby.setStatus("WAITING");
		lobby.setSessionHostUserId(1L);
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));
		lobby.setSpectatorIds(new ArrayList<>());
		lobby.setKickedUserIds(new ArrayList<>());
		
		Map<Long, String> colors = new HashMap<>();
		colors.put(1L, "light_green"); colors.put(2L, "navy_blue");
		lobby.setAssignedCharacterColorByUserId(colors);
		
		Map<Long, Boolean> readyMap = new HashMap<>();
		readyMap.put(1L, true); readyMap.put(2L, true);
		lobby.setPlayerReadyByUserId(readyMap);

		Mockito.when(lobbyRepository.findBySessionId("S1")).thenReturn(lobby);
		Mockito.when(lobbyRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

		User host = new User(); host.setId(1L);
		User guest = new User(); guest.setId(2L);
		Mockito.when(userRepository.findAllById(Mockito.anyIterable())).thenReturn(List.of(host, guest));

		// 2. Action: The GUEST disconnects
		lobbyService.removePlayerFromDisconnect("S1", 2L);

		// 3. Assertion: Guest removed, but Host is NOT reassigned
		assertFalse(lobby.getPlayerIds().contains(2L));
		assertEquals(1, lobby.getPlayerIds().size());
		assertEquals(1L, lobby.getSessionHostUserId()); // Host remains untouched
		
		Mockito.verify(lobbyRepository).save(lobby);
	}
}
