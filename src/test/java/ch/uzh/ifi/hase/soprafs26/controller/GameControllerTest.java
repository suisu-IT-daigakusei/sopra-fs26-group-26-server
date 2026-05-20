package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.config.GameMoveAuthorizationInterceptor;
import ch.uzh.ifi.hase.soprafs26.config.GameMoveWebConfig;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.rest.dto.PeekSelectionDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameSyncStateDTO;
import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.service.GameService;
import ch.uzh.ifi.hase.soprafs26.service.HotEndpointRateLimitException;
import ch.uzh.ifi.hase.soprafs26.service.HotEndpointRateLimiter;
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
import org.mockito.Mockito;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.Optional;

@WebMvcTest(GameController.class)
@Import({ GameMoveWebConfig.class, GameMoveAuthorizationInterceptor.class })
public class GameControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private GameService gameService;

        @MockitoBean
        private LobbyService lobbyService;

        @MockitoBean
        private HotEndpointRateLimiter hotEndpointRateLimiter;

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

        @Test
    void getActiveGameForCurrentUser_gameFoundWithStatus_returnsGameIdAndStatus() throws Exception {
        // 1. Setup
        Game game = new Game();
        game.setId("game-123");
        game.setStatus(GameStatus.ROUND_ACTIVE); 
        
        Mockito.when(gameService.getActiveGameForToken("valid-token")).thenReturn(Optional.of(game));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/active")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.gameId", is("game-123")))
               .andExpect(jsonPath("$.status", is("ROUND_ACTIVE")));
    }

    @Test
    void getActiveGameForCurrentUser_gameFoundNullStatus_returnsOnlyGameId() throws Exception {
        // 1. Setup
        Game game = new Game();
        game.setId("game-123");
        game.setStatus(null); // Explicitly null to hit the if-statement bypass
        
        Mockito.when(gameService.getActiveGameForToken("valid-token")).thenReturn(Optional.of(game));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/active")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.gameId", is("game-123")))
               .andExpect(jsonPath("$.status").doesNotExist()); // Proves the status key wasn't added
    }

    @Test
    void getActiveGameForCurrentUser_noActiveGame_returnsEmptyMap() throws Exception {
        // 1. Setup
        Mockito.when(gameService.getActiveGameForToken("valid-token")).thenReturn(Optional.empty());

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/active")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()", is(0))); // Proves the returned JSON object {} is empty
    }

    @Test
    void getActiveGameForCurrentUser_invalidToken_returnsUnauthorized() throws Exception {
        // 1. Setup
        Mockito.when(gameService.getActiveGameForToken("bad-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/active")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // Expect a 401 response
    }

    @Test
    void getDiscardPileTopCard_cardExists_returnsCard() throws Exception {
        // 1. Setup: Create a fake card to return
        Card mockCard = new Card();
        mockCard.setCode("AS"); // Ace of Spades
        mockCard.setVisibility(true);
        mockCard.setValue(1);
        
        Mockito.when(gameService.getDiscardPileTopCard("game-123")).thenReturn(mockCard);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/game-123/discard-pile/top")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.code", is("AS")))
               .andExpect(jsonPath("$.visibility", is(true)))
               .andExpect(jsonPath("$.value", is(1)));
    }

    @Test
    void getDiscardPileTopCard_pileEmpty_returnsEmptyBody() throws Exception {
        // 1. Setup: The service returns null when the pile is empty
        Mockito.when(gameService.getDiscardPileTopCard("game-123")).thenReturn(null);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/game-123/discard-pile/top")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").doesNotExist()); // Proves the JSON body is completely empty
    }

    @Test
    void getDiscardPileTopCard_gameNotFound_returnsNotFound() throws Exception {
        // 1. Setup: The service throws a 404 exception (just like we tested in the service layer!)
        Mockito.when(gameService.getDiscardPileTopCard("invalid-game"))
               .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/invalid-game/discard-pile/top")
                .header("Authorization", "valid-token"))
               .andExpect(status().isNotFound()); // Proves the controller correctly passes the 404 to the frontend
    }

    @Test
    void getTurnOwner_validRequest_returnsCurrentTurnUserId() throws Exception {
        Mockito.when(gameService.getCurrentTurnOwnerForToken("game-123", "valid-token"))
                .thenReturn(2L);

        mockMvc.perform(get("/games/game-123/turn-owner")
                .header("Authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentTurnUserId", is(2)));
    }

    @Test
    void getSyncState_validRequest_returnsCompactPayload() throws Exception {
        Card discardTop = new Card();
        discardTop.setCode("8H");
        discardTop.setValue(8);
        discardTop.setVisibility(true);

        Card drawnCard = new Card();
        drawnCard.setCode("QS");
        drawnCard.setValue(12);
        drawnCard.setVisibility(true);

        Card handCard = new Card();
        handCard.setCode("3D");
        handCard.setValue(3);
        handCard.setVisibility(false);

        GameSyncStateDTO dto = new GameSyncStateDTO();
        dto.setStatus("ROUND_ACTIVE");
        dto.setCurrentTurnUserId(3L);
        dto.setDiscardTop(discardTop);
        dto.setDrawnCard(drawnCard);
        dto.setMyHand(List.of(handCard));
        dto.setRematchDecisionSeconds(60L);

        Mockito.when(gameService.getSyncStateSnapshotForToken("game-123", "valid-token"))
                .thenReturn(new GameService.SyncStateSnapshot(dto, "\"etag-sync-1\""));

        mockMvc.perform(get("/games/game-123/sync-state")
                .header("Authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ROUND_ACTIVE")))
                .andExpect(jsonPath("$.currentTurnUserId", is(3)))
                .andExpect(jsonPath("$.discardTop.code", is("8H")))
                .andExpect(jsonPath("$.drawnCard.code", is("QS")))
                .andExpect(jsonPath("$.myHand[0].code", is("3D")))
                .andExpect(jsonPath("$.rematchDecisionSeconds", is(60)));
    }

    @Test
    void getSyncState_matchingIfNoneMatch_returnsNotModified() throws Exception {
        GameSyncStateDTO dto = new GameSyncStateDTO();
        dto.setStatus("ROUND_ACTIVE");
        Mockito.when(gameService.getSyncStateSnapshotForToken("game-123", "valid-token"))
                .thenReturn(new GameService.SyncStateSnapshot(dto, "\"etag-sync-2\""));

        mockMvc.perform(get("/games/game-123/sync-state")
                .header("Authorization", "valid-token")
                .header("If-None-Match", "\"etag-sync-2\""))
                .andExpect(status().isNotModified());
    }

    @Test
    void getTurnOwner_nullCurrentTurn_returnsEmptyMap() throws Exception {
        Mockito.when(gameService.getCurrentTurnOwnerForToken("game-123", "valid-token"))
                .thenReturn(null);

        mockMvc.perform(get("/games/game-123/turn-owner")
                .header("Authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void getTurnOwner_invalidToken_returnsUnauthorized() throws Exception {
        Mockito.when(gameService.getCurrentTurnOwnerForToken("game-123", "bad-token"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        mockMvc.perform(get("/games/game-123/turn-owner")
                .header("Authorization", "bad-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTurnOwner_rateLimited_returnsRetryAfterHeader() throws Exception {
        Mockito.doThrow(new HotEndpointRateLimitException("Too many sync requests; retry shortly", 2L))
                .when(hotEndpointRateLimiter)
                .enforceHotReadLimit(eq("turn-owner"), eq("valid-token"), any(), any());

        mockMvc.perform(get("/games/game-123/turn-owner")
                .header("Authorization", "valid-token"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", is("Too Many Requests")))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.message", is("Too many sync requests; retry shortly")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "2"));
    }

    @Test
    void moveAbilitySwap_validRequest_returnsOk() throws Exception {
        // 1. Setup: A perfect JSON body
        String validJsonBody = """
                {
                    "ownCardIndex": 0,
                    "targetUserId": 2,
                    "targetCardIndex": 1
                }
                """;

        // gameService.moveAbilitySwap returns void, so Mockito will naturally do nothing (which is success)

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/abilities/swap")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validJsonBody))
               .andExpect(status().isOk());
               
        // Verify the service was called with the exact parsed integers and longs!
        Mockito.verify(gameService, Mockito.times(1))
               .moveAbilitySwap("game-123", "valid-token", 0, 2L, 1);
    }

    @Test
    void moveAbilitySwap_missingField_returnsBadRequest() throws Exception {
        // 1. Setup: Missing the 'ownCardIndex' field completely
        String missingFieldJson = """
                {
                    "targetUserId": 2,
                    "targetCardIndex": 1
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/abilities/swap")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingFieldJson))
               .andExpect(status().isBadRequest()); 
               // Expect 400 Bad Request because requireIntBodyField will throw an exception
    }

    @Test
    void moveAbilitySwap_invalidDataType_returnsBadRequest() throws Exception {
        // 1. Setup: targetUserId is a word instead of a number
        String invalidTypeJson = """
                {
                    "ownCardIndex": 0,
                    "targetUserId": "not-a-number", 
                    "targetCardIndex": 1
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/abilities/swap")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidTypeJson))
               .andExpect(status().isBadRequest());
               // Expect 400 Bad Request because requireLongBodyField will fail to parse "not-a-number"
    }

    @Test
    void completeRoundWithoutRematch_returnsValidSessionId_returnsMapWithId() throws Exception {
        // 1. Setup: Service returns a valid session ID
        Mockito.when(gameService.completeRoundWithoutRematch("game-123", "valid-token"))
               .thenReturn("session-999");

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/rematch/no")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.sessionId", is("session-999"))); // Map.of("sessionId", "session-999")
    }

    @Test
    void completeRoundWithoutRematch_returnsNull_returnsEmptyMap() throws Exception {
        // 1. Setup: Service returns null
        Mockito.when(gameService.completeRoundWithoutRematch("game-123", "valid-token"))
               .thenReturn(null);

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/rematch/no")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()", is(0))); // Map.of() produces an empty JSON object {}
    }

    @Test
    void completeRoundWithoutRematch_returnsBlankString_returnsEmptyMap() throws Exception {
        // 1. Setup: Service returns a string that is just spaces
        Mockito.when(gameService.completeRoundWithoutRematch("game-123", "valid-token"))
               .thenReturn("   ");

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/rematch/no")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()", is(0))); // isBlank() catches this and returns Map.of()
    }

    @Test
    void completeRoundWithoutRematch_invalidToken_returnsUnauthorized() throws Exception {
        // 1. Setup: Service rejects the token
        Mockito.when(gameService.completeRoundWithoutRematch("game-123", "bad-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/rematch/no")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void submitRematchDecision_validDecisionProvided_returnsNoContent() throws Exception {
        // 1. Setup
        String jsonBody = """
                {
                    "decision": "FRESH"
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/rematch/decision")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isNoContent()); // 204 No Content

        // Verify the modern decision was passed exactly as-is to the service
        Mockito.verify(gameService, Mockito.times(1))
               .submitRematchDecision("game-123", "valid-token", "FRESH");
    }

    @Test
    void submitRematchDecision_legacyRematchTrue_translatesToContinue() throws Exception {
        // 1. Setup
        String jsonBody = """
                {
                    "rematch": true
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/rematch/decision")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isNoContent());

        // Verify the boolean `true` was translated to GameService.REMATCH_DECISION_CONTINUE
        Mockito.verify(gameService, Mockito.times(1))
               .submitRematchDecision("game-123", "valid-token", GameService.REMATCH_DECISION_CONTINUE);
    }

    @Test
    void submitRematchDecision_legacyRematchFalse_translatesToNone() throws Exception {
        // 1. Setup
        String jsonBody = """
                {
                    "rematch": false
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/rematch/decision")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isNoContent());

        // Verify the boolean `false` was translated to GameService.REMATCH_DECISION_NONE
        Mockito.verify(gameService, Mockito.times(1))
               .submitRematchDecision("game-123", "valid-token", GameService.REMATCH_DECISION_NONE);
    }

    @Test
    void submitRematchDecision_missingFields_returnsBadRequest() throws Exception {
        // 1. Setup: An empty JSON object {}
        String emptyJsonBody = "{}";

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/rematch/decision")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyJsonBody))
               .andExpect(status().isBadRequest()); // 400 Bad Request
               
        // Verify the service was NEVER called because the controller rejected it
        Mockito.verify(gameService, Mockito.never())
               .submitRematchDecision(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void getPostRoundLobby_returnsValidSessionId_returnsMapWithId() throws Exception {
        // 1. Setup
        Mockito.when(gameService.getPostRoundLobbySessionForToken("game-123", "valid-token"))
               .thenReturn("session-999");

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/game-123/post-round-lobby")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.sessionId", is("session-999")));
    }

    @Test
    void getPostRoundLobby_returnsNull_returnsEmptyMap() throws Exception {
        // 1. Setup
        Mockito.when(gameService.getPostRoundLobbySessionForToken("game-123", "valid-token"))
               .thenReturn(null);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/game-123/post-round-lobby")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void getPostRoundLobby_returnsBlankString_returnsEmptyMap() throws Exception {
        // 1. Setup
        Mockito.when(gameService.getPostRoundLobbySessionForToken("game-123", "valid-token"))
               .thenReturn("   ");

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/game-123/post-round-lobby")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void getPostRoundLobby_invalidToken_returnsUnauthorized() throws Exception {
        // 1. Setup
        Mockito.when(gameService.getPostRoundLobbySessionForToken("game-123", "bad-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/game-123/post-round-lobby")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void getRematchConfig_invalidToken_returnsUnauthorized() throws Exception {
        Mockito.when(gameService.getRematchDecisionSeconds("game-123", "bad-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        mockMvc.perform(get("/games/game-123/rematch/config")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void getRematchConfig_validRequest_returnsDecisionSeconds() throws Exception {
        // 1. Setup: Service returns 15 seconds
        Mockito.when(gameService.getRematchDecisionSeconds("game-123", "valid-token"))
               .thenReturn(15L);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/game-123/rematch/config")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.decisionSeconds", is(15))); // The exact key and the exact value!
    }

    @Test
    void moveCardToDiscardPile_validRequest_returnsUpdatedGame() throws Exception {
        // 1. Setup
        Game mockGame = new Game();
        mockGame.setId("game-123");
        // We only need to mock getGameById, because the other two void methods will succeed by doing nothing!
        Mockito.when(gameService.getGameById("game-123")).thenReturn(mockGame);

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/drawn-card/discard")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id", is("game-123")));

        // Verify that the two void methods were actually called in the correct order
        Mockito.verify(gameService, Mockito.times(1)).verifyMoveCallerIsCurrentPlayer("game-123", "valid-token");
        Mockito.verify(gameService, Mockito.times(1)).moveCardToDiscardPile("game-123", "valid-token");
    }

    @Test
    void moveCardToDiscardPile_notCurrentPlayer_throwsForbidden() throws Exception {
        // 1. Setup: Use doThrow() for the void method to simulate a security block
        Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your turn"))
               .when(gameService).verifyMoveCallerIsCurrentPlayer("game-123", "bad-token");

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/drawn-card/discard")
                .header("Authorization", "bad-token"))
               .andExpect(status().isForbidden());

        // Verify that because the first check failed, the actual move logic was NEVER executed
        Mockito.verify(gameService, Mockito.never()).moveCardToDiscardPile(Mockito.anyString(), Mockito.anyString());
        Mockito.verify(gameService, Mockito.never()).getGameById(Mockito.anyString());
    }

    @Test
    void moveCardToDiscardPile_gameNotFound_throwsNotFound() throws Exception {
        // 1. Setup: Simulate the game missing when it tries to return it at the very end
        Mockito.when(gameService.getGameById("invalid-game"))
               .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found"));

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/invalid-game/drawn-card/discard")
                .header("Authorization", "valid-token"))
               .andExpect(status().isNotFound());
    }

    @Test
    void selectPeekCards_validRequest_returnsNoContent() throws Exception {
        // 1. Setup: A basic JSON payload (even an empty object works to prove mapping succeeds)
        String jsonBody = """
                {
                    "targetUserId": 2,
                    "targetCardIndex": 1
                }
                """;

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/peek")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isNoContent()); // 204 No Content

        // Verify that the service was called exactly once with the correct gameId and token.
        // We use Mockito.any() for the DTO because Spring creates a brand new instance of it during the request.
        Mockito.verify(gameService, Mockito.times(1))
               .applyPeek(Mockito.eq("game-123"), Mockito.eq("valid-token"), Mockito.any(PeekSelectionDTO.class));
    }

    @Test
    void selectPeekCards_serviceRejectsPeek_returnsForbidden() throws Exception {
        // 1. Setup: Simulate the service blocking the peek (e.g., user already peeked, or wrong phase)
        Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot peek right now"))
               .when(gameService).applyPeek(Mockito.eq("game-123"), Mockito.eq("invalid-token"), Mockito.any(PeekSelectionDTO.class));

        String jsonBody = "{}";

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/peek")
                .header("Authorization", "invalid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
               .andExpect(status().isForbidden()); // 403 Forbidden
    }
    
    @Test
    void selectPeekCards_missingBody_returnsBadRequest() throws Exception {
        // 1. Setup: Sending the request without any body at all
        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/peek")
                .header("Authorization", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)) // Notice: No .content(...) !
               .andExpect(status().isBadRequest()); // Spring automatically returns 400 when a @RequestBody is missing

        // Verify the service was never touched because Spring blocked it at the controller level
        Mockito.verify(gameService, Mockito.never())
               .applyPeek(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void getDrawnCard_validRequest_returnsDrawnCard() throws Exception {
        // 1. Setup: Create the mock card the service will return
        Card mockCard = new Card();
        mockCard.setCode("KH"); // King of Hearts
        mockCard.setValue(13);
        mockCard.setVisibility(true);

        Mockito.when(gameService.getDrawnCard("game-123", "valid-token"))
               .thenReturn(mockCard);

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/game-123/drawn-card")
                .header("Authorization", "valid-token"))
               .andExpect(status().isOk()) // 200 OK
               .andExpect(jsonPath("$.code", is("KH")))
               .andExpect(jsonPath("$.value", is(13)))
               .andExpect(jsonPath("$.visibility", is(true)));
    }

    @Test
    void getDrawnCard_invalidToken_returnsUnauthorized() throws Exception {
        // 1. Setup: Simulate the service rejecting the token
        Mockito.when(gameService.getDrawnCard("game-123", "bad-token"))
               .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        // 2. Action & 3. Assertion
        mockMvc.perform(get("/games/game-123/drawn-card")
                .header("Authorization", "bad-token"))
               .andExpect(status().isUnauthorized()); // 401 Unauthorized
    }

    @Test
    void skipAbility_validRequest_returnsNoContent() throws Exception {
        // 1. Setup
        // We don't need to mock anything for gameService.skipAbility because void mock methods 
        // automatically do nothing (which means success) by default!

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/abilities/skip")
                .header("Authorization", "valid-token"))
               .andExpect(status().isNoContent()); // 204 No Content

        // Verify the service was called with the exact parameters from the path and header
        Mockito.verify(gameService, Mockito.times(1)).skipAbility("game-123", "valid-token");
    }

    @Test
    void skipAbility_notAllowed_throwsForbidden() throws Exception {
        // 1. Setup: Simulate the service rejecting the action
        Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to skip right now"))
               .when(gameService).skipAbility("game-123", "bad-token");

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/game-123/abilities/skip")
                .header("Authorization", "bad-token"))
               .andExpect(status().isForbidden()); // 403 Forbidden
    }

    @Test
    void resumeGame_sessionNotFound_throwsNotFound() throws Exception {
        // 1. Setup: The service cannot find the session ID in the database
        Mockito.when(gameService.resumeGame(99L))
               .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/resume/99")
                .contentType(MediaType.APPLICATION_JSON))
               .andExpect(status().isNotFound()); // Expecting 404 NOT FOUND
    }

    @Test
    void resumeGame_sessionInvalidOrEnded_throwsBadRequest() throws Exception {
        // 1. Setup: The session exists, but no players are in it or it already ended
        Mockito.when(gameService.resumeGame(99L))
               .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session already finished"));

        // 2. Action & 3. Assertion
        mockMvc.perform(post("/games/resume/99")
                .contentType(MediaType.APPLICATION_JSON))
               .andExpect(status().isBadRequest()); // Expecting 400 BAD REQUEST
    }

}
