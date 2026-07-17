package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.UserListQuery;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendOnlineSummaryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendRequestIncomingDTO;
import ch.uzh.ifi.hase.soprafs26.service.HistoryService;
import ch.uzh.ifi.hase.soprafs26.service.HotEndpointRateLimiter;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.service.UserService;
import ch.uzh.ifi.hase.soprafs26.service.UserService.PagedUser;
import ch.uzh.ifi.hase.soprafs26.service.UserService.PagedUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// These static imports are critical for the mockMvc.perform() calls
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

// Add these to fix the Mockito errors!
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.BDDMockito.given; 

// Add this to fix the hasSize() error!
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.util.AuthValidationRules;

/**
 * UserControllerTest
 * This is a WebMvcTest which allows to test the UserController i.e. GET/POST
 * request without actually sending them over the network.
 * This tests if the UserController works.
 */

public class UserControllerTest {

	private MockMvc mockMvc;
    private UserService userService;
    private HistoryService historyService;
    private LobbyService lobbyService;
    private UserRepository userRepository;
    private HotEndpointRateLimiter hotEndpointRateLimiter;

    @BeforeEach
    void setup() {
        // 1. Mock all four required dependencies
        userService = Mockito.mock(UserService.class);
        historyService = Mockito.mock(HistoryService.class);
        lobbyService = Mockito.mock(LobbyService.class);
        userRepository = Mockito.mock(UserRepository.class);
        hotEndpointRateLimiter = Mockito.mock(HotEndpointRateLimiter.class);

        // 2. Pass them all into the constructor in the EXACT order defined in UserController.java
        UserController userController = new UserController(
                userService, 
                historyService, 
                lobbyService, 
                userRepository,
                hotEndpointRateLimiter
        );

        // 3. Build the standalone setup
        mockMvc = MockMvcBuilders.standaloneSetup(userController).build();
    }

	@Test
	public void givenUsers_whenGetUsers_thenReturnJsonArray() throws Exception {
		// given
		User user = new User();
		user.setId(1L);
		user.setName("Firstname Lastname");
		user.setUsername("firstname@lastname");
		user.setStatus(UserStatus.OFFLINE);
		user.setGamesPlayed(7);
		user.setRoundsPlayed(19);

		given(userService.getUsersPage(Mockito.any())).willReturn(
				new PagedUsers(
						List.of(new PagedUser(user, null, UserStatus.PLAYING, "LIVE-SESSION")),
						0,
						20,
						1,
						1,
						false));

		// when
		MockHttpServletRequestBuilder getRequest = get("/users").contentType(MediaType.APPLICATION_JSON);

		// then
		mockMvc.perform(getRequest).andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].name", is(user.getName())))
				.andExpect(jsonPath("$.items[0].username", is(user.getUsername())))
				.andExpect(jsonPath("$.items[0].status", is(UserStatus.PLAYING.toString())))
				.andExpect(jsonPath("$.items[0].joinableSessionId", is("LIVE-SESSION")))
				.andExpect(jsonPath("$.items[0].gamesPlayed", is(7)))
				.andExpect(jsonPath("$.items[0].roundsPlayed", is(19)))
				.andExpect(jsonPath("$.page", is(0)))
				.andExpect(jsonPath("$.size", is(20)))
				.andExpect(jsonPath("$.totalElements", is(1)))
				.andExpect(jsonPath("$.totalPages", is(1)))
				.andExpect(jsonPath("$.hasNext", is(false)));

		org.mockito.ArgumentCaptor<UserListQuery> queryCaptor =
				org.mockito.ArgumentCaptor.forClass(UserListQuery.class);
		verify(userService).getUsersPage(queryCaptor.capture());
		assertEquals(0, queryCaptor.getValue().page());
		assertEquals(20, queryCaptor.getValue().size());
		assertEquals(
				ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.View.DIRECTORY,
				queryCaptor.getValue().view());
		Mockito.verify(lobbyService, Mockito.never())
				.resolveJoinableSessionsAndPresenceForUsers(Mockito.anyList());
	}

    @Test
    void getUsers_rejectsUnboundedOrUnsupportedParameters() throws Exception {
        mockMvc.perform(get("/users").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/users").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/users").param("q", "x".repeat(65)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/users").param("view", "directory").param("sort", "rank"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/users").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/users").param("excludeId", "0"))
                .andExpect(status().isBadRequest());

        Mockito.verify(userService, Mockito.never()).getUsersPage(Mockito.any());
    }

    @Test
    void getUsers_friendsOnlyRequiresTokenAndPassesViewerToQuery() throws Exception {
        mockMvc.perform(get("/users").param("friendsOnly", "true"))
                .andExpect(status().isUnauthorized());

        User viewer = new User();
        viewer.setId(7L);
        Mockito.when(userRepository.findByToken("viewer-token")).thenReturn(viewer);
        Mockito.when(userService.getUsersPage(Mockito.any()))
                .thenReturn(new PagedUsers(List.of(), 0, 20, 0, 0, false));

        mockMvc.perform(get("/users")
                        .param("view", "leaderboard")
                        .param("friendsOnly", "true")
                        .header("Authorization", "viewer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        org.mockito.ArgumentCaptor<UserListQuery> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(UserListQuery.class);
        verify(userService).getUsersPage(queryCaptor.capture());
        assertEquals(7L, queryCaptor.getValue().viewerId());
        assertEquals(true, queryCaptor.getValue().friendsOnly());
    }

    @Test
    void getUsers_friendsOnlyRateLimitsByIpBeforeRotatingTokenLookups() throws Exception {
        MockHttpServletRequestBuilder firstRequest = get("/users")
                .param("friendsOnly", "true")
                .header("Authorization", "fake-token-a")
                .header("X-Forwarded-For", "198.51.100.23")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.9");
                    return request;
                });
        MockHttpServletRequestBuilder secondRequest = get("/users")
                .param("friendsOnly", "true")
                .header("Authorization", "fake-token-b")
                .header("X-Forwarded-For", "198.51.100.23")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.9");
                    return request;
                });

        mockMvc.perform(firstRequest).andExpect(status().isUnauthorized());
        mockMvc.perform(secondRequest).andExpect(status().isUnauthorized());

        org.mockito.InOrder callOrder = Mockito.inOrder(hotEndpointRateLimiter, userRepository);
        callOrder.verify(hotEndpointRateLimiter).enforceHotReadLimit(
                "users-list-directory", null, "198.51.100.23", "203.0.113.9");
        callOrder.verify(userRepository).findByToken("fake-token-a");
        callOrder.verify(hotEndpointRateLimiter).enforceHotReadLimit(
                "users-list-directory", null, "198.51.100.23", "203.0.113.9");
        callOrder.verify(userRepository).findByToken("fake-token-b");
        Mockito.verifyNoInteractions(userService);
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
				.content("{\"username\":\"testUser123\", \"password\":\"ValidPass1!\"}");

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
                .content("{\"username\":\"testUsername\", \"password\":\"testPassword\"}");

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

    @Test
    public void getUserHistory_ownerCanViewPrivateHistory_returnsHistory() throws Exception {
        // 1. Arrange
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setToken("valid-token");
        mockUser.setIsPublicLog(false);

        Session mockSession = new Session();
        mockSession.setId(10L); // Internal ID is now a Long
        mockSession.setSessionId("session-123"); // The string identifier
        // Add any other necessary fields your DTO mapping requires
        
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(mockUser);
        Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));
        
        // Updated service method call
        Mockito.when(historyService.getUserSessionHistory(1L, 25, 50)).thenReturn(List.of(mockSession));

        // 2. Act & 3. Assert
        mockMvc.perform(MockMvcRequestBuilders.get("/users/1/history")
                        .header("Authorization", "valid-token")
                        .param("limit", "25")
                        .param("offset", "50")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(10))) // Asserting the Long ID
                .andExpect(jsonPath("$[0].sessionId", is("session-123"))); // Asserting the String sessionId
    }

    @Test
    void getUserHistory_publicTargetAllowsAuthenticatedStranger() throws Exception {
        User requester = new User();
        requester.setId(1L);

        User target = new User();
        target.setId(2L);
        target.setIsPublicLog(true);

        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(requester);
        Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        Mockito.when(historyService.getUserSessionHistory(2L, 200, 0)).thenReturn(List.of());

        mockMvc.perform(get("/users/2/history")
                        .header("Authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        Mockito.verify(historyService).getUserSessionHistory(2L, 200, 0);
    }

    @Test
    void getUserHistory_privateFlagDoesNotHideResultHistoryFromAuthenticatedStranger() throws Exception {
        User requester = new User();
        requester.setId(1L);

        User target = new User();
        target.setId(2L);
        target.setIsPublicLog(false);

        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(requester);
        Mockito.when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        Mockito.when(historyService.getUserSessionHistory(2L, 200, 0)).thenReturn(List.of());

        mockMvc.perform(get("/users/2/history")
                        .header("Authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        Mockito.verify(historyService).getUserSessionHistory(2L, 200, 0);
    }

	@Test
    void getUserHistory_userDoesNotExist_returns404NotFound() throws Exception {
        User requester = new User();
        requester.setId(1L);
        Mockito.when(userRepository.findByToken("testToken")).thenReturn(requester);
        Mockito.when(userRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/users/99/history")
                .header("Authorization", "testToken")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        Mockito.verify(historyService, Mockito.never())
                .getUserSessionHistory(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void getUserById_validUserWithId_returnsUserAndFetchesLobbyStatus() throws Exception {
        // 1. Setup: Create a mock user with a valid ID
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("testUsername");
        mockUser.setToken("secret-token"); // We will verify this gets stripped!
        
        // If your User entity requires a status to avoid NullPointerExceptions in normalizeVisibleStatus:
        // mockUser.setStatus(UserStatus.ONLINE); 

        Mockito.when(userService.getUserProfileById(1L)).thenReturn(mockUser);
        Mockito.when(lobbyService.resolveJoinableSessionIdForUser(1L)).thenReturn("session-123");
        
        // Assuming resolveLobbyPresenceStatusForUser returns an enum value
        // Mockito.when(lobbyService.resolveLobbyPresenceStatusForUser(1L)).thenReturn(UserStatus.ONLINE);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/users/1"))
               .andExpect(status().isOk()) // 200 OK
               .andExpect(jsonPath("$.username", is("testUsername")))
               .andExpect(jsonPath("$.joinableSessionId", is("session-123")))
               .andExpect(jsonPath("$.token").doesNotExist()); // Verifies the token was successfully nullified
               
        // Verify the lobby service was called to fetch the session ID
        Mockito.verify(lobbyService, Mockito.times(1)).resolveJoinableSessionIdForUser(1L);
    }

    @Test
    void getUserById_userWithoutId_skipsLobbyStatusAndReturnsUser() throws Exception {
        // 1. Setup: Create a mock user where ID is explicitly null to trigger the 'else' block
        User mockUser = new User();
        mockUser.setId(null);
        mockUser.setUsername("ghostUser");

        // The controller gets called with "2", but the returned user has no ID
        Mockito.when(userService.getUserProfileById(2L)).thenReturn(mockUser);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/users/2"))
               .andExpect(status().isOk()) // 200 OK
               .andExpect(jsonPath("$.username", is("ghostUser")))
               .andExpect(jsonPath("$.joinableSessionId").doesNotExist()); // Or is(nullValue()) depending on Jackson settings

        // Verify that because the ID was null, the controller safely skipped calling the lobbyService
        Mockito.verify(lobbyService, Mockito.never()).resolveJoinableSessionIdForUser(Mockito.anyLong());
    }

    @Test
    void getUserById_userNotFound_throwsNotFound() throws Exception {
        // 1. Setup: The user service cannot find the user
        Mockito.when(userService.getUserProfileById(99L))
               .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/users/99"))
               .andExpect(status().isNotFound()); // 404 NOT FOUND
    }

    @Test
    void updateUser_validRequest_returnsNoContent() throws Exception {
        // 1. Setup: Since the service method returns void, Mockito does nothing by default.
        // We just provide a valid JSON payload to simulate the UserPutDTO.
        String jsonBody = """
                {
                    "username": "newUsername",
                    "birthday": "1999-12-31"
                }
                """;

        User authenticatedUser = new User();
        authenticatedUser.setId(1L);
        Mockito.when(userRepository.findByToken("owner-token")).thenReturn(authenticatedUser);

        // 2. Action & 3. Assertion
        mockMvc.perform(put("/users/1")
                .header("Authorization", "owner-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isNoContent()); // 204 NO CONTENT
               
        // Verify the service was called exactly once with the correct ID and mapped entity
        Mockito.verify(userService, Mockito.times(1))
               .updateUser(Mockito.eq(1L), Mockito.any(ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO.class));
    }

    @Test
    void updateUser_missingBody_returnsBadRequest() throws Exception {
        // 1. Setup: No mock setup needed because Spring blocks this before the controller runs!

        // 2. Action & 3. Assertion
        mockMvc.perform(put("/users/1")
                .header("Authorization", "owner-token")
                .contentType(MediaType.APPLICATION_JSON)) // Notice: No .content()!
               .andExpect(status().isBadRequest()); // 400 BAD REQUEST

        // Verify the service was entirely protected from the bad request
        Mockito.verify(userService, Mockito.never())
               .updateUser(Mockito.anyLong(), Mockito.any(ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO.class));
    }

    @Test
    void updateUser_userNotFound_throwsNotFound() throws Exception {
        // 1. Setup: Tell Mockito to throw an error when the void method is called for user 99
        Mockito.doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"))
               .when(userService).updateUser(Mockito.eq(99L), Mockito.any(ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO.class));

        String jsonBody = """
                {
                    "username": "newUsername"
                }
                """;

        User authenticatedUser = new User();
        authenticatedUser.setId(99L);
        Mockito.when(userRepository.findByToken("owner-token")).thenReturn(authenticatedUser);

        // 2. Action & 3. Assertion
        mockMvc.perform(put("/users/99")
                .header("Authorization", "owner-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isNotFound()); // 404 NOT FOUND
    }

    @Test
    void updateUser_missingOrInvalidToken_returnsUnauthorized() throws Exception {
        String jsonBody = """
                { "bio": "should not be written" }
                """;

        mockMvc.perform(put("/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/users/1")
                        .header("Authorization", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isUnauthorized());

        Mockito.verify(userService, Mockito.never())
                .updateUser(Mockito.anyLong(), Mockito.any(ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO.class));
    }

    @Test
    void updateUser_authenticatedDifferentUser_returnsForbidden() throws Exception {
        User authenticatedUser = new User();
        authenticatedUser.setId(2L);
        Mockito.when(userRepository.findByToken("other-user-token")).thenReturn(authenticatedUser);
        String jsonBody = """
                { "bio": "should not be written" }
                """;

        mockMvc.perform(put("/users/1")
                        .header("Authorization", "other-user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isForbidden());

        Mockito.verify(userService, Mockito.never())
                .updateUser(Mockito.anyLong(), Mockito.any(ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO.class));
    }

    @Test
    void loginUser_validCredentials_returnsUser() throws Exception {
        // 1. Setup: Create a mock user that the service will return upon successful login
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("testUsername");
        mockUser.setToken("new-login-token"); // Note: We expect the token to be returned upon login!

        Mockito.when(userService.loginUser("testUsername", "correctPassword"))
               .thenReturn(mockUser);

        // A basic JSON payload simulating the UserLoginDTO
        String jsonBody = """
                {
                    "username": "testUsername",
                    "password": "correctPassword"
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isOk()) // 200 OK
               .andExpect(jsonPath("$.username", is("testUsername")))
               .andExpect(jsonPath("$.token", is("new-login-token"))); 
               
        // Verify the service was called with exactly the strings provided in the JSON
        Mockito.verify(userService, Mockito.times(1)).loginUser("testUsername", "correctPassword");
    }

    @Test
    void loginUser_missingBody_returnsBadRequest() throws Exception {
        // 1. Setup: No mock setup needed; Spring blocks it.

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/login")
                .contentType(MediaType.APPLICATION_JSON)) // Notice: No .content()!
               .andExpect(status().isBadRequest()); // 400 BAD REQUEST

        // Verify the service is never touched if the request is malformed
        Mockito.verify(userService, Mockito.never()).loginUser(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void loginUser_invalidCredentials_throwsUnauthorized() throws Exception {
        // 1. Setup: Tell the service to reject these specific credentials
        Mockito.when(userService.loginUser("testUsername", "wrongPassword"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        String jsonBody = """
                {
                    "username": "testUsername",
                    "password": "wrongPassword"
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
    }

    @Test
    void logoutUser_validToken_returnsOk() throws Exception {
        // 1. Setup: Since the service method returns void, Mockito does nothing by default!
        // We just let it run smoothly.

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/auth/logout")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()); // 200 OK
               
        // Verify the service was actually told to log the user out
        Mockito.verify(userService, Mockito.times(1)).logoutUser("valid-token");
    }

    @Test
    void logoutUser_missingHeader_returnsBadRequest() throws Exception {
        // 1. Setup: No mock setup needed because Spring catches the missing header before the controller runs!

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/auth/logout")) // Notice: No .header(...) included!
               .andExpect(status().isBadRequest()); // 400 BAD REQUEST

        // Verify the service was entirely protected from the bad request
        Mockito.verify(userService, Mockito.never()).logoutUser(Mockito.anyString());
    }

    @Test
    void logoutUser_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup: Tell Mockito to throw an error when the void method is called with a bad token
        Mockito.doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"))
               .when(userService).logoutUser("bad-token");

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/auth/logout")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
    }

    @Test
    void logoutUserBeacon_nullOrBlankBody_doesNothing() throws Exception {
        // 1. Action & Assertion for Null body
        mockMvc.perform(post("/auth/logout/beacon"))
               .andExpect(status().isOk());

        // Action & Assertion for Blank body
        mockMvc.perform(post("/auth/logout/beacon")
                .content("   "))
               .andExpect(status().isOk());

        // Verify the service was entirely skipped
        Mockito.verify(userService, Mockito.never()).logoutUser(Mockito.anyString());
    }

    @Test
    void logoutUserBeacon_jsonFormatBody_extractsTokenAndLogsOut() throws Exception {
        // 1. Setup: A raw string resembling a JSON payload
        String jsonBody = "{\"token\":\"json-token-123\"}";

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/auth/logout/beacon")
                .content(jsonBody))
               .andExpect(status().isOk());

        // Verify your custom string parser stripped the JSON syntax and found the token
        Mockito.verify(userService, Mockito.times(1)).logoutUser("json-token-123");
    }

    @Test
    void logoutUserBeacon_formUrlEncodedBody_extractsAndDecodesToken() throws Exception {
        // 1. Setup: A raw string resembling form-data, including URL-encoded spaces (%20)
        String formBody = "token=url%20encoded%20token";

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/auth/logout/beacon")
                .content(formBody))
               .andExpect(status().isOk());

        // Verify your parser stripped "token=" and properly decoded the spaces
        Mockito.verify(userService, Mockito.times(1)).logoutUser("url encoded token");
    }

    @Test
    void logoutUserBeacon_plainTextBody_usesBodyAsToken() throws Exception {
        // 1. Setup: A raw string that is just the token itself
        String plainBody = "plain-text-token";

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/auth/logout/beacon")
                .content(plainBody))
               .andExpect(status().isOk());

        // Verify the parser recognized it as plain text and passed it directly
        Mockito.verify(userService, Mockito.times(1)).logoutUser("plain-text-token");
    }

    @Test
    void logoutUserBeacon_extractedTokenIsBlank_doesNotLogOut() throws Exception {
        // 1. Setup: Send formats where the actual token part is empty
        String emptyJsonBody = "{\"token\":\"   \"}";
        String emptyFormBody = "token=";

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/auth/logout/beacon")
                .content(emptyJsonBody))
               .andExpect(status().isOk());

        mockMvc.perform(post("/auth/logout/beacon")
                .content(emptyFormBody))
               .andExpect(status().isOk());

        // Verify the final `!token.isBlank()` block successfully stopped the service call
        Mockito.verify(userService, Mockito.never()).logoutUser(Mockito.anyString());
    }

    @Test
    void handleHeartbeat_validToken_returnsNoContent() throws Exception {
        // 1. Setup: The service method returns void, so Mockito will just let it execute normally.

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/heartbeat")
                .header("Authorization", "valid-token"))
               .andExpect(status().isNoContent()); // 204 NO CONTENT
               
        // Verify the heartbeat service was called exactly once with the provided token
        Mockito.verify(userService, Mockito.times(1)).heartbeat("valid-token");
    }

    @Test
    void handleHeartbeat_missingHeader_returnsBadRequest() throws Exception {
        // 1. Setup: Spring handles the missing header, so no mock setup is needed.

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/heartbeat")) // Notice: No .header() provided!
               .andExpect(status().isBadRequest()); // 400 BAD REQUEST

        // Verify the service was protected and never called
        Mockito.verify(userService, Mockito.never()).heartbeat(Mockito.anyString());
    }

    @Test
    void handleHeartbeat_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup: Tell Mockito to throw an error when a bad token is used
        Mockito.doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"))
               .when(userService).heartbeat("bad-token");

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/heartbeat")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED
    }

    @Test
    void getUserHistory_invalidToken_throwsUnauthorized() throws Exception {
        // 1. Setup: Simulate an invalid token where the repository cannot find a user
        Mockito.when(userRepository.findByToken("bad-token")).thenReturn(null);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/users/1/history")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // 401 UNAUTHORIZED

        // Verify that the history service is entirely protected and never called
        Mockito.verify(historyService, Mockito.never())
                .getUserSessionHistory(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void getUserHistory_missingToken_throwsUnauthorized() throws Exception {
        mockMvc.perform(get("/users/1/history"))
               .andExpect(status().isUnauthorized());

        Mockito.verifyNoInteractions(historyService);
        Mockito.verify(userRepository, Mockito.never()).findByToken(Mockito.anyString());
    }


}
