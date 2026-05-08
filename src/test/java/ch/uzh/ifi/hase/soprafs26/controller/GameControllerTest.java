package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.config.GameMoveAuthorizationInterceptor;
import ch.uzh.ifi.hase.soprafs26.config.GameMoveWebConfig;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.service.GameService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.is;

import java.util.List;

@WebMvcTest(GameController.class)
@Import({ GameMoveWebConfig.class, GameMoveAuthorizationInterceptor.class })
public class GameControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private GameService gameService;

        @MockitoBean
        private LobbyService lobbyService;

        @Test
        void postMoveDraw_interceptorAllows_returns204() throws Exception {
                String gameId = "id1";

                doNothing().when(gameService).verifyMoveCallerIsCurrentPlayer(anyString(), anyString());

                mockMvc.perform(post("/games/{gameId}/moves/draw", gameId)
                                .header("Authorization", "token"))
                                .andExpect(status().isNoContent());

                verify(gameService).verifyMoveCallerIsCurrentPlayer(eq(gameId), eq("token"));
                verify(gameService).moveDrawFromDrawPile(eq(gameId), eq("token"));
        }

        @Test
        void postMoveCabo_interceptorForbidden_doesNotCallMoveHandler() throws Exception {
                String gameId = "id1";

                doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your turn"))
                                .when(gameService).verifyMoveCallerIsCurrentPlayer(anyString(), anyString());

                mockMvc.perform(post("/games/{gameId}/moves/cabo", gameId)
                                .header("Authorization", "token"))
                                .andExpect(status().isForbidden());

                verify(gameService, never()).moveCallCabo(anyString(), anyString());
        }

        @Test
        void postDiscardPileDraw_callsService_returns204() throws Exception {
                String gameId = "g1";
                doNothing().when(gameService).moveDrawFromDiscardPile(anyString(), anyString());

                mockMvc.perform(post("/games/{gameId}/discard-pile/draw", gameId)
                                .header("Authorization", "token"))
                                .andExpect(status().isNoContent());

                verify(gameService).moveDrawFromDiscardPile(eq(gameId), eq("token"));
        }

        @Test
        void postDiscardPileSwap_callsService_returns200() throws Exception {
                String gameId = "g1";
                doNothing().when(gameService).moveSwapWithDiscardPile(anyString(), anyString(), eq(2));

                mockMvc.perform(post("/games/{gameId}/discard-pile/swap", gameId)
                                .header("Authorization", "token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetCardIndex\":2}"))
                                .andExpect(status().isOk());

                verify(gameService).moveSwapWithDiscardPile(eq(gameId), eq("token"), eq(2));
        }

        @Test
        void postDiscardPileDraw_thenDrawnCardSwap_clientSequence_callsBothHandlersInOrder() throws Exception {
                String gameId = "g1";
                doNothing().when(gameService).moveDrawFromDiscardPile(anyString(), anyString());
                doNothing().when(gameService).moveSwapDrawnCard(anyString(), anyString(), eq(0));

                mockMvc.perform(post("/games/{gameId}/discard-pile/draw", gameId)
                                .header("Authorization", "token"))
                                .andExpect(status().isNoContent());

                mockMvc.perform(post("/games/{gameId}/drawn-card/swap", gameId)
                                .header("Authorization", "token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetCardIndex\":0}"))
                                .andExpect(status().isOk());

                // verify that the API calls happened in this order
                var order = inOrder(gameService);
                order.verify(gameService).moveDrawFromDiscardPile(eq(gameId), eq("token"));
                order.verify(gameService).moveSwapDrawnCard(eq(gameId), eq("token"), eq(0));
        }

        @Test
        public void postStartGame_validRequest_startsGameAndReturns200() throws Exception {

                String sessionId = "ABCD12EF";
                String token = "valid-token";

                Lobby mockLobby = mock(Lobby.class);
                when(mockLobby.getPlayerIds()).thenReturn(List.of(1L, 2L, 3L));

                Game game = new Game();
                game.setId("game-123");

                when(lobbyService.verifyLobbyCanStart(anyString(), anyString())).thenReturn(mockLobby);
                when(gameService.startGame(anyList(), any(Lobby.class))).thenReturn(game);
                doNothing().when(lobbyService).markLobbyAsPlaying(anyString());

                when(gameService.getGameById(anyString())).thenReturn(game);
                doNothing().when(gameService).verifyMoveCallerIsCurrentPlayer(anyString(), anyString());

                mockMvc.perform(post("/lobbies/{sessionId}/start", sessionId)
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id", is("game-123")));

                var order = inOrder(lobbyService, gameService);
                order.verify(lobbyService).verifyLobbyCanStart(eq(token), eq(sessionId));
                order.verify(gameService).startGame(anyList(), any(Lobby.class));
                order.verify(lobbyService).markLobbyAsPlaying(eq(sessionId));
        }

        @Test
        public void putCallCabo_validRequest_returns200() throws Exception {
                String gameId = "test-game";
                String token = "valid-token";

                doNothing().when(gameService).moveCallCabo(gameId, token);

                mockMvc.perform(post("/games/{gameId}/moves/cabo", gameId)
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isNoContent()); 
                
                verify(gameService, times(1)).moveCallCabo(eq(gameId), eq(token));
        }
}
