package ch.uzh.ifi.hase.soprafs26.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendOnlineSummaryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendRequestIncomingDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.UserListHit;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.UserListPage;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.UserListQuery;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.util.AuthValidationRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;

/**
 * User Service
 * This class is the "worker" and responsible for all functionality related to
 * the user
 * (e.g., it creates, modifies, deletes, finds). The result will be passed back
 * to the caller.
 */


// user service ist sozusagen brain vom backend - enthält die gnaze logik:
    // COntroller empfängt requests und gibt es dem service und der service macht dann die ganze arbeit
@Service
@Transactional
public class UserService {
    private static final PasswordEncoder PASSWORD_ENCODER =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private static final long HEARTBEAT_WRITE_THROTTLE_SECONDS = 30;
    private static final int MAX_BIO_LENGTH = 180;
    private static final List<String> CHARACTER_COLOR_ORDER = List.of(
            "navy_blue",
            "light_blue",
            "dark_green",
            "light_green",
            "yellow",
            "orange",
            "red",
            "pink",
            "purple");
    private static final List<String> APPEARANCE_MODE_ORDER = List.of(
            "system",
            "light",
            "dark");
    private static final String PREFERRED_COLOR_UNASSIGNED = "__unassigned__";
    private static final Map<String, String> LEGACY_COLOR_ALIAS_MAP = Map.ofEntries(
            Map.entry("black", "navy_blue"),
            Map.entry("blue", "navy_blue"),
            Map.entry("green", "light_green"),
            Map.entry("default", "orange"),
            Map.entry("slate", "navy_blue"),
            Map.entry("graphite", "dark_green"),
            Map.entry("forest", "dark_green"),
            Map.entry("ocean", "light_blue"),
            Map.entry("teal", "light_blue"),
            Map.entry("coral", "red"),
            Map.entry("indigo", "navy_blue"),
            Map.entry("plum", "purple"),
            Map.entry("amber", "yellow"));

    private record UserUpdatePatch(
            String password,
            UserStatus status,
            Boolean isPublicLog,
            String bio,
            String profileCharacterId,
            List<String> preferredColorPriority,
            String menuBackgroundId,
            String gameBackgroundId,
            String primaryColorId,
            String appearanceMode,
            Boolean tutorialsEnabled,
            Integer musicVolume,
            Integer soundEffectsVolume,
            List<String> musicBlacklist) {
        static UserUpdatePatch fromEntity(User userInput) {
            if (userInput == null) {
                return new UserUpdatePatch(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            }
            return new UserUpdatePatch(
                    userInput.getPassword(),
                    userInput.getStatus(),
                    userInput.getIsPublicLog(),
                    userInput.getBio(),
                    userInput.getProfileCharacterId(),
                    userInput.getPreferredColorPriority(),
                    userInput.getMenuBackgroundId(),
                    userInput.getGameBackgroundId(),
                    userInput.getPrimaryColorId(),
                    userInput.getAppearanceMode(),
                    userInput.getTutorialsEnabled(),
                    userInput.getMusicVolume(),
                    userInput.getSoundEffectsVolume(),
                    userInput.getMusicBlacklist());
        }

        static UserUpdatePatch fromDto(UserPutDTO userInput) {
            if (userInput == null) {
                return new UserUpdatePatch(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            }
            return new UserUpdatePatch(
                    userInput.getPassword(),
                    userInput.getStatus(),
                    userInput.getIsPublicLog(),
                    userInput.getBio(),
                    userInput.getProfileCharacterId(),
                    userInput.getPreferredColorPriority(),
                    userInput.getMenuBackgroundId(),
                    userInput.getGameBackgroundId(),
                    userInput.getPrimaryColorId(),
                    userInput.getAppearanceMode(),
                    userInput.getTutorialsEnabled(),
                    userInput.getMusicVolume(),
                    userInput.getSoundEffectsVolume(),
                    userInput.getMusicBlacklist());
        }
    }

    public record PagedUser(
            User user,
            Integer globalRank,
            UserStatus visibleStatus,
            String joinableSessionId) {

        public PagedUser(User user, Integer globalRank) {
            this(user, globalRank, null, null);
        }
    }

    public record PagedUsers(
            List<PagedUser> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext) {

        public PagedUsers {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

	private final Logger log = LoggerFactory.getLogger(UserService.class);

	private final UserRepository userRepository;
    private final LobbyRepository lobbyRepository;
    private final UserListQueryRepository userListQueryRepository;
    private final OnlineUsersEventPublisher onlineUsersEventPublisher;
    private final DisconnectService disconnectService;
    private final LobbyService lobbyService;

	public UserService(@Qualifier("userRepository") UserRepository userRepository,
                       @Qualifier("lobbyRepository") LobbyRepository lobbyRepository,
	                   UserListQueryRepository userListQueryRepository,
	                   OnlineUsersEventPublisher onlineUsersEventPublisher,
                       @Lazy DisconnectService disconnectService,
                       @Lazy LobbyService lobbyService) {
		this.userRepository = userRepository;
        this.lobbyRepository = lobbyRepository;
        this.userListQueryRepository = userListQueryRepository;
		this.onlineUsersEventPublisher = onlineUsersEventPublisher;
		this.disconnectService = disconnectService;
        this.lobbyService = lobbyService;
	}

    private String normalizeCharacterColorId(String rawColorId) {
        String normalized = rawColorId == null ? "" : rawColorId.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return "";
        }
        if (LEGACY_COLOR_ALIAS_MAP.containsKey(normalized)) {
            normalized = LEGACY_COLOR_ALIAS_MAP.get(normalized);
        }
        return CHARACTER_COLOR_ORDER.contains(normalized) ? normalized : "";
    }

    private List<String> sanitizePreferredColorPriority(List<String> priorityList) {
        if (priorityList == null || priorityList.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String entry : priorityList) {
            String normalized = entry == null ? "" : entry.trim().toLowerCase();
            if (normalized.isEmpty() || PREFERRED_COLOR_UNASSIGNED.equals(normalized)) {
                continue;
            }
            normalized = normalizeCharacterColorId(normalized);
            if (normalized.isEmpty() || seen.contains(normalized)) {
                continue;
            }
            seen.add(normalized);
            sanitized.add(normalized);
        }
        return sanitized;
    }

    private String normalizeAppearanceMode(String rawAppearanceMode) {
        String normalized = rawAppearanceMode == null ? "" : rawAppearanceMode.trim().toLowerCase();
        return APPEARANCE_MODE_ORDER.contains(normalized) ? normalized : "";
    }

    private String normalizeAndValidateUsername(String username) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        if (normalizedUsername.length() < AuthValidationRules.USERNAME_MIN_LENGTH
                || normalizedUsername.length() > AuthValidationRules.USERNAME_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthValidationRules.USERNAME_HINT);
        }
        if (!AuthValidationRules.USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthValidationRules.USERNAME_HINT);
        }
        return normalizedUsername;
    }

    private void validatePassword(String password) {
        if (password == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required");
        }
        if (password.length() < AuthValidationRules.PASSWORD_MIN_LENGTH
                || password.length() > AuthValidationRules.PASSWORD_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthValidationRules.PASSWORD_HINT);
        }
        if (!AuthValidationRules.CREDENTIAL_FORMAT_PATTERN.matcher(password).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthValidationRules.PASSWORD_HINT);
        }
    }

    private boolean passwordMatchesAndUpgrade(User user, String candidatePassword) {
        String storedPassword = user == null ? null : user.getPassword();
        if (storedPassword == null || candidatePassword == null) {
            return false;
        }

        if (storedPassword.startsWith("{")) {
            try {
                if (!PASSWORD_ENCODER.matches(candidatePassword, storedPassword)) {
                    return false;
                }
                if (PASSWORD_ENCODER.upgradeEncoding(storedPassword)) {
                    user.setPassword(PASSWORD_ENCODER.encode(candidatePassword));
                }
                return true;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }

        boolean legacyMatch = MessageDigest.isEqual(
                storedPassword.getBytes(StandardCharsets.UTF_8),
                candidatePassword.getBytes(StandardCharsets.UTF_8));
        if (legacyMatch) {
            user.setPassword(PASSWORD_ENCODER.encode(candidatePassword));
        }
        return legacyMatch;
    }

    private boolean isUserInPlayingLobby(Long userId) {
        if (userId == null || lobbyRepository == null) {
            return false;
        }
        // check only players, not spectators
        return lobbyRepository.existsByStatusAndPlayerId("PLAYING", userId);
    }

    private User requireAuthenticatedUser(String token) {
        String normalizedToken = token == null ? "" : token.trim();
        if (normalizedToken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing token");
        }
        User me = userRepository.findByToken(normalizedToken);
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
        return me;
    }

    private Set<Long> toFriendIdSet(User user) {
        Set<Long> cleaned = new LinkedHashSet<>();
        if (user == null || user.getFriendUserIds() == null) {
            return cleaned;
        }
        Long selfId = user.getId();
        for (Long candidate : user.getFriendUserIds()) {
            if (candidate == null) {
                continue;
            }
            if (selfId != null && selfId.equals(candidate)) {
                continue;
            }
            cleaned.add(candidate);
        }
        return cleaned;
    }

    private boolean storeFriendIdSet(User user, Set<Long> friendIds) {
        List<Long> sorted = friendIds.stream()
                .filter(Objects::nonNull)
                .sorted(Long::compareTo)
                .toList();
        List<Long> next = new ArrayList<>(sorted);
        List<Long> current = user.getFriendUserIds() == null
                ? List.of()
                : new ArrayList<>(user.getFriendUserIds());
        if (current.equals(next)) {
            return false;
        }
        user.setFriendUserIds(next);
        return true;
    }

    private List<Long> resolveAcceptedFriendIds(User me) {
        Long myId = me.getId();
        if (myId == null) {
            return List.of();
        }

        Set<Long> mySelections = toFriendIdSet(me);
        if (mySelections.isEmpty()) {
            return List.of();
        }

        List<Long> accepted = new ArrayList<>();
        for (User candidate : userRepository.findAllById(mySelections)) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (toFriendIdSet(candidate).contains(myId)) {
                accepted.add(candidate.getId());
            }
        }
        accepted.sort(Long::compareTo);
        return accepted;
    }

    private List<User> resolveAcceptedFriends(User me) {
        List<Long> friendIds = resolveAcceptedFriendIds(me);
        if (friendIds.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllById(friendIds);
    }

    public List<Long> getAcceptedFriendIds(String token) {
        User me = requireAuthenticatedUser(token);
        return resolveAcceptedFriendIds(me);
    }

    public List<FriendRequestIncomingDTO> getIncomingFriendRequests(String token) {
        User me = requireAuthenticatedUser(token);
        Long myId = me.getId();
        if (myId == null) {
            return List.of();
        }

        Set<Long> mySelections = toFriendIdSet(me);
        List<FriendRequestIncomingDTO> incoming = new ArrayList<>();

        for (User candidate : userRepository.findUsersWhoSelectedFriendId(myId)) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            Long candidateId = candidate.getId();
            if (candidateId.equals(myId)) {
                continue;
            }

            Set<Long> candidateSelections = toFriendIdSet(candidate);
            boolean candidateRequestedMe = candidateSelections.contains(myId);
            boolean alreadyMutualOrOutgoingAccepted = mySelections.contains(candidateId);
            if (!candidateRequestedMe || alreadyMutualOrOutgoingAccepted) {
                continue;
            }

            FriendRequestIncomingDTO dto = new FriendRequestIncomingDTO();
            dto.setRequesterUserId(candidateId);
            dto.setRequesterUsername(candidate.getUsername());
            incoming.add(dto);
        }

        incoming.sort(
                Comparator.comparing(
                        (FriendRequestIncomingDTO dto) -> {
                            String username = dto.getRequesterUsername();
                            return username == null ? "" : username.toLowerCase();
                        })
                        .thenComparing(dto -> dto.getRequesterUserId() == null ? Long.MAX_VALUE : dto.getRequesterUserId()));
        return incoming;
    }

    public List<Long> getOutgoingPendingFriendRequestIds(String token) {
        User me = requireAuthenticatedUser(token);
        Long myId = me.getId();
        if (myId == null) {
            return List.of();
        }

        Set<Long> mySelections = toFriendIdSet(me);
        if (mySelections.isEmpty()) {
            return List.of();
        }

        List<Long> pending = new ArrayList<>();
        for (User candidate : userRepository.findAllById(mySelections)) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            Set<Long> candidateSelections = toFriendIdSet(candidate);
            if (!candidateSelections.contains(myId)) {
                pending.add(candidate.getId());
            }
        }
        pending.sort(Long::compareTo);
        return pending;
    }

    public FriendOnlineSummaryDTO getFriendOnlineSummary(String token) {
        User me = requireAuthenticatedUser(token);
        List<User> acceptedFriends = resolveAcceptedFriends(me);

        int playing = 0;
        int lobby = 0;
        int spectating = 0;
        int online = 0;

        for (User friend : acceptedFriends) {
            UserStatus status = friend.getStatus();
            if (status == null) {
                continue;
            }
            switch (status) {
                case ONLINE -> online += 1;
                case PLAYING -> {
                    playing += 1;
                    online += 1;
                }
                case LOBBY -> {
                    lobby += 1;
                    online += 1;
                }
                case SPECTATING -> {
                    spectating += 1;
                    online += 1;
                }
                default -> {
                    // offline and any unknown future state are not counted as online
                }
            }
        }

        FriendOnlineSummaryDTO summary = new FriendOnlineSummaryDTO();
        summary.setFriendsOnline(online);
        summary.setPlaying(playing);
        summary.setLobby(lobby);
        summary.setSpectating(spectating);
        return summary;
    }

    public void sendFriendRequest(String token, Long targetUserId) {
        User me = requireAuthenticatedUser(token);
        Long myId = me.getId();
        if (targetUserId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target user id is required");
        }
        if (myId != null && myId.equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot friend yourself");
        }

        User target = getUserById(targetUserId);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        Set<Long> mySelections = toFriendIdSet(me);
        mySelections.add(targetUserId);
        if (storeFriendIdSet(me, mySelections)) {
            userRepository.save(me);
            userRepository.flush();
        }
    }

    public void acceptFriendRequest(String token, Long requesterUserId) {
        User me = requireAuthenticatedUser(token);
        if (requesterUserId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requester user id is required");
        }

        User requester = getUserById(requesterUserId);
        Set<Long> requesterSelections = toFriendIdSet(requester);
        if (!requesterSelections.contains(me.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No incoming friend request from this user");
        }

        Set<Long> mySelections = toFriendIdSet(me);
        mySelections.add(requesterUserId);
        if (storeFriendIdSet(me, mySelections)) {
            userRepository.save(me);
            userRepository.flush();
        }
    }

    public void removeFriendOrRequest(String token, Long otherUserId) {
        User me = requireAuthenticatedUser(token);
        Long myId = me.getId();
        if (otherUserId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Other user id is required");
        }
        if (myId != null && myId.equals(otherUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot remove yourself");
        }

        User other = getUserById(otherUserId);

        Set<Long> mySelections = toFriendIdSet(me);
        Set<Long> otherSelections = toFriendIdSet(other);
        boolean changedMine = mySelections.remove(otherUserId);
        boolean changedOther = myId != null && otherSelections.remove(myId);

        if (changedMine && storeFriendIdSet(me, mySelections)) {
            userRepository.save(me);
        }
        if (changedOther && storeFriendIdSet(other, otherSelections)) {
            userRepository.save(other);
        }
        if (changedMine || changedOther) {
            userRepository.flush();
        }
    }

    // holt alle user aus Datenbank und gibt sie dem controller
	public List<User> getUsers() {
        List<User> users = this.userRepository.findAll();

        // Public user cards need preferred colors, but not the private music
        // blacklist. @BatchSize on the collection prevents an N+1 query.
        for (User user : users) {
            if (user != null && user.getPreferredColorPriority() != null) {
                user.getPreferredColorPriority().size();
            }
        }

        refreshDerivedStatisticsAndRanking(users);
		return users;
	}

    @Transactional(readOnly = true)
    public PagedUsers getUsersPage(UserListQuery query) {
        UserListPage queriedPage = userListQueryRepository.findPage(query);
        List<Long> orderedIds = queriedPage.hits().stream()
                .map(UserListHit::userId)
                .toList();
        Map<Long, User> usersById = new LinkedHashMap<>();
        for (User user : userRepository.findAllById(orderedIds)) {
            if (user != null && user.getId() != null) {
                usersById.put(user.getId(), user);
            }
        }

        List<PagedUser> orderedUsers = new ArrayList<>(orderedIds.size());
        for (UserListHit hit : queriedPage.hits()) {
            User user = usersById.get(hit.userId());
            if (user == null) {
                continue;
            }
            if (user.getPreferredColorPriority() != null) {
                user.getPreferredColorPriority().size();
            }
            orderedUsers.add(new PagedUser(
                    user,
                    hit.globalRank(),
                    hit.visibleStatus(),
                    hit.joinableSessionId()));
        }

        long totalPagesLong = queriedPage.totalElements() / query.size();
        if (queriedPage.totalElements() % query.size() != 0L) {
            totalPagesLong++;
        }
        int totalPages = (int) Math.min(Integer.MAX_VALUE, totalPagesLong);
        boolean hasNext = ((long) query.page() + 1L) * query.size() < queriedPage.totalElements();
        return new PagedUsers(
                orderedUsers,
                query.page(),
                query.size(),
                queriedPage.totalElements(),
                totalPages,
                hasNext);
    }

    /**
     * Derives ranking fields from counters maintained when sessions end.
     * The interactive /users request therefore never scans historical sessions.
     */
    private void refreshDerivedStatisticsAndRanking(List<User> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        List<User> ordered = new ArrayList<>(users);
        for (User user : ordered) {
            int gamesPlayed = Math.max(0, Objects.requireNonNullElse(user.getGamesPlayed(), 0));
            int totalPoints = Objects.requireNonNullElse(user.getTotalPointsAccumulated(), 0);
            int averageSession = gamesPlayed == 0
                    ? 0
                    : (int) Math.round((double) totalPoints / gamesPlayed);
            if (!Objects.equals(user.getAverageScorePerSession(), averageSession)) {
                user.setAverageScorePerSession(averageSession);
            }
        }

        ordered.sort(Comparator
                .comparing(
                        (User user) -> Objects.requireNonNullElse(user.getGamesWon(), 0),
                        Comparator.reverseOrder())
                .thenComparing(
                        (User user) -> Objects.requireNonNullElse(user.getGamesPlayed(), 0),
                        Comparator.reverseOrder())
                .thenComparing(
                        (User user) -> Objects.requireNonNullElse(user.getAverageScorePerSession(), 0),
                        Integer::compareTo)
                .thenComparing(User::getId, Comparator.nullsFirst(Long::compareTo)));

        int rank = 1;
        for (User user : ordered) {
            if (!Objects.equals(user.getOverallRank(), rank)) {
                user.setOverallRank(rank);
            }
            rank++;
        }
    }

	public User createUser(User newUser) {
        String normalizedUsername = normalizeAndValidateUsername(newUser.getUsername());
        newUser.setUsername(normalizedUsername);
        String rawPassword = newUser.getPassword();
        validatePassword(rawPassword);
        if (newUser.getBio() != null) {
            String normalizedBio = newUser.getBio().trim();
            if (normalizedBio.length() > MAX_BIO_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bio must be 180 characters or fewer.");
            }
            newUser.setBio(normalizedBio);
        } else {
            newUser.setBio("");
        }
		newUser.setToken(UUID.randomUUID().toString()); // generiert einen zufälligen eindeutigen Token
		newUser.setStatus(UserStatus.ONLINE); // neuer User ist sofort ONLINE
        newUser.setCreationDate(LocalDate.now()); // setzt heutiges datum
		checkIfUserExists(newUser); // schaut ob dese user beriets exisiter
		newUser.setPassword(PASSWORD_ENCODER.encode(rawPassword));
		// saves the given entity but data is only persisted in the database once
        // speichert in datenbank
		newUser = userRepository.save(newUser);
		userRepository.flush();

		log.debug("Created Information for User: {}", newUser);
		onlineUsersEventPublisher.broadcastOnlineUsers();
		return newUser;
	}

	/**
	 * This is a helper method that will check the uniqueness criteria of the
	 * username and the name
	 * defined in the User entity. The method will do nothing if the input is unique
	 * and throw an error otherwise.
	 *
	 * @param userToBeCreated
	 * @throws org.springframework.web.server.ResponseStatusException
	 * @see User
	 */
	//private void checkIfUserExists(User userToBeCreated) {
		//User userByUsername = userRepository.findByUsername(userToBeCreated.getUsername());
		//User userByName = userRepository.findByName(userToBeCreated.getName());

		//String baseErrorMessage = "The %s provided %s not unique. Therefore, the user could not be created!";
		//if (userByUsername != null && userByName != null) {
		//	throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				//	String.format(baseErrorMessage, "username and the name", "are"));
		//} else if (userByUsername != null) {
		//	throw new ResponseStatusException(HttpStatus.CONFLICT, "This username is already taken, chose a new one");
		//} else if (userByName != null) {
		//	throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(baseErrorMessage, "name", "is"));
		//}
	//}

    // name prüfung auskommentiert von mir weil sie nicht mehr gebraucht wird, sondern nur check via username
    // schauen ob username bereits exisitier, wenn ja conflict error
    private void checkIfUserExists(User userToBeCreated) {
        User userByUsername = userRepository.findByUsername(userToBeCreated.getUsername());

        if (userByUsername != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
    }




    // sucht user anhand von ID falls nicht gefunden not found error
    public User getUserById(Long userId) { return userRepository.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public User getUserProfileById(Long userId) {
        User user = getUserById(userId);
        initializeProfileCollections(user);
        return user;
    }

    private void initializeProfileCollections(User user) {
        if (user == null) {
            return;
        }
        if (user.getPreferredColorPriority() != null) {
            user.getPreferredColorPriority().size();
        }
        if (user.getMusicBlacklist() != null) {
            user.getMusicBlacklist().size();
        }
    }
// änderung des PW in datenbank
    public void updateUser(Long userId, User userInput) {
        applyUserUpdatePatch(userId, UserUpdatePatch.fromEntity(userInput));
    }

    public void updateUser(Long userId, UserPutDTO userInput) {
        applyUserUpdatePatch(userId, UserUpdatePatch.fromDto(userInput));
    }

    private void applyUserUpdatePatch(Long userId, UserUpdatePatch patch) {
        User user = getUserById(userId);
        boolean shouldRefreshLobbyPresentation = false;
        if (patch.password() != null) {
            validatePassword(patch.password());
            user.setPassword(PASSWORD_ENCODER.encode(patch.password()));
        }
        if (patch.bio() != null) {
            String normalizedBio = patch.bio().trim();
            if (normalizedBio.length() > MAX_BIO_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bio must be 180 characters or fewer.");
            }
            user.setBio(normalizedBio);
        }
        if (patch.status() != null) {
            user.setStatus(patch.status());
        }
        if (patch.isPublicLog() != null) {
            user.setIsPublicLog(patch.isPublicLog());
        }
        if (patch.profileCharacterId() != null) {
            String nextCharacterId = patch.profileCharacterId().trim();
            if (!nextCharacterId.isEmpty()) {
                user.setProfileCharacterId(nextCharacterId);
            }
        }
        if (patch.preferredColorPriority() != null) {
            List<String> sanitizedPriority = sanitizePreferredColorPriority(patch.preferredColorPriority());
            user.setPreferredColorPriority(sanitizedPriority);
            shouldRefreshLobbyPresentation = true;
        }
        if (patch.menuBackgroundId() != null) {
            String nextMenuBackgroundId = patch.menuBackgroundId().trim();
            if (!nextMenuBackgroundId.isEmpty()) {
                user.setMenuBackgroundId(nextMenuBackgroundId);
            }
        }
        if (patch.gameBackgroundId() != null) {
            String nextGameBackgroundId = patch.gameBackgroundId().trim();
            if (!nextGameBackgroundId.isEmpty()) {
                user.setGameBackgroundId(nextGameBackgroundId);
            }
        }
        if (patch.primaryColorId() != null) {
            String nextPrimaryColorId = normalizeCharacterColorId(patch.primaryColorId());
            if (!nextPrimaryColorId.isEmpty()) {
                user.setPrimaryColorId(nextPrimaryColorId);
            }
        }
        if (patch.appearanceMode() != null) {
            String nextAppearanceMode = normalizeAppearanceMode(patch.appearanceMode());
            if (!nextAppearanceMode.isEmpty()) {
                user.setAppearanceMode(nextAppearanceMode);
            }
        }
        if (patch.tutorialsEnabled() != null) {
            user.setTutorialsEnabled(patch.tutorialsEnabled());
        }
        if (patch.musicVolume() != null) {
            int clampedMusicVolume = Math.max(0, Math.min(100, patch.musicVolume()));
            user.setMusicVolume(clampedMusicVolume);
        }
        if (patch.soundEffectsVolume() != null) {
            int clampedEffectsVolume = Math.max(0, Math.min(100, patch.soundEffectsVolume()));
            user.setSoundEffectsVolume(clampedEffectsVolume);
        }
        if (patch.musicBlacklist() != null) {
            List<String> sanitizedBlacklist = new ArrayList<>();
            for (String tag : patch.musicBlacklist()) {
                String normalized = tag == null ? "" : tag.trim();
                if (!normalized.isEmpty()) {
                    sanitizedBlacklist.add(normalized);
                }
            }
            user.setMusicBlacklist(sanitizedBlacklist);
        }
        userRepository.save(user);
        userRepository.flush();
        if (shouldRefreshLobbyPresentation && lobbyService != null) {
            lobbyService.refreshWaitingLobbyPresentationForUser(user.getId());
        }
        onlineUsersEventPublisher.broadcastOnlineUsers();
    }
// schauen ob username bereits existier und ob PW korrekt ist, wenn nicht unauthorized fehler, falls
    // alles gut dann wird stauts auf online gesetzet
    public User loginUser(String username, String password) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isEmpty() || password == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        User user = userRepository.findByUsername(normalizedUsername);
        if (user == null || !passwordMatchesAndUpgrade(user, password)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

// falls ja status ändern zu online
        user.setStatus(resolveStatusForLogin(user.getId()));
        userRepository.save(user);
        userRepository.flush();
        initializeProfileCollections(user);
        onlineUsersEventPublisher.broadcastOnlineUsers();
        return user;
    }

    private UserStatus resolveStatusForLogin(Long userId) {
        if (userId == null || lobbyRepository == null) {
            return UserStatus.ONLINE;
        }

        List<Object[]> presenceRows = lobbyRepository.findHighestPriorityPresenceForUser(userId);
        if (presenceRows != null && !presenceRows.isEmpty()) {
            Object[] row = presenceRows.get(0);
            String lobbyStatus = row != null && row.length > 0 && row[0] != null
                    ? row[0].toString()
                    : "";
            boolean player = row != null && row.length > 1 && Boolean.TRUE.equals(row[1]);
            if ("PLAYING".equals(lobbyStatus)) {
                return player ? UserStatus.PLAYING : UserStatus.SPECTATING;
            }
            if ("WAITING".equals(lobbyStatus)) {
                return player ? UserStatus.LOBBY : UserStatus.SPECTATING;
            }
        }

        return UserStatus.ONLINE;
    }

	// logout needs to be authenticated according to REST interface
	public void logoutUser(String token) {
		User foundUser = userRepository.findByToken(token);

		if(foundUser == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!");
		}

        if (isUserInPlayingLobby(foundUser.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot logout during an active game");
        }

        if (lobbyService != null) {
            lobbyService.removeUserFromLobbiesForLogoutSafety(foundUser.getId());
        }

		foundUser.setStatus(UserStatus.OFFLINE);
		// this saves a random token to the user but the token is never revealed and no-one can use it
		// acts as a safety feature s.t. a user that is logged out has no valid token saved in the DB
		foundUser.setToken(UUID.randomUUID().toString());
		userRepository.save(foundUser);
		userRepository.flush();
        if (disconnectService != null) {
            disconnectService.cancelDisconnectTimer(foundUser.getId());
        }
        onlineUsersEventPublisher.broadcastOnlineUsers();
	}

	public void heartbeat(String token) {
    	User user = userRepository.findByToken(token);
    	if (user == null) return;
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant last = user.getLastHeartbeat();
        boolean shouldSaveHeartbeat = last == null || now.isAfter(last.plusSeconds(HEARTBEAT_WRITE_THROTTLE_SECONDS));
        boolean shouldResolveStatus = user.getStatus() == null || user.getStatus() == UserStatus.OFFLINE;
        UserStatus resolvedStatus = user.getStatus() == null ? UserStatus.ONLINE : user.getStatus();
        boolean statusNeedsUpdate = false;
        if (shouldResolveStatus) {
            resolvedStatus = resolveStatusForLogin(user.getId());
            statusNeedsUpdate = user.getStatus() != resolvedStatus;
        }
        boolean shouldPersist = shouldSaveHeartbeat || statusNeedsUpdate;
        if (shouldPersist) {
            if (shouldSaveHeartbeat) {
                user.setLastHeartbeat(now);
            }
            if (statusNeedsUpdate) {
                user.setStatus(resolvedStatus);
            }
            userRepository.save(user);
        }
        if (disconnectService != null) {
            // A fresh authenticated heartbeat means the user is back and active.
            // Clear any stale "timed out in playing" flag to prevent false auto-Cabo.
            disconnectService.handleReconnect(user.getId());
        }
        if (statusNeedsUpdate) {
            onlineUsersEventPublisher.broadcastOnlineUsers();
        }
	}
}
