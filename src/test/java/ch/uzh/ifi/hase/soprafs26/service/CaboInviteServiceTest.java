package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.CaboInviteRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteCreateDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteSentDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInvitePendingDTO;
import ch.uzh.ifi.hase.soprafs26.constant.CaboInviteStatus;
import ch.uzh.ifi.hase.soprafs26.entity.CaboInvite;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Date;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

public class CaboInviteServiceTest {

	@Mock
	private CaboInviteRepository caboInviteRepository;

	@Mock
    private LobbyRepository lobbyRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private CaboInviteEventPublisher caboInviteEventPublisher;

	@Mock
	private LobbyService lobbyService;

	@InjectMocks
	private CaboInviteService caboInviteService;

	@BeforeEach
	public void setup() {
		MockitoAnnotations.openMocks(this);
	}

	@Test
	public void createInviteAsUser_noWaitingLobby_throwsConflict() {
		User host = new User();
		host.setId(1L);
		User invitee = new User();
		invitee.setId(2L);
		Mockito.when(userRepository.findByToken("token1")).thenReturn(host);
		Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(invitee));
		Mockito.when(lobbyService.requireWaitingLobbyForHost(1L))
				.thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Create a lobby first"));

		CaboInviteCreateDTO body = new CaboInviteCreateDTO();
		body.setToUserId(2L);

		assertThrows(ResponseStatusException.class,
				() -> caboInviteService.createInviteAsUser("token1", 1L, body));
	}

	@Test
	public void createInviteAsUser_inviteSelf_throwsBadRequest() {
		User host = new User();
		host.setId(1L);
		Mockito.when(userRepository.findByToken("token1")).thenReturn(host);

		CaboInviteCreateDTO body = new CaboInviteCreateDTO();
		body.setToUserId(1L);

		assertThrows(ResponseStatusException.class,
				() -> caboInviteService.createInviteAsUser("token1", 1L, body));
	}

	@Test
    void createInviteAsUser_whenSuccessful_populatesLobbyDetailsInPendingDto() {
        // Given
        String token = "host-token";
        Long hostId = 1L;
        Long inviteeId = 2L;
        Long lobbyId = 100L;

        User host = new User();
        host.setId(hostId);
        host.setUsername("hostUser");
        host.setName("Host Name");

        User invitee = new User();
        invitee.setId(inviteeId);

        Mockito.when(userRepository.findByToken(token)).thenReturn(host);
        Mockito.when(userRepository.findById(inviteeId)).thenReturn(Optional.of(invitee));

        Lobby waitingLobby = new Lobby();
        waitingLobby.setId(lobbyId);
        Mockito.when(lobbyService.requireWaitingLobbyForHost(hostId)).thenReturn(waitingLobby);

        Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(new ArrayList<>());

        Mockito.when(caboInviteRepository.findByFromUserIdAndLobbyIdOrderByCreatedAtDesc(hostId, lobbyId))
                .thenReturn(new ArrayList<>());

        Mockito.when(caboInviteRepository.findByFromUserIdAndToUserIdAndLobbyIdAndStatus(
                hostId, inviteeId, lobbyId, CaboInviteStatus.PENDING)).thenReturn(Optional.empty());

        CaboInvite savedInvite = new CaboInvite();
        savedInvite.setId(555L);
        savedInvite.setFromUserId(hostId);
        savedInvite.setToUserId(inviteeId);
        savedInvite.setLobbyId(lobbyId);
        savedInvite.setStatus(CaboInviteStatus.PENDING);
        savedInvite.setCreatedAt(java.time.LocalDateTime.now()); 
        
        Mockito.when(caboInviteRepository.save(any(CaboInvite.class))).thenReturn(savedInvite);

        Lobby structuralLobby = new Lobby();
        structuralLobby.setSessionId("session-xyz-123");
        structuralLobby.setSessionHostUserId(hostId);
        Mockito.when(lobbyService.findLobbyById(lobbyId)).thenReturn(Optional.of(structuralLobby));

        CaboInviteCreateDTO body = new CaboInviteCreateDTO();
        body.setToUserId(inviteeId);

        // Act
        CaboInvitePendingDTO resultDto = caboInviteService.createInviteAsUser(token, hostId, body);

        // Assert
        assertNotNull(resultDto);
        assertEquals(555L, resultDto.getId());
        assertEquals("hostUser", resultDto.getFromUsername());
        assertEquals("Host Name", resultDto.getFromName());
        assertNotNull(resultDto.getInviteCreationDate());
        assertEquals("session-xyz-123", resultDto.getSessionId());
        assertEquals(hostId, resultDto.getSessionHostUserId());

        Mockito.verify(caboInviteEventPublisher, Mockito.times(1))
                .publishToInviteeAfterCommit(Mockito.eq(inviteeId), any(CaboInvitePendingDTO.class));
    }

	@Test
    void createInviteAsUser_whenTargetUserIsCurrentlyPlaying_throwsConflict() {
        // Given
        String token = "host-token";
        Long hostId = 1L;
        Long inviteeId = 2L;

        User host = new User();
        host.setId(hostId);
        User invitee = new User();
        invitee.setId(inviteeId);

        Mockito.when(userRepository.findByToken(token)).thenReturn(host);
        Mockito.when(userRepository.findById(inviteeId)).thenReturn(Optional.of(invitee));

        Lobby waitingLobby = new Lobby();
        waitingLobby.setId(100L);
        Mockito.when(lobbyService.requireWaitingLobbyForHost(hostId)).thenReturn(waitingLobby);

        // Guard Condition: Mock a playing lobby that contains our target inviteeId
        Lobby activeLobby = new Lobby();
        activeLobby.setStatus("PLAYING");
        activeLobby.setPlayerIds(List.of(inviteeId));
        Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(List.of(activeLobby));

        CaboInviteCreateDTO body = new CaboInviteCreateDTO();
        body.setToUserId(inviteeId);

        // Act & Assert
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.createInviteAsUser(token, hostId, body));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("User is currently playing", ex.getReason());
    }

    @Test
    void createInviteAsUser_whenActiveInviteSlotsAreFull_throwsConflict() {
        // Given
        String token = "host-token";
        Long hostId = 1L;
        Long inviteeId = 2L;
        Long lobbyId = 100L;

        User host = new User();
        host.setId(hostId);
        User invitee = new User();
        invitee.setId(inviteeId);

        Mockito.when(userRepository.findByToken(token)).thenReturn(host);
        Mockito.when(userRepository.findById(inviteeId)).thenReturn(Optional.of(invitee));

        Lobby waitingLobby = new Lobby();
        waitingLobby.setId(lobbyId);
        Mockito.when(lobbyService.requireWaitingLobbyForHost(hostId)).thenReturn(waitingLobby);
        Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(new ArrayList<>());

        // Guard Condition: Mock 3 pre-existing active invites to other players (slots full)
        CaboInvite activeInv1 = new CaboInvite(); activeInv1.setToUserId(8L); activeInv1.setStatus(CaboInviteStatus.PENDING);
        CaboInvite activeInv2 = new CaboInvite(); activeInv2.setToUserId(9L); activeInv2.setStatus(CaboInviteStatus.ACCEPTED);
        CaboInvite activeInv3 = new CaboInvite(); activeInv3.setToUserId(10L); activeInv3.setStatus(CaboInviteStatus.PENDING);
        
        Mockito.when(caboInviteRepository.findByFromUserIdAndLobbyIdOrderByCreatedAtDesc(hostId, lobbyId))
                .thenReturn(List.of(activeInv1, activeInv2, activeInv3));

        CaboInviteCreateDTO body = new CaboInviteCreateDTO();
        body.setToUserId(inviteeId);

        // Act & Assert
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.createInviteAsUser(token, hostId, body));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Maximum of 3 active invites", ex.getReason());
    }

    @Test
    void createInviteAsUser_whenPendingInviteAlreadyExistsToSameTarget_throwsConflict() {
        // Given
        String token = "host-token";
        Long hostId = 1L;
        Long inviteeId = 2L;
        Long lobbyId = 100L;

        User host = new User();
        host.setId(hostId);
        User invitee = new User();
        invitee.setId(inviteeId);

        Mockito.when(userRepository.findByToken(token)).thenReturn(host);
        Mockito.when(userRepository.findById(inviteeId)).thenReturn(Optional.of(invitee));

        Lobby waitingLobby = new Lobby();
        waitingLobby.setId(lobbyId);
        Mockito.when(lobbyService.requireWaitingLobbyForHost(hostId)).thenReturn(waitingLobby);
        Mockito.when(lobbyRepository.findByStatus("PLAYING")).thenReturn(new ArrayList<>());
        Mockito.when(caboInviteRepository.findByFromUserIdAndLobbyIdOrderByCreatedAtDesc(hostId, lobbyId))
                .thenReturn(new ArrayList<>());

        // Guard Condition: Mock an exact duplicate pending invite found for this pair
        CaboInvite preExistingInvite = new CaboInvite();
        Mockito.when(caboInviteRepository.findByFromUserIdAndToUserIdAndLobbyIdAndStatus(
                hostId, inviteeId, lobbyId, CaboInviteStatus.PENDING)).thenReturn(Optional.of(preExistingInvite));

        CaboInviteCreateDTO body = new CaboInviteCreateDTO();
        body.setToUserId(inviteeId);

        // Act & Assert
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.createInviteAsUser(token, hostId, body));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Pending invite already exists", ex.getReason());
    }

    @Test
    void getSentInvitesForUser_unauthorizedUser_throwsForbidden() {
        User host = new User();
        host.setId(1L);
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(host);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.getSentInvitesForUser("valid-token", 999L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getSentInvitesForUser_whenLobbyNotFound_returnsEmptyList() {
        User host = new User();
        host.setId(1L);
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(host);
        Mockito.when(lobbyService.findWaitingLobbyForHost(1L)).thenReturn(Optional.empty());

        List<CaboInviteSentDTO> results = caboInviteService.getSentInvitesForUser("valid-token", 1L);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void getSentInvitesForUser_whenLobbyFound_returnsListCoveringStatusEdgeCases() {
        User host = new User();
        host.setId(1L);
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(host);

        Lobby waitingLobby = new Lobby();
        waitingLobby.setId(100L);
        Mockito.when(lobbyService.findWaitingLobbyForHost(1L)).thenReturn(Optional.of(waitingLobby));

        // Create mock invites covering both a normal state and an ACCEPTED status edge case
        CaboInvite inviteA = new CaboInvite();
        inviteA.setToUserId(2L);
        inviteA.setStatus(CaboInviteStatus.PENDING);

        CaboInvite inviteB = new CaboInvite();
        inviteB.setToUserId(3L);
        inviteB.setLobbyId(100L);
        inviteB.setStatus(CaboInviteStatus.ACCEPTED);

        Mockito.when(caboInviteRepository.findByFromUserIdAndLobbyIdOrderByCreatedAtDesc(1L, 100L))
                .thenReturn(List.of(inviteA, inviteB));

        // Stub out the user name lookup mapping branch
        User user2 = new User(); user2.setUsername("userTwo");
        User user3 = new User(); user3.setUsername("userThree");
        Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        Mockito.when(userRepository.findById(3L)).thenReturn(Optional.of(user3));

        // Condition where an accepted player left the waiting lobby, fallback logic changes state string to DECLINED
        Mockito.when(lobbyService.getWaitingSessionIdIfPlayerInLobby(100L, 3L)).thenReturn("  ");

        // Act
        List<CaboInviteSentDTO> results = caboInviteService.getSentInvitesForUser("valid-token", 1L);

        // Assert
        assertEquals(2, results.size());
        assertEquals("PENDING", results.get(0).getStatus());
        assertEquals("userTwo", results.get(0).getToUsername());
        
        // Verifies the stillInWaitingLobby condition triggers the DECLINED fallback
        assertEquals("DECLINED", results.get(1).getStatus());
        assertEquals("userThree", results.get(1).getToUsername());
    }

    @Test
    void getPendingInvitesForUser_unauthorizedUser_throwsForbidden() {
        User invitee = new User();
        invitee.setId(2L);
        Mockito.when(userRepository.findByToken("invitee-token")).thenReturn(invitee);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.getPendingInvitesForUser("invitee-token", 999L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getPendingInvitesForUser_filtersByActiveLobbiesAndMapsDTo() {
        User invitee = new User();
        invitee.setId(2L);
        Mockito.when(userRepository.findByToken("invitee-token")).thenReturn(invitee);

        CaboInvite validInvite = new CaboInvite();
        validInvite.setId(50L);
        validInvite.setFromUserId(1L);
        validInvite.setToUserId(2L);
        validInvite.setLobbyId(100L);
        validInvite.setStatus(CaboInviteStatus.PENDING);

        Mockito.when(caboInviteRepository.findByToUserIdAndStatusOrderByCreatedAtAsc(2L, CaboInviteStatus.PENDING))
                .thenReturn(List.of(validInvite));

        // Mock lobby configuration
        Mockito.when(lobbyService.isLobbyWaiting(100L)).thenReturn(true);
        Lobby currentLobby = new Lobby();
        currentLobby.setSessionId("session-abc");
        currentLobby.setSessionHostUserId(1L);
        Mockito.when(lobbyService.findLobbyById(100L)).thenReturn(Optional.of(currentLobby));

        // Mock the fromUser properties
        User host = new User();
        host.setId(1L);
        host.setUsername("hostUser");
        Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(host));

        // Act
        List<CaboInvitePendingDTO> list = caboInviteService.getPendingInvitesForUser("invitee-token", 2L);

        // Assert
        assertEquals(1, list.size());
        assertEquals("hostUser", list.get(0).getFromUsername());
        assertEquals("session-abc", list.get(0).getSessionId());
    }

    @Test
    void respondForUser_authorizationAndValidationGuards_throwCorrectExceptions() {
        User invitee = new User();
        invitee.setId(2L);
        Mockito.when(userRepository.findByToken("invitee-token")).thenReturn(invitee);

        // 1. Guard: Forbidden when user tokens mismatch requested URI
        assertThrows(ResponseStatusException.class,
                () -> caboInviteService.respondForUser("invitee-token", 999L, 10L, new ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO()));

        // 2. Guard: Bad Request when request body or decision text is completely null
        ResponseStatusException exNull = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.respondForUser("invitee-token", 2L, 10L, null));
        assertEquals(HttpStatus.BAD_REQUEST, exNull.getStatusCode());

        ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO body = new ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO();
        
        // 3. Guard: Not Found when inviteId doesn't exist
        Mockito.when(caboInviteRepository.findById(999L)).thenReturn(Optional.empty());
        body.setDecision("ACCEPT");
        ResponseStatusException exNotFound = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.respondForUser("invitee-token", 2L, 999L, body));
        assertEquals(HttpStatus.NOT_FOUND, exNotFound.getStatusCode());

        // Setup base invite mock
        CaboInvite mockInvite = new CaboInvite();
        mockInvite.setId(10L);
        mockInvite.setToUserId(888L); // Target is a different user
        mockInvite.setStatus(CaboInviteStatus.PENDING);
        Mockito.when(caboInviteRepository.findById(10L)).thenReturn(Optional.of(mockInvite));

        // 4. Guard: Forbidden when invite target does not match authorized actor
        ResponseStatusException exNotYourInvite = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.respondForUser("invitee-token", 2L, 10L, body));
        assertEquals(HttpStatus.FORBIDDEN, exNotYourInvite.getStatusCode());

        mockInvite.setToUserId(2L); // Fix target ownership

        // 5. Guard: Conflict when invite is already resolved
        mockInvite.setStatus(CaboInviteStatus.ACCEPTED);
        ResponseStatusException exResolved = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.respondForUser("invitee-token", 2L, 10L, body));
        assertEquals(HttpStatus.CONFLICT, exResolved.getStatusCode());

        mockInvite.setStatus(CaboInviteStatus.PENDING); // Reset to active state
        mockInvite.setLobbyId(200L);
        Mockito.when(lobbyService.isLobbyWaiting(200L)).thenReturn(false);

        // 6. Guard: Conflict when lobby is no longer waiting
        ResponseStatusException exLobbyGone = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.respondForUser("invitee-token", 2L, 10L, body));
        assertEquals(HttpStatus.CONFLICT, exLobbyGone.getStatusCode());
    }

    @Test
    void respondForUser_whenDecisionTextInvalid_throwsBadRequest() {
        User invitee = new User();
        invitee.setId(2L);
        Mockito.when(userRepository.findByToken("invitee-token")).thenReturn(invitee);

        CaboInvite invite = new CaboInvite();
        invite.setToUserId(2L);
        invite.setStatus(CaboInviteStatus.PENDING);
        invite.setLobbyId(200L);
        Mockito.when(caboInviteRepository.findById(10L)).thenReturn(Optional.of(invite));
        Mockito.when(lobbyService.isLobbyWaiting(200L)).thenReturn(true);

        ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO body = new ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO();
        body.setDecision("MAYBE"); // Invalid text entry

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.respondForUser("invitee-token", 2L, 10L, body));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void respondForUser_whenUserInActiveGameDuringAcceptance_throwsConflict() {
        User invitee = new User();
        invitee.setId(2L);
        Mockito.when(userRepository.findByToken("invitee-token")).thenReturn(invitee);

        CaboInvite invite = new CaboInvite();
        invite.setToUserId(2L);
        invite.setStatus(CaboInviteStatus.PENDING);
        invite.setLobbyId(200L);
        Mockito.when(caboInviteRepository.findById(10L)).thenReturn(Optional.of(invite));
        Mockito.when(lobbyService.isLobbyWaiting(200L)).thenReturn(true);

        // Guard Condition: User attempts to accept while already bound to a game block
        Mockito.when(lobbyService.isUserInActiveGame(2L)).thenReturn(true);

        ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO body = new ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO();
        body.setDecision("ACCEPT");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.respondForUser("invitee-token", 2L, 10L, body));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void respondForUser_successfulAcceptance_updatesLobbyAndPublishesEvent() {
        User invitee = new User();
        invitee.setId(2L);
        Mockito.when(userRepository.findByToken("invitee-token")).thenReturn(invitee);

        CaboInvite invite = new CaboInvite();
        invite.setFromUserId(1L);
        invite.setToUserId(2L);
        invite.setStatus(CaboInviteStatus.PENDING);
        invite.setLobbyId(200L);
        Mockito.when(caboInviteRepository.findById(10L)).thenReturn(Optional.of(invite));
        Mockito.when(lobbyService.isLobbyWaiting(200L)).thenReturn(true);
        Mockito.when(lobbyService.isUserInActiveGame(2L)).thenReturn(false);

        ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO body = new ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO();
        body.setDecision("  accept  "); // Verifies trimmer and case-insensitive conversion

        Mockito.when(lobbyService.getWaitingSessionIdIfPlayerInLobby(200L, 2L)).thenReturn("lobby-session-777");

        // Act
        ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteRespondDTO response = 
                caboInviteService.respondForUser("invitee-token", 2L, 10L, body);

        // Assert
        assertNotNull(response);
        assertEquals("lobby-session-777", response.getWaitingLobbySessionId());
        assertEquals(CaboInviteStatus.ACCEPTED, invite.getStatus());
        
        Mockito.verify(caboInviteRepository, Mockito.times(1)).save(invite);
        Mockito.verify(lobbyService, Mockito.times(1)).addPlayerToLobby(200L, 1L, 2L);
        Mockito.verify(caboInviteEventPublisher, Mockito.times(1))
                .publishToInviterAfterCommit(eq(1L), any(ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteSentDTO.class));
    }

    @Test
    void respondForUser_successfulDecline_updatesStatusDirectly() {
        User invitee = new User();
        invitee.setId(2L);
        Mockito.when(userRepository.findByToken("invitee-token")).thenReturn(invitee);

        CaboInvite invite = new CaboInvite();
        invite.setFromUserId(1L);
        invite.setToUserId(2L);
        invite.setStatus(CaboInviteStatus.PENDING);
        invite.setLobbyId(200L);
        Mockito.when(caboInviteRepository.findById(10L)).thenReturn(Optional.of(invite));
        Mockito.when(lobbyService.isLobbyWaiting(200L)).thenReturn(true);

        ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO body = new ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO();
        body.setDecision("DECLINE");

        // Act
        ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteRespondDTO response = 
                caboInviteService.respondForUser("invitee-token", 2L, 10L, body);

        // Assert
        assertNotNull(response);
        assertNull(response.getWaitingLobbySessionId());
        assertEquals(CaboInviteStatus.DECLINED, invite.getStatus());
        
        Mockito.verify(caboInviteRepository, Mockito.times(1)).save(invite);
        Mockito.verify(lobbyService, Mockito.never()).addPlayerToLobby(anyLong(), anyLong(), anyLong());
    }

    @Test
    void deleteInviteForUser_validationGuards_throwCorrectExceptions() {
        User actor = new User();
        actor.setId(1L);
        Mockito.when(userRepository.findByToken("actor-token")).thenReturn(actor);

        // 1. Guard: Forbidden when user tokens mismatch requested URI
        assertThrows(ResponseStatusException.class,
                () -> caboInviteService.deleteInviteForUser("actor-token", 999L, 10L));

        // 2. Guard: Not Found when requested inviteId doesn't exist
        Mockito.when(caboInviteRepository.findById(999L)).thenReturn(Optional.empty());
        ResponseStatusException exNotFound = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.deleteInviteForUser("actor-token", 1L, 999L));
        assertEquals(HttpStatus.NOT_FOUND, exNotFound.getStatusCode());

        CaboInvite invite = new CaboInvite();
        invite.setId(10L);
        invite.setFromUserId(88L);
        invite.setToUserId(99L); // Neither side matches actor ID 1L
        invite.setStatus(CaboInviteStatus.PENDING);
        Mockito.when(caboInviteRepository.findById(10L)).thenReturn(Optional.of(invite));

        // 3. Guard: Forbidden when actor is neither the host nor target of the invite
        ResponseStatusException exForbidden = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.deleteInviteForUser("actor-token", 1L, 10L));
        assertEquals(HttpStatus.FORBIDDEN, exForbidden.getStatusCode());

        invite.setFromUserId(1L); // Assign sender ownership to actor
        invite.setStatus(CaboInviteStatus.ACCEPTED); // Modify state to resolved

        // 4. Guard: Conflict when attempting to delete a non-pending resolved invite
        ResponseStatusException exConflict = assertThrows(ResponseStatusException.class,
                () -> caboInviteService.deleteInviteForUser("actor-token", 1L, 10L));
        assertEquals(HttpStatus.CONFLICT, exConflict.getStatusCode());
    }

    @Test
    void deleteInviteForUser_whenPendingAndAuthorized_successfullyDeletes() {
        User actor = new User();
        actor.setId(1L);
        Mockito.when(userRepository.findByToken("actor-token")).thenReturn(actor);

        CaboInvite invite = new CaboInvite();
        invite.setId(10L);
        invite.setFromUserId(1L); // Authorized actor
        invite.setToUserId(2L);
        invite.setStatus(CaboInviteStatus.PENDING); // Active target
        Mockito.when(caboInviteRepository.findById(10L)).thenReturn(Optional.of(invite));

        // Act
        caboInviteService.deleteInviteForUser("actor-token", 1L, 10L);

        // Assert: Verifies the uncovered deletion code executes cleanly
        Mockito.verify(caboInviteRepository, Mockito.times(1)).delete(invite);
    }
}
