package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.config.settings.GameSettingsProperties;
import ch.uzh.ifi.hase.soprafs26.config.settings.LobbySettingsProperties;
import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbySettingsPatchDTO;
import ch.uzh.ifi.hase.soprafs26.repository.GameRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;

// These are the publishers your Service uses to send WebSocket messages
import ch.uzh.ifi.hase.soprafs26.service.GameEventPublisher;
import ch.uzh.ifi.hase.soprafs26.service.LobbyEventPublisher;
import ch.uzh.ifi.hase.soprafs26.service.OnlineUsersEventPublisher;
import ch.uzh.ifi.hase.soprafs26.service.DisconnectService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class WebSocketBroadcastTest {

    @Mock
    private GameEventPublisher gameEventPublisher;

    @Mock
    private LobbyEventPublisher lobbyEventPublisher;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private LobbyRepository lobbyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private GameSettingsProperties gameSettings;

    @Mock
    private LobbySettingsProperties lobbySettings;

    @Mock
    private OnlineUsersEventPublisher onlineUsersEventPublisher;

    @Mock
    private DisconnectService disconnectService;

    @Mock
    private LobbyChatService lobbyChatService;

    @InjectMocks
    private GameService gameService;

    @InjectMocks
    private LobbyService lobbyService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Mockito.when(gameSettings.getMinPlayers()).thenReturn(2);
        Mockito.when(gameSettings.getMaxPlayers()).thenReturn(4);
        Mockito.when(gameSettings.getStarterCardsPerPlayer()).thenReturn(4);
        Mockito.when(gameSettings.getInitialPeekSeconds()).thenReturn(10L);
        Mockito.when(gameSettings.getTurnSeconds()).thenReturn(30L);
        Mockito.when(gameSettings.getAbilitySeconds()).thenReturn(30L);
        Mockito.when(gameSettings.getPostPeekAutoEndSeconds()).thenReturn(5L);
        Mockito.when(gameSettings.getAbilitySwapSeconds()).thenReturn(10L);
        Mockito.when(gameSettings.getCaboRevealSeconds()).thenReturn(30L);
        Mockito.when(gameSettings.getRematchDecisionSeconds()).thenReturn(60L);
        Mockito.when(lobbySettings.getAfkTimeoutMinSeconds()).thenReturn(180L);
        Mockito.when(lobbySettings.getAfkTimeoutMaxSeconds()).thenReturn(1200L);
        Mockito.when(lobbySettings.getInitialPeekMinSeconds()).thenReturn(3L);
        Mockito.when(lobbySettings.getInitialPeekMaxSeconds()).thenReturn(60L);
        Mockito.when(lobbySettings.getTurnMinSeconds()).thenReturn(10L);
        Mockito.when(lobbySettings.getTurnMaxSeconds()).thenReturn(60L);
        Mockito.when(lobbySettings.getAbilityRevealMinSeconds()).thenReturn(3L);
        Mockito.when(lobbySettings.getAbilityRevealMaxSeconds()).thenReturn(10L);
        Mockito.when(lobbySettings.getAbilitySwapMinSeconds()).thenReturn(5L);
        Mockito.when(lobbySettings.getAbilitySwapMaxSeconds()).thenReturn(30L);
        Mockito.when(lobbySettings.getAbilitySwapDefaultSeconds()).thenReturn(10L);
        Mockito.when(lobbySettings.getWebsocketGraceMinSeconds()).thenReturn(180L);
        Mockito.when(lobbySettings.getWebsocketGraceMaxSeconds()).thenReturn(600L);
        Mockito.when(lobbySettings.getChatCooldownDefaultSeconds()).thenReturn(3L);
        Mockito.when(lobbySettings.getChatCooldownMinSeconds()).thenReturn(1L);
        Mockito.when(lobbySettings.getChatCooldownMaxSeconds()).thenReturn(60L);
    }

    @Test
    void task16_gameUpdate_shouldPublishFilteredState() {
        // Setup: Game needs a state and at least one card to avoid IndexOutOfBounds
        Game game = new Game();
        game.setId("game-123");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);

        User user = new User();
        user.setId(1L);
        user.setToken("testToken");
        
        List<Card> drawPile = new ArrayList<>();
        drawPile.add(new Card()); 
        game.setDrawPile(drawPile);

        Mockito.when(gameRepository.findById("game-123")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any())).thenReturn(game);
        Mockito.when(userRepository.findByToken("testToken")).thenReturn(user);

        // Action: This triggers the internal call to gameEventPublisher.publishFilteredState(saved)
        gameService.moveDrawFromDrawPile("game-123", "testToken");

        // Assertion: Verify Task #16
        verify(gameEventPublisher, times(1)).publishFilteredState(any(Game.class));
    }

    @Test
    void task40_lobbyUpdate_shouldBroadcastLobbyUpdate() {
        String token = "host-token";
        String sessionId = "lobby-ABC";
        Long hostId = 1L;

        // Setup User as the Host
        User host = new User();
        host.setId(hostId);
        host.setToken(token);
        Mockito.when(userRepository.findByToken(token)).thenReturn(host);

        // Setup Lobby in WAITING status
        Lobby lobby = new Lobby();
        lobby.setId(99L);
        lobby.setSessionId(sessionId);
        lobby.setSessionHostUserId(hostId);
        lobby.setStatus("WAITING");
        
        Mockito.when(lobbyRepository.findBySessionIdForUpdate(sessionId)).thenReturn(Optional.of(lobby));
        Mockito.when(lobbyRepository.save(any())).thenReturn(lobby);

        LobbySettingsPatchDTO settingsDTO = new LobbySettingsPatchDTO();
        settingsDTO.setIsPublic(false);

        // Action: This triggers lobbyEventPublisher.broadcastLobbyUpdate(lobby.getId(), lobby)
        lobbyService.updateLobbySettings(token, sessionId, settingsDTO);

        // Assertion: Verify Task #40
        verify(lobbyEventPublisher, times(1))
                .broadcastLobbyUpdate(eq(99L), any(Lobby.class));
    }
}
