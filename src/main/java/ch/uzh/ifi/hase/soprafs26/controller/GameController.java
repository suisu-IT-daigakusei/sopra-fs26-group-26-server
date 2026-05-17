package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.rest.dto.PeekSelectionDTO;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.service.GameService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import org.springframework.web.server.ResponseStatusException;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameStateBroadcastDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;

@RestController
public class GameController {

    private final GameService gameService;
    private final LobbyService lobbyService;

    public GameController(GameService gameService, LobbyService lobbyService) {
        this.gameService = gameService;
        this.lobbyService = lobbyService;
    }

    private int requireIntBodyField(Map<String, ?> body, String field) {
        if (body == null || !body.containsKey(field) || body.get(field) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        Object raw = body.get(field);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be an integer");
        }
    }

    private long requireLongBodyField(Map<String, ?> body, String field) {
        if (body == null || !body.containsKey(field) || body.get(field) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        Object raw = body.get(field);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a number");
        }
    }

    // endpoint according to REST interface
    @PostMapping("/lobbies/{sessionId}/start")
    @ResponseStatus(HttpStatus.OK)
    public Game startGame(  @RequestHeader("Authorization") String token,
                            @PathVariable String sessionId,
                            @RequestBody Map<String, Integer> requestBody) {
        Lobby currentLobby = lobbyService.verifyLobbyCanStart(token, sessionId);
        // retrieve playerIds of players currently in lobby
        List<Long> playerIds = currentLobby.getPlayerIds();
        Game startedGame = gameService.startGame(playerIds, currentLobby);
        lobbyService.markLobbyAsPlaying(sessionId);
        return startedGame;
    }

    @GetMapping("/games/active")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> getActiveGameForCurrentUser(
            @RequestHeader("Authorization") String token) {
        return gameService.getActiveGameForToken(token)
                .map(game -> {
                    Map<String, String> payload = new HashMap<>();
                    payload.put("gameId", game.getId());
                    if (game.getStatus() != null) {
                        payload.put("status", game.getStatus().name());
                    }
                    return payload;
                })
                .orElseGet(Map::of);
    }

    // Backlog #9: Implement logic to always render the DiscardPile top card with its face-up value
    @GetMapping("/games/{gameId}/discard-pile/top")
    @ResponseStatus(HttpStatus.OK)
    public Card getDiscardPileTopCard(@PathVariable String gameId) {
        return gameService.getDiscardPileTopCard(gameId);

    }

    //# 8: Implement a global isMyTurn state that disables all buttons and click listeners on the game board when false.
    @GetMapping("/games/{gameId}/is-my-turn/{userId}")
    @ResponseStatus(HttpStatus.OK)
    public boolean isMyTurn(
            @PathVariable String gameId,
            @PathVariable Long userId) {
        return gameService.isMyTurn(gameId, userId);
    }

    // get the players own hand aka only the visible cards are being shown
    @GetMapping("/games/{gameId}/my-hand")
    @ResponseStatus(HttpStatus.OK)
    public List<Card> getMyHand(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        return gameService.getMyHand(gameId, token);
    }

    // Example empty stubs of move endpoints to demonstrate the interceptor from #30

    @PostMapping("/games/{gameId}/moves/draw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveDrawFromDrawPile(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        gameService.moveDrawFromDrawPile(gameId, token);
    }

    // Implement the click-handler to "pick up" the top card from the discard pile.
    // #25
    @PostMapping("/games/{gameId}/discard-pile/draw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveDrawFromDiscardPile(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        gameService.moveDrawFromDiscardPile(gameId, token);
    }

    // swap drawn card with one of the player's hand cards
    @PostMapping("/games/{gameId}/drawn-card/swap")
    @ResponseStatus(HttpStatus.OK)
    public void moveSwapDrawnCard(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Integer> body) {
        int targetCardIndex = requireIntBodyField(body, "targetCardIndex");
        gameService.moveSwapDrawnCard(gameId, token, targetCardIndex);
    }

    // swap top card of discard pile with one of the player's hand cards
    @PostMapping("/games/{gameId}/discard-pile/swap")
    @ResponseStatus(HttpStatus.OK)
    public void moveSwapWithDiscardPile(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Integer> body) {
        int targetCardIndex = requireIntBodyField(body, "targetCardIndex");
        gameService.moveSwapWithDiscardPile(gameId, token, targetCardIndex);
    }

    // swap cards between two players as special ability
    @PostMapping("/games/{gameId}/abilities/swap")
    @ResponseStatus(HttpStatus.OK)
    public void moveAbilitySwap(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> body) {
        int ownCardIndex = requireIntBodyField(body, "ownCardIndex");
        Long targetUserId = requireLongBodyField(body, "targetUserId");
        int targetCardIndex = requireIntBodyField(body, "targetCardIndex");
        gameService.moveAbilitySwap(gameId, token, ownCardIndex, targetUserId, targetCardIndex);
    }

    // call cabo in order to end the game
    @PostMapping("/games/{gameId}/moves/cabo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveCallCabo(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        gameService.moveCallCabo(gameId, token);
    }

    @PostMapping("/games/{gameId}/rematch/no")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> completeRoundWithoutRematch(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        String waitingSessionId = gameService.completeRoundWithoutRematch(gameId, token);
        return waitingSessionId == null || waitingSessionId.isBlank()
                ? Map.of()
                : Map.of("sessionId", waitingSessionId);
    }

    @PostMapping("/games/{gameId}/rematch/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitRematchDecision(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> body) {
        Object decisionRaw = body == null ? null : body.get("decision");
        if (decisionRaw != null) {
            gameService.submitRematchDecision(gameId, token, String.valueOf(decisionRaw));
            return;
        }

        Object legacyRematchRaw = body == null ? null : body.get("rematch");
        if (legacyRematchRaw instanceof Boolean legacyRematch) {
            gameService.submitRematchDecision(
                    gameId,
                    token,
                    legacyRematch ? GameService.REMATCH_DECISION_CONTINUE : GameService.REMATCH_DECISION_NONE);
            return;
        }

        throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "decision is required (CONTINUE, FRESH, NONE)");
    }

    @GetMapping("/games/{gameId}/post-round-lobby")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> getPostRoundLobby(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        String waitingSessionId = gameService.getPostRoundLobbySessionForToken(gameId, token);
        return waitingSessionId == null || waitingSessionId.isBlank()
                ? Map.of()
                : Map.of("sessionId", waitingSessionId);
    }

    @GetMapping("/games/{gameId}/rematch/config")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Long> getRematchConfig(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        return Map.of("decisionSeconds", gameService.getRematchDecisionSeconds(gameId, token));
    }

    @GetMapping("/games/{gameId}/config")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Long> getGameRuntimeConfig(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        return gameService.getGameRuntimeConfig(gameId, token);
    }

    // discard the drawn card — POST /games/{gameId}/drawn-card/discard
    @PostMapping("/games/{gameId}/drawn-card/discard")
    @ResponseStatus(HttpStatus.OK)
    public Game moveCardToDiscardPile(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        gameService.verifyMoveCallerIsCurrentPlayer(gameId, token);
        gameService.moveCardToDiscardPile(gameId, token);
        return gameService.getGameById(gameId);
    }

    // #47 and #49
    @PostMapping("/games/{gameId}/peek")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void selectPeekCards(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token,
            @RequestBody PeekSelectionDTO body) {
        gameService.applyPeek(gameId, token, body);
    }
    // 20 Drawn card that only reveals value to the active player
    @GetMapping("/games/{gameId}/drawn-card")
    @ResponseStatus(HttpStatus.OK)
    public Card getDrawnCard(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        return gameService.getDrawnCard(gameId, token);
    }

    @PostMapping("/games/{gameId}/abilities/skip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skipAbility(
            @PathVariable String gameId,
            @RequestHeader("Authorization") String token) {
        gameService.skipAbility(gameId, token);
    }

    @PostMapping("/games/resume/{sessionId}")
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    public GameStateBroadcastDTO resumeGame(@PathVariable Long sessionId) {
        Game resumedGame = gameService.resumeGame(sessionId);
        return DTOMapper.INSTANCE.convertEntityToGameStateBroadcastDTO(resumedGame);
    }

}
