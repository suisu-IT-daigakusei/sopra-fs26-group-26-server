package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatMessageDTO;
import ch.uzh.ifi.hase.soprafs26.service.GameService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyChatService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
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
}
