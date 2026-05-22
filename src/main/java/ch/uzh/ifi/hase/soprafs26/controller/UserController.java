package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.AuthRulesDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendOnlineSummaryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendRequestIncomingDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.SessionHistoryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserLoginDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;
import ch.uzh.ifi.hase.soprafs26.service.HistoryService;
import ch.uzh.ifi.hase.soprafs26.service.HotEndpointRateLimiter;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.service.UserService;
import ch.uzh.ifi.hase.soprafs26.util.AuthValidationRules;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class UserController {

    private final UserService userService;
    private final HistoryService historyService;
    private final LobbyService lobbyService;
    private final UserRepository userRepository;
    private final HotEndpointRateLimiter hotEndpointRateLimiter;

    public UserController(
            UserService userService,
            HistoryService historyService,
            LobbyService lobbyService,
            UserRepository userRepository) {
        this(userService, historyService, lobbyService, userRepository, null);
    }

    @Autowired
    public UserController(
            UserService userService,
            HistoryService historyService,
            LobbyService lobbyService,
            UserRepository userRepository,
            HotEndpointRateLimiter hotEndpointRateLimiter) {
        this.userService = userService;
        this.historyService = historyService;
        this.lobbyService = lobbyService;
        this.userRepository = userRepository;
        this.hotEndpointRateLimiter = hotEndpointRateLimiter;
    }

    private void enforceHotReadLimit(String endpointKey, String token, HttpServletRequest request) {
        if (hotEndpointRateLimiter == null) {
            return;
        }
        String forwardedForHeader = request == null ? null : request.getHeader("X-Forwarded-For");
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        hotEndpointRateLimiter.enforceHotReadLimit(endpointKey, token, forwardedForHeader, remoteAddress);
    }

    private UserStatus normalizeVisibleStatus(UserStatus persistedStatus, UserStatus lobbyPresenceStatus) {
        if (lobbyPresenceStatus != null) {
            return lobbyPresenceStatus;
        }
        if (persistedStatus == UserStatus.LOBBY
                || persistedStatus == UserStatus.PLAYING
                || persistedStatus == UserStatus.SPECTATING) {
            return UserStatus.ONLINE;
        }
        return persistedStatus;
    }

    @GetMapping("/users")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public List<UserGetDTO> getAllUsers(HttpServletRequest request) {
        enforceHotReadLimit("users-list", null, request);
        List<User> users = userService.getUsers();
        List<UserGetDTO> userGetDTOs = new ArrayList<>();

        List<Long> userIds = users.stream()
                .map(User::getId)
                .filter(id -> id != null)
                .toList();
        LobbyService.LobbyPresenceLookupResult presenceLookup =
                lobbyService.resolveJoinableSessionsAndPresenceForUsers(userIds);
        if (presenceLookup == null) {
            presenceLookup = new LobbyService.LobbyPresenceLookupResult(Map.of(), Map.of());
        }
        Map<Long, String> joinableSessionIdByUserId = presenceLookup.joinableSessionIdByUserId();
        if (joinableSessionIdByUserId == null) {
            joinableSessionIdByUserId = Map.of();
        }
        Map<Long, UserStatus> lobbyPresenceStatusByUserId = presenceLookup.statusByUserId();
        if (lobbyPresenceStatusByUserId == null) {
            lobbyPresenceStatusByUserId = Map.of();
        }

        for (User user : users) {
            UserGetDTO dto = DTOMapper.INSTANCE.convertEntityToUserGetDTO(user);
            dto.setToken(null);
            if (user.getId() != null) {
                dto.setJoinableSessionId(joinableSessionIdByUserId.get(user.getId()));
            } else {
                dto.setJoinableSessionId(null);
            }
            UserStatus lobbyPresenceStatus = user.getId() == null ? null : lobbyPresenceStatusByUserId.get(user.getId());
            dto.setStatus(normalizeVisibleStatus(user.getStatus(), lobbyPresenceStatus));
            userGetDTOs.add(dto);
        }
        return userGetDTOs;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    public UserGetDTO createUser(@Valid @RequestBody UserPostDTO userPostDTO) {
        User userInput = DTOMapper.INSTANCE.convertUserPostDTOtoEntity(userPostDTO);
        User createdUser = userService.createUser(userInput);
        return DTOMapper.INSTANCE.convertEntityToUserGetDTO(createdUser);
    }

    @GetMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public UserGetDTO getUserById(@PathVariable Long userId) {
        User user = userService.getUserById(userId);
        UserGetDTO dto = DTOMapper.INSTANCE.convertEntityToUserGetDTO(user);
        dto.setToken(null);
        UserStatus lobbyPresenceStatus = null;
        if (user.getId() != null) {
            dto.setJoinableSessionId(lobbyService.resolveJoinableSessionIdForUser(user.getId()));
            lobbyPresenceStatus = lobbyService.resolveLobbyPresenceStatusForUser(user.getId());
        } else {
            dto.setJoinableSessionId(null);
        }
        dto.setStatus(normalizeVisibleStatus(user.getStatus(), lobbyPresenceStatus));
        return dto;
    }

    @PutMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateUser(@PathVariable Long userId, @Valid @RequestBody UserPutDTO userPutDTO) {
        userService.updateUser(userId, userPutDTO);
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public UserGetDTO loginUser(@Valid @RequestBody UserLoginDTO userPostDTO) {
        User user = userService.loginUser(userPostDTO.getUsername(), userPostDTO.getPassword());
        return DTOMapper.INSTANCE.convertEntityToUserGetDTO(user);
    }

    @GetMapping("/auth/rules")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public AuthRulesDTO getAuthRules() {
        AuthRulesDTO rules = new AuthRulesDTO();

        AuthRulesDTO.UsernameRulesDTO usernameRules = new AuthRulesDTO.UsernameRulesDTO();
        usernameRules.setMinLength(AuthValidationRules.USERNAME_MIN_LENGTH);
        usernameRules.setMaxLength(AuthValidationRules.USERNAME_MAX_LENGTH);
        usernameRules.setPattern(AuthValidationRules.USERNAME_REGEX);
        usernameRules.setAllowedCharactersPattern(AuthValidationRules.USERNAME_ALLOWED_CHAR_REGEX);
        usernameRules.setHint(AuthValidationRules.USERNAME_HINT);

        AuthRulesDTO.PasswordRulesDTO passwordRules = new AuthRulesDTO.PasswordRulesDTO();
        passwordRules.setMinLength(AuthValidationRules.PASSWORD_MIN_LENGTH);
        passwordRules.setMaxLength(AuthValidationRules.PASSWORD_MAX_LENGTH);
        passwordRules.setPattern(AuthValidationRules.CREDENTIAL_FORMAT_REGEX);
        passwordRules.setAllowedCharactersPattern(AuthValidationRules.CREDENTIAL_ALLOWED_CHAR_REGEX);
        passwordRules.setHint(AuthValidationRules.PASSWORD_HINT);
        passwordRules.setAsciiOnly(true);
        passwordRules.setRequiresUppercase(true);
        passwordRules.setRequiresSpecialSymbol(true);

        rules.setUsername(usernameRules);
        rules.setPassword(passwordRules);
        return rules;
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.OK)
    public void logoutUser(@RequestHeader("Authorization") String token) {
        userService.logoutUser(token);
    }

    @PostMapping("/auth/logout/beacon")
    @ResponseStatus(HttpStatus.OK)
    public void logoutUserBeacon(@RequestBody(required = false) String body) {
        if (body == null || body.isBlank()) {
            return;
        }

        String cleaned = body.trim();
        String token;
        if (cleaned.startsWith("{")) {
            token = cleaned.replace("{", "")
                    .replace("}", "")
                    .replace("\"token\":", "")
                    .replace("\"", "")
                    .trim();
        } else if (cleaned.startsWith("token=")) {
            token = URLDecoder.decode(cleaned.substring("token=".length()), StandardCharsets.UTF_8);
        } else {
            token = cleaned;
        }

        if (!token.isBlank()) {
            userService.logoutUser(token);
        }
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleHeartbeat(@RequestHeader("Authorization") String token) {
        userService.heartbeat(token);
    }

    @GetMapping("/users/me/friends/ids")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public List<Long> getMyFriendIds(@RequestHeader("Authorization") String token, HttpServletRequest request) {
        enforceHotReadLimit("friend-ids", token, request);
        return userService.getAcceptedFriendIds(token);
    }

    @GetMapping("/users/me/friends/requests/incoming")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public List<FriendRequestIncomingDTO> getMyIncomingFriendRequests(
            @RequestHeader("Authorization") String token,
            HttpServletRequest request) {
        enforceHotReadLimit("friend-requests-incoming", token, request);
        return userService.getIncomingFriendRequests(token);
    }

    @GetMapping("/users/me/friends/requests/outgoing/ids")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public List<Long> getMyOutgoingPendingFriendRequestIds(
            @RequestHeader("Authorization") String token,
            HttpServletRequest request) {
        enforceHotReadLimit("friend-requests-outgoing", token, request);
        return userService.getOutgoingPendingFriendRequestIds(token);
    }

    @GetMapping("/users/me/friends/online-summary")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public FriendOnlineSummaryDTO getMyFriendsOnlineSummary(
            @RequestHeader("Authorization") String token,
            HttpServletRequest request) {
        enforceHotReadLimit("friends-online-summary", token, request);
        return userService.getFriendOnlineSummary(token);
    }

    @PostMapping("/users/me/friends/requests/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendFriendRequest(
            @RequestHeader("Authorization") String token,
            @PathVariable Long targetUserId) {
        userService.sendFriendRequest(token, targetUserId);
    }

    @PostMapping("/users/me/friends/requests/{requesterUserId}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptFriendRequest(
            @RequestHeader("Authorization") String token,
            @PathVariable Long requesterUserId) {
        userService.acceptFriendRequest(token, requesterUserId);
    }

    @DeleteMapping("/users/me/friends/requests/{otherUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void declineOrCancelFriendRequest(
            @RequestHeader("Authorization") String token,
            @PathVariable Long otherUserId) {
        userService.removeFriendOrRequest(token, otherUserId);
    }

    @DeleteMapping("/users/me/friends/{otherUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFriend(
            @RequestHeader("Authorization") String token,
            @PathVariable Long otherUserId) {
        userService.removeFriendOrRequest(token, otherUserId);
    }

    @GetMapping("/users/{id}/history")
    public List<SessionHistoryDTO> getUserHistory(@PathVariable Long id, @RequestHeader("Authorization") String token) {
        User user = userRepository.findByToken(token);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token!");
        }

        List<Session> sessionHistory = historyService.getUserSessionHistory(id);
        return DTOMapper.INSTANCE.convertEntityListToSessionHistoryDTOList(sessionHistory);
    }
}
