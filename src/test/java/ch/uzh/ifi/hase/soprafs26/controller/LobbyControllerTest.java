package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatMessageDTO;
import ch.uzh.ifi.hase.soprafs26.service.GameService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyChatService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.rest.dto.WaitingLobbyViewDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.mockito.Mockito;	

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

@WebMvcTest(LobbyController.class)
public class LobbyControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private LobbyService lobbyService;

	@MockitoBean
	private GameService gameService;

	@MockitoBean
	private LobbyChatService lobbyChatService;

	@Test
	public void patchLobbySettings_validBody_returnsLobby() throws Exception {
		Lobby lobby = new Lobby();
		lobby.setId(1L);
		lobby.setSessionId("ABCD12EF");
		lobby.setSessionHostUserId(1L);
		lobby.setIsPublic(false);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L)));

		given(lobbyService.updateLobbySettings(eq("my-token"), eq("ABCD12EF"), any()))
				.willReturn(lobby);

		MockHttpServletRequestBuilder patchRequest = patch("/lobbies/ABCD12EF/settings")
				.header("Authorization", "my-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(asJsonString(java.util.Map.of("isPublic", false)));

		mockMvc.perform(patchRequest)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessionId", is(lobby.getSessionId())))
				.andExpect(jsonPath("$.isPublic", is(false)));
	}

	@Test
	public void postJoinLobby_validAuthorization_returnsLobby() throws Exception {
		Lobby lobby = new Lobby();
		lobby.setId(1L);
		lobby.setSessionId("S1");
		lobby.setSessionHostUserId(1L);
		lobby.setIsPublic(true);
		lobby.setStatus("WAITING");
		lobby.setPlayerIds(new ArrayList<>(List.of(1L, 2L)));

		given(lobbyService.joinLobby(eq("S1"), eq("token"))).willReturn(lobby);

		mockMvc.perform(post("/lobbies/S1/players").header("Authorization", "token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessionId", is("S1")));
	}

	@Test
	public void postJoinLobby_invalidAuthorization_returnsUnauthorized() throws Exception {
		given(lobbyService.joinLobby(eq("S1"), eq("invalid-token")))
				.willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

		mockMvc.perform(post("/lobbies/S1/players").header("Authorization", "invalid-token"))
				.andExpect(status().isUnauthorized());
	}

	private String asJsonString(final Object object) {
		try {
			return new ObjectMapper().writeValueAsString(object);
		} catch (JacksonException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					String.format("The request body could not be created.%s", e));
		}
	}

	@Test
	public void postJoinLobby_validRequest_returns200() throws Exception {
		String sessionId = "testId";
		String token = "testToken";

		Lobby lobby = new Lobby();
		lobby.setSessionId(sessionId);

		when(lobbyService.joinLobby(eq(sessionId), eq(token))).thenReturn(lobby);

		mockMvc.perform(post("/lobbies/{sessionId}/players", sessionId)
						.header("Authorization", token))
				.andExpect(status().isOk());

		verify(lobbyService, times(1)).joinLobby(eq(sessionId), eq(token));
	}

	@Test
	public void postLobbyChatMessage_validRequest_returnsMessage() throws Exception {
		LobbyChatMessageDTO response = new LobbyChatMessageDTO();
		response.setSequence(1L);
		response.setSessionId("ABCD12EF");
		response.setUserId(1L);
		response.setUsername("alice");
		response.setText("hello");

		given(lobbyChatService.sendMessage(eq("my-token"), eq("ABCD12EF"), any()))
				.willReturn(response);

		mockMvc.perform(post("/lobbies/ABCD12EF/chat/messages")
						.header("Authorization", "my-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(asJsonString(java.util.Map.of("message", "hello"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessionId", is("ABCD12EF")))
				.andExpect(jsonPath("$.text", is("hello")));
	}

	@Test
	public void getLobbyChatMessages_validRequest_returnsList() throws Exception {
		LobbyChatMessageDTO response = new LobbyChatMessageDTO();
		response.setSequence(1L);
		response.setSessionId("ABCD12EF");
		response.setUserId(1L);
		response.setUsername("alice");
		response.setText("hello");

		given(lobbyChatService.getMessages(eq("my-token"), eq("ABCD12EF")))
				.willReturn(List.of(response));

		mockMvc.perform(get("/lobbies/ABCD12EF/chat/messages")
						.header("Authorization", "my-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].sessionId", is("ABCD12EF")))
				.andExpect(jsonPath("$[0].text", is("hello")));
	}

	@Test
	public void postLobbyChatMessage_cooldownActive_returnsTooManyRequests() throws Exception {
		given(lobbyChatService.sendMessage(eq("my-token"), eq("ABCD12EF"), any()))
				.willThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Chat cooldown active"));

		mockMvc.perform(post("/lobbies/ABCD12EF/chat/messages")
						.header("Authorization", "my-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(asJsonString(java.util.Map.of("message", "hello"))))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	public void getLobbyChatMessages_invalidToken_returnsUnauthorized() throws Exception {
		given(lobbyChatService.getMessages(eq("bad-token"), eq("ABCD12EF")))
				.willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

		mockMvc.perform(get("/lobbies/ABCD12EF/chat/messages")
						.header("Authorization", "bad-token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
    void createLobby_validRequest_returnsCreatedLobby() throws Exception {
        // 1. Setup: Create a mock lobby entity that the service will return
        Lobby mockLobby = new Lobby();
        mockLobby.setSessionId("new-session-123"); 
        
        // Mock the service call expecting 'true' for isPublic
        Mockito.when(lobbyService.createLobby(Mockito.eq("valid-token"), Mockito.eq(true)))
               .thenReturn(mockLobby);

        String jsonBody = """
                {
                    "isPublic": true
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/lobbies")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isCreated()) // 201 CREATED
               .andExpect(jsonPath("$.sessionId", is("new-session-123"))); 
               // Note: adjust "sessionId" to match whatever field is in your LobbyGetDTO!
    }

    @Test
    void createLobby_missingIsPublicFlag_passesNullToService() throws Exception {
        // 1. Setup
        Lobby mockLobby = new Lobby();
        mockLobby.setSessionId("new-session-456");

        // Mock the service expecting 'null' since the JSON map won't have the key
        Mockito.when(lobbyService.createLobby(Mockito.eq("valid-token"), Mockito.isNull()))
               .thenReturn(mockLobby);

        String emptyJsonBody = "{}";

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/lobbies")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyJsonBody))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.sessionId", is("new-session-456")));

        // Explicitly verify the null was passed successfully
        Mockito.verify(lobbyService, Mockito.times(1)).createLobby("valid-token", null);
    }

    @Test
    void createLobby_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup: Service rejects the token
        Mockito.when(lobbyService.createLobby(Mockito.eq("bad-token"), Mockito.anyBoolean()))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        String jsonBody = """
                {
                    "isPublic": false
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/lobbies")
                .header("Authorization", "bad-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
    }

	@Test
    void getPublicLobbies_lobbiesExist_returnsLobbyList() throws Exception {
        // 1. Setup: Create a list of mock lobbies
        Lobby lobby1 = new Lobby();
        lobby1.setSessionId("session-1");
        lobby1.setIsPublic(true);

        Lobby lobby2 = new Lobby();
        lobby2.setSessionId("session-2");
        lobby2.setIsPublic(true);

        List<Lobby> mockLobbies = List.of(lobby1, lobby2);

        Mockito.when(lobbyService.getPublicLobbies("valid-token")).thenReturn(mockLobbies);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/lobbies")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()) // 200 OK
               .andExpect(jsonPath("$.length()", is(2))) // Checks that the array has 2 elements
               .andExpect(jsonPath("$[0].sessionId", is("session-1"))) // Checks the first element
               .andExpect(jsonPath("$[1].sessionId", is("session-2"))); // Checks the second element
    }

    @Test
    void getPublicLobbies_noLobbiesExist_returnsEmptyList() throws Exception {
        // 1. Setup: Service returns an empty list
        Mockito.when(lobbyService.getPublicLobbies("valid-token")).thenReturn(List.of());

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/lobbies")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()", is(0))); // Checks that the JSON array [] is empty
    }

    @Test
    void getPublicLobbies_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup: Service rejects the token
        Mockito.when(lobbyService.getPublicLobbies("bad-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/lobbies")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
    }

	@Test
    void joinLobbyAsSpectator_validRequest_returnsLobby() throws Exception {
        // 1. Setup: Create a mock lobby to return
        Lobby mockLobby = new Lobby();
        mockLobby.setSessionId("session-123");
        mockLobby.setIsPublic(true);

        Mockito.when(lobbyService.joinLobbyAsSpectator("session-123", "valid-token"))
               .thenReturn(mockLobby);

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/lobbies/session-123/spectators")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()) // Expecting 200 OK
               .andExpect(jsonPath("$.sessionId", is("session-123")));
               
        // Verify the service was called with the exact path variable and header
        Mockito.verify(lobbyService, Mockito.times(1))
               .joinLobbyAsSpectator("session-123", "valid-token");
    }

    @Test
    void joinLobbyAsSpectator_lobbyNotFound_throwsNotFound() throws Exception {
        // 1. Setup: Simulate the lobby not existing in the database
        Mockito.when(lobbyService.joinLobbyAsSpectator("invalid-session", "valid-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Lobby not found"));

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/lobbies/invalid-session/spectators")
                .header("Authorization", "valid-token"))
               .andExpect(status().isNotFound()); // 404 NOT FOUND
    }

    @Test
    void joinLobbyAsSpectator_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup: Simulate the token being rejected
        Mockito.when(lobbyService.joinLobbyAsSpectator("session-123", "bad-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/lobbies/session-123/spectators")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
    }

	@Test
    void updateReadyState_validRequest_returnsWaitingLobbyView() throws Exception {
        // 1. Setup: Create a mock response DTO
        WaitingLobbyViewDTO mockView = new WaitingLobbyViewDTO();
        // Set whatever fields your WaitingLobbyViewDTO has. For example, if it has a sessionId:
        // mockView.setSessionId("session-123"); 

        Mockito.when(lobbyService.setPlayerReady(Mockito.eq("session-123"), Mockito.eq("valid-token"), Mockito.eq(true)))
               .thenReturn(mockView);

        String jsonBody = """
                {
                    "ready": true
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(patch("/lobbies/session-123/ready")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isOk()); // 200 OK
               
        // If you know a specific field in WaitingLobbyViewDTO, you can assert it like:
        // .andExpect(jsonPath("$.sessionId", is("session-123")));
    }

    @Test
    void updateReadyState_emptyBody_passesNullReadyState() throws Exception {
        // 1. Setup
        WaitingLobbyViewDTO mockView = new WaitingLobbyViewDTO();
        
        // Mock the service to expect 'null' for the ready boolean
        Mockito.when(lobbyService.setPlayerReady(Mockito.eq("session-123"), Mockito.eq("valid-token"), Mockito.isNull()))
               .thenReturn(mockView);

        String emptyJsonBody = "{}";

        // 2. Action & 3. Assertion
        mockMvc.perform(patch("/lobbies/session-123/ready")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyJsonBody))
               .andExpect(status().isOk());

        // Verify that the controller successfully extracted null and passed it down
        Mockito.verify(lobbyService, Mockito.times(1)).setPlayerReady("session-123", "valid-token", null);
    }

    @Test
    void updateReadyState_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup
        Mockito.when(lobbyService.setPlayerReady(Mockito.anyString(), Mockito.eq("bad-token"), Mockito.anyBoolean()))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        String jsonBody = """
                {
                    "ready": true
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(patch("/lobbies/session-123/ready")
                .header("Authorization", "bad-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
    }

	@Test
    void getWaitingLobby_validRequest_returnsLobbyView() throws Exception {
        // 1. Setup: Create a mock DTO to return
        WaitingLobbyViewDTO mockView = new WaitingLobbyViewDTO();
        // Assuming your DTO has a sessionId or similar fields to verify against. 
        // If it doesn't, you can just assert the status code!
        // mockView.setSessionId("session-123"); 

        Mockito.when(lobbyService.getWaitingLobbyView("valid-token", "session-123"))
               .thenReturn(mockView);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/lobbies/waiting/session-123")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()); 
               // .andExpect(jsonPath("$.sessionId", is("session-123"))); // Uncomment if applicable
    }

    @Test
    void getWaitingLobby_lobbyNotFound_throwsNotFound() throws Exception {
        // 1. Setup: Simulate the lobby missing
        Mockito.when(lobbyService.getWaitingLobbyView("valid-token", "invalid-session"))
               .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Lobby not found"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/lobbies/waiting/invalid-session")
                .header("Authorization", "valid-token"))
               .andExpect(status().isNotFound()); // 404 NOT FOUND
    }

    @Test
    void getWaitingLobby_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup: Simulate token rejection
        Mockito.when(lobbyService.getWaitingLobbyView("bad-token", "session-123"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/lobbies/waiting/session-123")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
    }

	@Test
    void getMyWaitingLobby_validRequest_returnsLobby() throws Exception {
        // 1. Setup: Create a mock lobby entity for a session you are hosting
        Lobby mockLobby = new Lobby();
        mockLobby.setSessionId("my-hosted-session-123");

        Mockito.when(lobbyService.getMyWaitingLobbyAsHost("valid-token"))
               .thenReturn(mockLobby);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/lobbies/my/waiting")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()) // 200 OK
               .andExpect(jsonPath("$.sessionId", is("my-hosted-session-123")));
               
        Mockito.verify(lobbyService, Mockito.times(1)).getMyWaitingLobbyAsHost("valid-token");
    }

    @Test
    void getMyWaitingLobby_noLobbyFound_throwsNotFound() throws Exception {
        // 1. Setup: Simulate the scenario where the user is not currently hosting a lobby
        Mockito.when(lobbyService.getMyWaitingLobbyAsHost("valid-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No waiting lobby found as host"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/lobbies/my/waiting")
                .header("Authorization", "valid-token"))
               .andExpect(status().isNotFound()); // 404 NOT FOUND
    }

    @Test
    void getMyWaitingLobby_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup: Simulate an authentication failure
        Mockito.when(lobbyService.getMyWaitingLobbyAsHost("bad-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/lobbies/my/waiting")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
    }

	@Test
    void removePlayerFromLobby_playerRemovedLobbySurvives_returnsUpdatedLobby() throws Exception {
        // 1. Setup: Create a mock lobby that still exists
        Lobby mockLobby = new Lobby();
        mockLobby.setSessionId("session-123");

        Mockito.when(lobbyService.removePlayerFromLobby("session-123", "valid-token", 456L))
               .thenReturn(mockLobby);

        // 2. Action & 3. Assertion
        mockMvc.perform(delete("/lobbies/session-123/players/456")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()) // 200 OK
               .andExpect(jsonPath("$.sessionId", is("session-123")));
    }

    @Test
    void removePlayerFromLobby_lastPlayerLeaves_returnsEmptyBody() throws Exception {
        // 1. Setup: The service returns null because the lobby was deleted
        Mockito.when(lobbyService.removePlayerFromLobby("session-123", "valid-token", 456L))
               .thenReturn(null);

        // 2. Action & 3. Assertion
        mockMvc.perform(delete("/lobbies/session-123/players/456")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()) // Still 200 OK
               .andExpect(jsonPath("$").doesNotExist()); // Proves the response body is completely empty
    }

    @Test
    void removePlayerFromLobby_notHostTryingToKick_throwsForbidden() throws Exception {
        // 1. Setup: The service rejects the attempt to kick someone
        Mockito.when(lobbyService.removePlayerFromLobby("session-123", "bad-token", 456L))
               .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can kick players"));

        // 2. Action & 3. Assertion
        mockMvc.perform(delete("/lobbies/session-123/players/456")
                .header("Authorization", "bad-token"))
               .andExpect(status().isForbidden()); // 403 FORBIDDEN
    }

	@Test
    void removeSpectatorFromLobby_spectatorRemovedLobbySurvives_returnsUpdatedLobby() throws Exception {
        // 1. Setup: Create a mock lobby that still exists
        Lobby mockLobby = new Lobby();
        mockLobby.setSessionId("session-123");

        Mockito.when(lobbyService.removeSpectatorFromLobby("session-123", "valid-token", 789L))
               .thenReturn(mockLobby);

        // 2. Action & 3. Assertion
        mockMvc.perform(delete("/lobbies/session-123/spectators/789")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()) // 200 OK
               .andExpect(jsonPath("$.sessionId", is("session-123")));
    }

    @Test
    void removeSpectatorFromLobby_lobbyDeleted_returnsEmptyBody() throws Exception {
        // 1. Setup: The service returns null because the lobby was deleted
        Mockito.when(lobbyService.removeSpectatorFromLobby("session-123", "valid-token", 789L))
               .thenReturn(null);

        // 2. Action & 3. Assertion
        mockMvc.perform(delete("/lobbies/session-123/spectators/789")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()) // Still 200 OK
               .andExpect(jsonPath("$").doesNotExist()); // Proves the response body is completely empty
    }

    @Test
    void removeSpectatorFromLobby_notAllowed_throwsForbidden() throws Exception {
        // 1. Setup: The service rejects the attempt to kick the spectator
        Mockito.when(lobbyService.removeSpectatorFromLobby("session-123", "bad-token", 789L))
               .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can kick spectators"));

        // 2. Action & 3. Assertion
        mockMvc.perform(delete("/lobbies/session-123/spectators/789")
                .header("Authorization", "bad-token"))
               .andExpect(status().isForbidden()); // 403 FORBIDDEN
    }
}
