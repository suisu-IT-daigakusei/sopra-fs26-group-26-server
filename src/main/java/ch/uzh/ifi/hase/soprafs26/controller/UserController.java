package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.Direction;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.Sort;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.UserListQuery;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.View;
import ch.uzh.ifi.hase.soprafs26.rest.dto.AuthRulesDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendOnlineSummaryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendRequestIncomingDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.SessionHistoryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserLoginDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPageDTO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
public class UserController {

    private static final int DEFAULT_USER_PAGE_SIZE = 20;
    private static final int MAX_USER_PAGE_SIZE = 100;
    private static final int MAX_USER_SEARCH_LENGTH = 64;
    private static final int MAX_USER_INCLUDE_IDS = 100;
    private static final int MAX_USER_EXCLUDE_IDS = 20;

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
    public UserPageDTO getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "directory") String view,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(name = "status", required = false) List<String> rawStatuses,
            @RequestParam(defaultValue = "false") boolean friendsOnly,
            @RequestParam(name = "excludeId", required = false) List<Long> rawExcludeIds,
            @RequestParam(name = "id", required = false) List<Long> rawIncludeIds,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestHeader(value = "Authorization", required = false) String token,
            HttpServletRequest request) {
        View parsedView = parseUserListView(view);
        String normalizedSearch = search == null ? "" : search.trim();
        validateUserPageBounds(page, size, normalizedSearch);
        Set<UserStatus> statuses = parseUserStatuses(rawStatuses);
        Set<Long> excludeIds = parseBoundedIds(rawExcludeIds, MAX_USER_EXCLUDE_IDS, "excludeId");
        Set<Long> includeIds = parseBoundedIds(rawIncludeIds, MAX_USER_INCLUDE_IDS, "id");
        Sort parsedSort = parseUserListSort(sort, parsedView);
        Direction parsedDirection = parseUserListDirection(direction);

        // The public directory is rate-limited by network caller rather than by
        // the untrusted Authorization value. This must happen before a
        // friends-only token lookup so rotating fake tokens neither bypass the
        // limit nor allocate one rate-limit bucket per fake token.
        enforceHotReadLimit(
                "users-list-" + parsedView.name().toLowerCase(Locale.ROOT),
                null,
                request);

        Long viewerId = null;
        if (friendsOnly) {
            User viewer = token == null || token.isBlank() ? null : userRepository.findByToken(token);
            if (viewer == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid token required for friends-only users");
            }
            viewerId = viewer.getId();
        }

        UserService.PagedUsers userPage = userService.getUsersPage(new UserListQuery(
                parsedView,
                page,
                size,
                normalizedSearch,
                statuses,
                friendsOnly,
                viewerId,
                excludeIds,
                includeIds,
                parsedSort,
                parsedDirection));
        List<UserGetDTO> userGetDTOs = new ArrayList<>();

        for (UserService.PagedUser pagedUser : userPage.items()) {
            User user = pagedUser.user();
            UserGetDTO dto = DTOMapper.INSTANCE.convertEntityToPublicUserGetDTO(user);
            dto.setToken(null);
            if (pagedUser.globalRank() != null) {
                dto.setOverallRank(pagedUser.globalRank());
            }
            dto.setJoinableSessionId(pagedUser.joinableSessionId());
            dto.setStatus(pagedUser.visibleStatus() == null
                    ? normalizeVisibleStatus(user.getStatus(), null)
                    : pagedUser.visibleStatus());
            userGetDTOs.add(dto);
        }
        return new UserPageDTO(
                userGetDTOs,
                userPage.page(),
                userPage.size(),
                userPage.totalElements(),
                userPage.totalPages(),
                userPage.hasNext());
    }

    private void validateUserPageBounds(int page, int size, String search) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be at least 0");
        }
        if (size < 1 || size > MAX_USER_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "size must be between 1 and " + MAX_USER_PAGE_SIZE);
        }
        if (search.length() > MAX_USER_SEARCH_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "q must be at most " + MAX_USER_SEARCH_LENGTH + " characters");
        }
    }

    private View parseUserListView(String rawView) {
        String normalized = normalizeQueryEnum(rawView);
        return switch (normalized) {
            case "", "directory" -> View.DIRECTORY;
            case "leaderboard" -> View.LEADERBOARD;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported user view");
        };
    }

    private Sort parseUserListSort(String rawSort, View view) {
        String normalized = normalizeQueryEnum(rawSort);
        if (normalized.isEmpty()) {
            return view == View.LEADERBOARD ? Sort.RANK : Sort.USERNAME;
        }
        Sort parsed = switch (normalized) {
            case "username" -> Sort.USERNAME;
            case "roundsplayed" -> Sort.ROUNDS_PLAYED;
            case "averagescore" -> Sort.AVERAGE_SCORE;
            case "roundwinrate" -> Sort.ROUND_WIN_RATE;
            case "status" -> Sort.STATUS;
            case "rank" -> Sort.RANK;
            case "gameswinrate" -> Sort.GAMES_WIN_RATE;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported user sort");
        };
        if (parsed == Sort.RANK && view != View.LEADERBOARD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rank sort requires leaderboard view");
        }
        return parsed;
    }

    private Direction parseUserListDirection(String rawDirection) {
        return switch (normalizeQueryEnum(rawDirection)) {
            case "", "asc" -> Direction.ASC;
            case "desc" -> Direction.DESC;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sort direction");
        };
    }

    private Set<UserStatus> parseUserStatuses(List<String> rawStatuses) {
        if (rawStatuses == null || rawStatuses.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<UserStatus> statuses = new LinkedHashSet<>();
        for (String rawStatusGroup : rawStatuses) {
            if (rawStatusGroup == null) {
                continue;
            }
            for (String rawStatus : rawStatusGroup.split(",")) {
                String normalized = rawStatus.trim().toUpperCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    continue;
                }
                try {
                    statuses.add(UserStatus.valueOf(normalized));
                } catch (IllegalArgumentException exception) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported user status");
                }
            }
        }
        return Set.copyOf(statuses);
    }

    private Set<Long> parseBoundedIds(List<Long> rawIds, int maxIds, String parameterName) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : rawIds) {
            if (id == null || id <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        parameterName + " values must be positive");
            }
            ids.add(id);
        }
        if (ids.size() > maxIds) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    parameterName + " accepts at most " + maxIds + " values");
        }
        return Set.copyOf(ids);
    }

    private String normalizeQueryEnum(String rawValue) {
        return rawValue == null
                ? ""
                : rawValue.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
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
        User user = userService.getUserProfileById(userId);
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
    public void updateUser(
            @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String token,
            @Valid @RequestBody UserPutDTO userPutDTO) {
        User authenticatedUser = token == null || token.isBlank()
                ? null
                : userRepository.findByToken(token);
        if (authenticatedUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
        if (authenticatedUser.getId() == null || !authenticatedUser.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot update another user");
        }
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
    public List<SessionHistoryDTO> getUserHistory(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (token == null || token.isBlank() || userRepository.findByToken(token) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token!");
        }

        userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

        List<Session> sessionHistory = historyService.getUserSessionHistory(id, limit, offset);
        return DTOMapper.INSTANCE.convertEntityListToSessionHistoryDTOList(sessionHistory);
    }
}
