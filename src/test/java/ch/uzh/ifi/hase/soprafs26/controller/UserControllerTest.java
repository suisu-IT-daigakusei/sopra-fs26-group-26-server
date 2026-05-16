package ch.uzh.ifi.hase.soprafs26.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendOnlineSummaryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendRequestIncomingDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.service.GameService;
import ch.uzh.ifi.hase.soprafs26.service.HistoryService;
import ch.uzh.ifi.hase.soprafs26.service.UserService;
import ch.uzh.ifi.hase.soprafs26.util.AuthValidationRules;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserControllerTest
 * This is a WebMvcTest which allows to test the UserController i.e. GET/POST
 * request without actually sending them over the network.
 * This tests if the UserController works.
 */
@WebMvcTest(UserController.class)
public class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserService userService;

	@MockitoBean
	private GameService gameService;

	@MockitoBean
	private HistoryService historyService;

	@MockitoBean
	private UserRepository userRepository;

	@Test
	public void givenUsers_whenGetUsers_thenReturnJsonArray() throws Exception {
		// given
		User user = new User();
		user.setName("Firstname Lastname");
		user.setUsername("firstname@lastname");
		user.setStatus(UserStatus.OFFLINE);

		List<User> allUsers = Collections.singletonList(user);

		// this mocks the UserService -> we define above what the userService should
		// return when getUsers() is called
		given(userService.getUsers()).willReturn(allUsers);

		// when
		MockHttpServletRequestBuilder getRequest = get("/users").contentType(MediaType.APPLICATION_JSON);

		// then
		mockMvc.perform(getRequest).andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].name", is(user.getName())))
				.andExpect(jsonPath("$[0].username", is(user.getUsername())))
				.andExpect(jsonPath("$[0].status", is(user.getStatus().toString())));
	}

	@Test
	public void createUser_validInput_userCreated() throws Exception {
		// given
		User user = new User();
		user.setId(1L);
		user.setName("Test User");
		user.setUsername("testUsername");
		user.setToken("1");
		user.setStatus(UserStatus.ONLINE);

		UserPostDTO userPostDTO = new UserPostDTO();
		userPostDTO.setName("Test User");
		userPostDTO.setUsername("testUsername");
        userPostDTO.setPassword("ValidPass#1");

		given(userService.createUser(Mockito.any())).willReturn(user);

		// when/then -> do the request + validate the result
		MockHttpServletRequestBuilder postRequest = post("/users")
				.contentType(MediaType.APPLICATION_JSON)
				.content(asJsonString(userPostDTO));

		// then
		mockMvc.perform(postRequest)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id", is(user.getId().intValue())))
				.andExpect(jsonPath("$.name", is(user.getName())))
				.andExpect(jsonPath("$.username", is(user.getUsername())))
				.andExpect(jsonPath("$.status", is(user.getStatus().toString())));
	}

    @Test
    public void createUser_invalidPassword_returnsBadRequest() throws Exception {
        UserPostDTO userPostDTO = new UserPostDTO();
        userPostDTO.setName("Test User");
        userPostDTO.setUsername("testUsername");
        userPostDTO.setPassword("alllower#1");

        MockHttpServletRequestBuilder postRequest = post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(asJsonString(userPostDTO));

        mockMvc.perform(postRequest).andExpect(status().isBadRequest());

        verify(userService, Mockito.never()).createUser(Mockito.any());
    }

    @Test
    public void loginUser_invalidUsernameFormat_returnsBadRequest() throws Exception {
        String loginPayload = "{\"username\":\"bad name\",\"password\":\"whatever\"}";

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isBadRequest());

        verify(userService, Mockito.never()).loginUser(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    public void getAuthRules_returnsConfiguredValidationRules() throws Exception {
        mockMvc.perform(get("/auth/rules").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username.minLength", is(AuthValidationRules.USERNAME_MIN_LENGTH)))
                .andExpect(jsonPath("$.username.maxLength", is(AuthValidationRules.USERNAME_MAX_LENGTH)))
                .andExpect(jsonPath("$.username.pattern", is(AuthValidationRules.USERNAME_REGEX)))
                .andExpect(jsonPath("$.username.allowedCharactersPattern", is(AuthValidationRules.USERNAME_ALLOWED_CHAR_REGEX)))
                .andExpect(jsonPath("$.username.hint", is(AuthValidationRules.USERNAME_HINT)))
                .andExpect(jsonPath("$.password.minLength", is(AuthValidationRules.PASSWORD_MIN_LENGTH)))
                .andExpect(jsonPath("$.password.maxLength", is(AuthValidationRules.PASSWORD_MAX_LENGTH)))
                .andExpect(jsonPath("$.password.pattern", is(AuthValidationRules.CREDENTIAL_FORMAT_REGEX)))
                .andExpect(jsonPath("$.password.allowedCharactersPattern", is(AuthValidationRules.CREDENTIAL_ALLOWED_CHAR_REGEX)))
                .andExpect(jsonPath("$.password.hint", is(AuthValidationRules.PASSWORD_HINT)))
                .andExpect(jsonPath("$.password.asciiOnly", is(true)))
                .andExpect(jsonPath("$.password.requiresUppercase", is(true)))
                .andExpect(jsonPath("$.password.requiresSpecialSymbol", is(true)));
    }

    @Test
    public void getMyFriendIds_authenticated_returnsIds() throws Exception {
        given(userService.getAcceptedFriendIds("valid-token")).willReturn(List.of(2L, 5L));

        mockMvc.perform(get("/users/me/friends/ids")
                        .header("Authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]", is(2)))
                .andExpect(jsonPath("$[1]", is(5)));
    }

    @Test
    public void getMyIncomingFriendRequests_authenticated_returnsRequests() throws Exception {
        FriendRequestIncomingDTO req1 = new FriendRequestIncomingDTO();
        req1.setRequesterUserId(2L);
        req1.setRequesterUsername("alice");
        FriendRequestIncomingDTO req2 = new FriendRequestIncomingDTO();
        req2.setRequesterUserId(7L);
        req2.setRequesterUsername("bob");
        given(userService.getIncomingFriendRequests("valid-token")).willReturn(List.of(req1, req2));

        mockMvc.perform(get("/users/me/friends/requests/incoming")
                        .header("Authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].requesterUserId", is(2)))
                .andExpect(jsonPath("$[0].requesterUsername", is("alice")))
                .andExpect(jsonPath("$[1].requesterUserId", is(7)))
                .andExpect(jsonPath("$[1].requesterUsername", is("bob")));
    }

    @Test
    public void getMyOutgoingPendingFriendRequestIds_authenticated_returnsIds() throws Exception {
        given(userService.getOutgoingPendingFriendRequestIds("valid-token")).willReturn(List.of(3L, 9L));

        mockMvc.perform(get("/users/me/friends/requests/outgoing/ids")
                        .header("Authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]", is(3)))
                .andExpect(jsonPath("$[1]", is(9)));
    }

    @Test
    public void getMyFriendsOnlineSummary_authenticated_returnsSummary() throws Exception {
        FriendOnlineSummaryDTO summary = new FriendOnlineSummaryDTO();
        summary.setFriendsOnline(4);
        summary.setPlaying(1);
        summary.setLobby(2);
        summary.setSpectating(1);
        given(userService.getFriendOnlineSummary("valid-token")).willReturn(summary);

        mockMvc.perform(get("/users/me/friends/online-summary")
                        .header("Authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsOnline", is(4)))
                .andExpect(jsonPath("$.playing", is(1)))
                .andExpect(jsonPath("$.lobby", is(2)))
                .andExpect(jsonPath("$.spectating", is(1)));
    }

    @Test
    public void sendFriendRequest_authenticated_callsService() throws Exception {
        mockMvc.perform(post("/users/me/friends/requests/23")
                        .header("Authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        verify(userService).sendFriendRequest("valid-token", 23L);
    }

    @Test
    public void acceptFriendRequest_authenticated_callsService() throws Exception {
        mockMvc.perform(post("/users/me/friends/requests/23/accept")
                        .header("Authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        verify(userService).acceptFriendRequest("valid-token", 23L);
    }

    @Test
    public void removeFriendRequest_authenticated_callsService() throws Exception {
        mockMvc.perform(delete("/users/me/friends/requests/23")
                        .header("Authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        verify(userService).removeFriendOrRequest("valid-token", 23L);
    }

    @Test
    public void removeFriend_authenticated_callsService() throws Exception {
        mockMvc.perform(delete("/users/me/friends/23")
                        .header("Authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        verify(userService).removeFriendOrRequest("valid-token", 23L);
    }

	/**
	 * Helper Method to convert userPostDTO into a JSON string such that the input
	 * can be processed
	 * Input will look like this: {"name": "Test User", "username": "testUsername"}
	 * 
	 * @param object
	 * @return string
	 */
	private String asJsonString(final Object object) {
		try {
			return new ObjectMapper().writeValueAsString(object);
		} catch (JacksonException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					String.format("The request body could not be created.%s", e.toString()));
		}
	}

    @Test
    public void getUserHistory_validTokenAndId_returnsHistory() throws Exception {
        // 1. Arrange
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setToken("valid-token");

        Session mockSession = new Session();
        mockSession.setId(10L); // Internal ID is now a Long
        mockSession.setSessionId("session-123"); // The string identifier
        // Add any other necessary fields your DTO mapping requires
        
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(mockUser);
        
        // Updated service method call
        Mockito.when(historyService.getUserSessionHistory(1L)).thenReturn(List.of(mockSession));

        // 2. Act & 3. Assert
        mockMvc.perform(MockMvcRequestBuilders.get("/users/1/history")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(10))) // Asserting the Long ID
                .andExpect(jsonPath("$[0].sessionId", is("session-123"))); // Asserting the String sessionId
    }

	@Test
    void getUserHistory_userDoesNotExist_returns404NotFound() throws Exception {

		Mockito.when(userRepository.findByToken("testToken")).thenReturn(new User());
		
        Mockito.when(historyService.getUserSessionHistory(99L))
               .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

        mockMvc.perform(get("/users/99/history")
                .header("Authorization", "testToken")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound()); 
    }

}
