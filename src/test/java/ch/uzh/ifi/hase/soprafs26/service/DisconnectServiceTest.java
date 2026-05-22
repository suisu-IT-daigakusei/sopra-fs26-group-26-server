package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.config.settings.TimeoutSettingsProperties;
import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisconnectServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private LobbyService lobbyService;

    @Mock
    private GameService gameService;

    @Mock
    private WebSocketSessionTracker webSocketSessionTracker;

    private DisconnectService disconnectService;
    private static final String AUTO_LOGOUT_TOKEN = "keep-token";

    @BeforeEach
    void setUp() {
        TimeoutSettingsProperties timeoutSettings = new TimeoutSettingsProperties();
        disconnectService = new DisconnectService(
                userRepository,
                lobbyService,
                gameService,
                timeoutSettings,
                webSocketSessionTracker);
    }

    @Test
    void handleConnectionLoss_spectator_doesNotEnterGraceTimer() {
        User spectator = new User();
        spectator.setId(42L);
        spectator.setStatus(UserStatus.SPECTATING);
        when(userRepository.findById(42L)).thenReturn(Optional.of(spectator));

        disconnectService.handleConnectionLoss(42L);

        assertFalse(disconnectService.isPlayerInGracePeriod(42L));
        verify(lobbyService, never()).isUserInLobbyContext(42L);
        verify(lobbyService, never()).findWebsocketGraceSecondsForUser(42L);
    }

    @Test
    void checkIdleUsers_spectator_isSkipped() {
        User spectator = createUser(7L, UserStatus.SPECTATING, AUTO_LOGOUT_TOKEN, Instant.now().minusSeconds(9999));

        when(userRepository.findByStatusNot(UserStatus.OFFLINE)).thenReturn(List.of(spectator));
        when(lobbyService.getPlayingLobbyPlayerIdsSnapshot()).thenReturn(Set.of());

        disconnectService.checkIdleUsers();

        verify(lobbyService, never()).handlePermanentDisconnect(7L);
        verify(userRepository, never()).save(spectator);
    }

    @Test
    void checkIdleUsers_staleOnlineUser_triggersPermanentDisconnect() {
        User onlineUser = createUser(8L, UserStatus.ONLINE, "online-token", Instant.now().minusSeconds(9999));

        when(userRepository.findByStatusNot(UserStatus.OFFLINE)).thenReturn(List.of(onlineUser));
        when(lobbyService.getPlayingLobbyPlayerIdsSnapshot()).thenReturn(Set.of());
        when(userRepository.findById(8L)).thenReturn(Optional.of(onlineUser));
        when(lobbyService.isPlayerTimedOutInPlaying(8L)).thenReturn(false);

        disconnectService.checkIdleUsers();

        verify(lobbyService).handlePermanentDisconnect(8L);
    }

    @Test
    void checkIdleUsers_staleActiveGameUserWithActiveSession_stillTriggersPermanentDisconnect() {
        User activeGameUser = createUser(81L, UserStatus.PLAYING, "playing-token", Instant.now().minusSeconds(9999));

        when(userRepository.findByStatusNot(UserStatus.OFFLINE)).thenReturn(List.of(activeGameUser));
        when(lobbyService.getPlayingLobbyPlayerIdsSnapshot()).thenReturn(Set.of(81L));
        when(userRepository.findById(81L)).thenReturn(Optional.of(activeGameUser));
        when(lobbyService.isPlayerTimedOutInPlaying(81L)).thenReturn(false);

        disconnectService.checkIdleUsers();

        verify(lobbyService).handlePermanentDisconnect(81L);
    }

    @Test
    void handleConnectionLoss_activeGameUser_entersGraceTimer() {
        User activePlayer = createUser(77L, UserStatus.PLAYING, "active-token", Instant.now());

        when(webSocketSessionTracker.hasActiveSession(77L)).thenReturn(false);
        when(userRepository.findById(77L)).thenReturn(Optional.of(activePlayer));
        when(lobbyService.isUserInLobbyContext(77L)).thenReturn(true);
        when(lobbyService.isUserInActiveGame(77L)).thenReturn(true);
        when(lobbyService.findWebsocketGraceSecondsForUser(77L)).thenReturn(300L);

        disconnectService.handleConnectionLoss(77L);

        assertTrue(disconnectService.isPlayerInGracePeriod(77L));
        verify(userRepository, never()).save(activePlayer);
    }

    @Test
    void checkIdleUsers_activeGameUser_prefersLobbyAfkTimeoutBeforeGameLookup() {
        User activeGameUser = createUser(82L, UserStatus.PLAYING, "playing-token", Instant.now().minusSeconds(9999));

        when(userRepository.findByStatusNot(UserStatus.OFFLINE)).thenReturn(List.of(activeGameUser));
        when(lobbyService.getPlayingLobbyPlayerIdsSnapshot()).thenReturn(Set.of(82L));
        when(lobbyService.getPlayingLobbyPlayerAfkTimeoutSecondsSnapshot()).thenReturn(java.util.Map.of(82L, 180L));
        when(userRepository.findById(82L)).thenReturn(Optional.of(activeGameUser));
        when(lobbyService.isPlayerTimedOutInPlaying(82L)).thenReturn(false);

        disconnectService.checkIdleUsers();

        verify(gameService, never()).findActiveGameForUser(82L);
        verify(lobbyService).handlePermanentDisconnect(82L);
    }

    @Test
    void checkAutoLogoutUsers_spectator_isLoggedOut() {
        User spectator = createUser(99L, UserStatus.SPECTATING, AUTO_LOGOUT_TOKEN, Instant.now().minusSeconds(9999));
        stubAutoLogoutCandidates(List.of(spectator));

        disconnectService.checkAutoLogoutUsers();

        assertUserLoggedOutAndTokenRotated(spectator, AUTO_LOGOUT_TOKEN);
        verify(userRepository).save(spectator);
    }

    @Test
    void checkAutoLogoutUsers_onlineUser_isLoggedOut() {
        User onlineUser = createUser(100L, UserStatus.ONLINE, AUTO_LOGOUT_TOKEN, Instant.now().minusSeconds(9999));
        stubAutoLogoutCandidates(List.of(onlineUser));

        disconnectService.checkAutoLogoutUsers();

        assertUserLoggedOutAndTokenRotated(onlineUser, AUTO_LOGOUT_TOKEN);
        verify(userRepository).save(onlineUser);
    }

    @Test
    void checkAutoLogoutUsers_nullCandidate_isIgnored() {
        stubAutoLogoutCandidates(Arrays.asList((User) null));

        disconnectService.checkAutoLogoutUsers();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void handleReconnect_refreshesTrackedSessionsAndClearsTimedOutFlag() {
        disconnectService.handleReconnect(123L);

        verify(webSocketSessionTracker, times(1)).touchSessions(123L);
        verify(lobbyService, times(1)).clearTimedOutPlayingFlag(123L);
    }

    private void stubAutoLogoutCandidates(List<User> candidates) {
        when(userRepository.findByLastHeartbeatBeforeAndStatusNot(any(Instant.class), eq(UserStatus.PLAYING)))
                .thenReturn(candidates);
    }

    private User createUser(Long id, UserStatus status, String token, Instant lastHeartbeat) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        user.setToken(token);
        user.setLastHeartbeat(lastHeartbeat);
        return user;
    }

    private void assertUserLoggedOutAndTokenRotated(User user, String previousToken) {
        assertEquals(UserStatus.OFFLINE, user.getStatus());
        assertNotEquals(previousToken, user.getToken());
    }
}
