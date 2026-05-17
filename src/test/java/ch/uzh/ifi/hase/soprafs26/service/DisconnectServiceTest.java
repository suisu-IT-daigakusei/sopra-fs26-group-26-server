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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.never;
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

    private DisconnectService disconnectService;

    @BeforeEach
    void setUp() {
        TimeoutSettingsProperties timeoutSettings = new TimeoutSettingsProperties();
        disconnectService = new DisconnectService(userRepository, lobbyService, gameService, timeoutSettings);
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
        User spectator = new User();
        spectator.setId(7L);
        spectator.setStatus(UserStatus.SPECTATING);
        spectator.setLastHeartbeat(Instant.now().minusSeconds(9999));

        when(userRepository.findByStatusNot(UserStatus.OFFLINE)).thenReturn(List.of(spectator));
        when(lobbyService.getPlayingLobbyPlayerIdsSnapshot()).thenReturn(Set.of());

        disconnectService.checkIdleUsers();

        verify(lobbyService, never()).handlePermanentDisconnect(7L);
        verify(userRepository, never()).save(spectator);
    }

    @Test
    void checkAutoLogoutUsers_spectator_isLoggedOut() {
        User spectator = new User();
        spectator.setId(99L);
        spectator.setStatus(UserStatus.SPECTATING);
        spectator.setToken("keep-token");

        when(userRepository.findByLastHeartbeatBeforeAndStatusNot(
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.eq(UserStatus.PLAYING)
        )).thenReturn(List.of(spectator));

        disconnectService.checkAutoLogoutUsers();

        assertEquals(UserStatus.OFFLINE, spectator.getStatus());
        assertNotEquals("keep-token", spectator.getToken());
        verify(userRepository).save(spectator);
    }
}
