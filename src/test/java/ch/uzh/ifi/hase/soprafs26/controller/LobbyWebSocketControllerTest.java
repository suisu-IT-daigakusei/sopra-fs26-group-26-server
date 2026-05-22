package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyWebSocketControllerTest {

    @Test
    void getLobbyState_returnsLobbyFromService() {
        LobbyService lobbyService = mock(LobbyService.class);
        LobbyWebSocketController controller = new LobbyWebSocketController(lobbyService);
        Lobby lobby = new Lobby();
        lobby.setSessionId("ABC12345");

        when(lobbyService.getLobbyById(9L)).thenReturn(lobby);

        Lobby result = controller.getLobbyState(9L);

        assertSame(lobby, result);
        verify(lobbyService).getLobbyById(9L);
    }
}

