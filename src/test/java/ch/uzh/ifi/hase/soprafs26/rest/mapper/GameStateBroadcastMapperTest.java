package ch.uzh.ifi.hase.soprafs26.rest.mapper;

import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.GameMoveEvent;
import ch.uzh.ifi.hase.soprafs26.entity.GameMoveStep;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardViewDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.DiscardTopDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameStateBroadcastDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.PlayerHandViewDTO;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameStateBroadcastMapperTest {

    private GameStateBroadcastMapper mapper;
    private LobbyService lobbyService;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        lobbyService = mock(LobbyService.class);
        userRepository = mock(UserRepository.class);
        when(lobbyService.isPlayerTimedOutInPlaying(anyLong())).thenReturn(false);
        when(lobbyService.resolvePlayingLobbySnapshotForPlayers(anyList()))
                .thenReturn(new LobbyService.PlayingLobbySnapshot(
                        "ABCD1234",
                        Map.of(1L, "navy_blue", 2L, "light_blue"),
                        List.of(99L)));
        when(lobbyService.findPlayingSessionIdForPlayers(anyList())).thenReturn("ABCD1234");
        when(lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(anyList()))
                .thenReturn(Map.of(1L, "navy_blue", 2L, "light_blue"));
        User user1 = new User();
        user1.setId(1L);
        user1.setUsername("user1");
        user1.setProfileCharacterId("char01");
        User user2 = new User();
        user2.setId(2L);
        user2.setUsername("user2");
        user2.setProfileCharacterId("char02");
        when(userRepository.findAllById(anyList())).thenReturn(List.of(user1, user2));
        mapper = new GameStateBroadcastMapper(lobbyService, userRepository);
    }

    @Test
    void buildSharedContext_usesSnapshotDataIncludingSpectators() {
        Game game = new Game();
        game.setOrderedPlayerIds(List.of(1L, 2L));

        GameStateBroadcastMapper.SharedBroadcastContext context = mapper.buildSharedContext(game);

        assertEquals("ABCD1234", context.getSessionId());
        assertEquals(Map.of(1L, "navy_blue", 2L, "light_blue"), context.getAssignedCharacterColorByUserId());
        assertEquals(List.of(99L), context.getSpectatorIds());
    }

    @Test
    void twoViewers_drawCountOnly_opponentHandSecretsHidden() {
        Game game = new Game();
        game.setId("g1");

        List<Card> draw = new ArrayList<>();
        Card secret = new Card();
        secret.setValue(1);
        secret.setCode("C1");
        secret.setVisibility(false);
        draw.add(secret);
        game.setDrawPile(draw);

        Map<Long, List<Card>> hands = new HashMap<>();
        Card h1 = new Card();
        h1.setValue(2);
        h1.setCode("C2");
        h1.setVisibility(false);
        hands.put(1L, List.of(h1));
        Card h2 = new Card();
        h2.setValue(3);
        h2.setCode("C3");
        h2.setVisibility(false);
        hands.put(2L, List.of(h2));
        game.setPlayerHands(hands);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setSessionEnded(true);

        GameStateBroadcastDTO for1 = mapper.toBroadcastForViewer(game, 1L);
        assertEquals("ABCD1234", for1.getSessionId());
        assertTrue(for1.isSessionEnded());
        assertEquals(1, for1.getDrawPileCount());
        // own card - but visibility is false
        assertNull(findPlayerHand(for1, 1L).getCards().get(0).getValue());
        // other player's card
        assertNull(findPlayerHand(for1, 2L).getCards().get(0).getValue());

        GameStateBroadcastDTO for2 = mapper.toBroadcastForViewer(game, 2L);
        assertTrue(for2.isSessionEnded());
        // other player's card
        assertNull(findPlayerHand(for2, 1L).getCards().get(0).getValue());
        // own card - but visibility is false
        assertNull(findPlayerHand(for2, 2L).getCards().get(0).getValue());
    }

    // #47: player 1 sees values only on their two peeked cards; player 2 never sees player 1's values.
    @Test
    void twoVisibleCardsOnPlayer1Hand_player2SeesNoValues() {
        Game game = new Game();
        game.setId("game-peek");

        Map<Long, List<Card>> hands = new HashMap<>();

        List<Card> hand1 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setValue(i);
            c.setCode(i + "-hearts");
            c.setVisibility(i == 0 || i == 2);
            hand1.add(c);
        }
        hands.put(1L, hand1);

        List<Card> hand2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setValue(i);
            c.setCode(i + "-clubs");
            c.setVisibility(false);
            hand2.add(c);
        }
        hands.put(2L, hand2);

        game.setPlayerHands(hands);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        // current player viewing as player 2 must not see player 1's peeked cards during INITIAL_PEEK
        // old mapper leaked when isViewerCurrentPlayer && !isOwner && card.getVisibility()
        game.setCurrentPlayerId(2L);
        game.setStatus(GameStatus.INITIAL_PEEK);

        GameStateBroadcastDTO gameStateForPlayer1 = mapper.toBroadcastForViewer(game, 1L);
        List<CardViewDTO> player1HandPlayer1View = findPlayerHand(gameStateForPlayer1, 1L).getCards();
        assertEquals(0, player1HandPlayer1View.get(0).getValue().intValue());
        assertEquals("0-hearts", player1HandPlayer1View.get(0).getCode());
        assertNull(player1HandPlayer1View.get(1).getValue());
        assertEquals(2, player1HandPlayer1View.get(2).getValue().intValue());
        assertEquals("2-hearts", player1HandPlayer1View.get(2).getCode());
        assertNull(player1HandPlayer1View.get(3).getValue());

        GameStateBroadcastDTO gameStateForPlayer2 = mapper.toBroadcastForViewer(game, 2L);
        List<CardViewDTO> player1HandPlayer2View = findPlayerHand(gameStateForPlayer2, 1L).getCards();
        for (int i = 0; i < 4; i++) {
            assertNull(player1HandPlayer2View.get(i).getValue());
            assertNull(player1HandPlayer2View.get(i).getCode());
        }
    }

    @Test
    void ownerVisibleCard_showsValueAndCode() {
        Game game = new Game();
        game.setId("g2");
        Map<Long, List<Card>> hands = new HashMap<>();
        Card c = new Card();
        c.setValue(1);
        c.setCode("C1");
        c.setVisibility(true);
        hands.put(1L, List.of(c));
        game.setPlayerHands(hands);
        game.setOrderedPlayerIds(List.of(1L, 2L));

        GameStateBroadcastDTO for1 = mapper.toBroadcastForViewer(game, 1L);
        assertEquals(1, findPlayerHand(for1, 1L).getCards().get(0).getValue().intValue());
        assertEquals("C1", findPlayerHand(for1, 1L).getCards().get(0).getCode());

        GameStateBroadcastDTO for2 = mapper.toBroadcastForViewer(game, 2L);
        // as 2nd player, access cards from 1st player (no data displayed)
        assertNull(findPlayerHand(for2, 1L).getCards().get(0).getValue());
        assertNull(findPlayerHand(for2, 1L).getCards().get(0).getCode());
    }

    // 9/10 spy: current player sees peeked opponent card; opponent (hand owner) does not see it face-up
    @Test
    void opponentPeekNineTen_spySeesPeekedCard_ownerDoesNot() {
        Game game = new Game();
        game.setId("g-spy");
        game.setStatus(GameStatus.ABILITY_PEEK_OPPONENT);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));

        List<Card> hand1 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            hand1.add(c);
        }

        List<Card> hand2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setValue(i);
            c.setCode(i + "-clubs");
            c.setVisibility(i == 2);
            hand2.add(c);
        }

        Map<Long, List<Card>> hands = new HashMap<>();
        hands.put(1L, hand1);
        hands.put(2L, hand2);
        game.setPlayerHands(hands);

        GameStateBroadcastDTO asSpy = mapper.toBroadcastForViewer(game, 1L);
        CardViewDTO peekedAsSpy = findPlayerHand(asSpy, 2L).getCards().get(2);
        assertEquals(2, peekedAsSpy.getValue().intValue());
        assertEquals("2-clubs", peekedAsSpy.getCode());

        GameStateBroadcastDTO asOwner = mapper.toBroadcastForViewer(game, 2L);
        CardViewDTO peekedAsOwner = findPlayerHand(asOwner, 2L).getCards().get(2);
        assertNull(peekedAsOwner.getValue());
        assertNull(peekedAsOwner.getCode());
    }

    // #67 discard top is public; card taken into hand is not
    // assumes correct post swap game state and tests broadcast mapper
    // no need to differentiate between 2 service paths (either swap with discard pile OR draw from discard pile, swap with drawn card),
    // because correct game state is assumed
    @Test
    void gameStateAfterSwapWithDiscard_broadcastGameState_discardTopVisible_handCardFromDiscardHiddenForAllViewers() {
        Game game = new Game();
        game.setId("g-swap-broadcast");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Card newDiscardTop = new Card();
        newDiscardTop.setValue(3);
        newDiscardTop.setCode("3C");
        newDiscardTop.setVisibility(true);
        game.setDiscardPile(new ArrayList<>(List.of(newDiscardTop)));

        List<Card> hand1 = new ArrayList<>();
        Card takenFromDiscard = new Card();
        takenFromDiscard.setValue(7);
        takenFromDiscard.setCode("7D");
        takenFromDiscard.setVisibility(false);
        hand1.add(takenFromDiscard);
        for (int i = 0; i < 3; i++) {
            Card c = new Card();
            c.setValue(10 + i);
            c.setCode("h" + i);
            c.setVisibility(false);
            hand1.add(c);
        }

        List<Card> hand2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setVisibility(false);
            hand2.add(c);
        }

        Map<Long, List<Card>> hands = new HashMap<>();
        hands.put(1L, hand1);
        hands.put(2L, hand2);
        game.setPlayerHands(hands);

        for (Long viewerId : List.of(1L, 2L)) {
            GameStateBroadcastDTO dto = mapper.toBroadcastForViewer(game, viewerId);
            DiscardTopDTO top = dto.getDiscardPileTop();
            assertNotNull(top);
            assertEquals(3, top.getValue());
            assertEquals("3C", top.getCode());

            CardViewDTO slot0 = findPlayerHand(dto, 1L).getCards().get(0);
            assertTrue(slot0.isFaceDown());
            assertNull(slot0.getValue());
            assertNull(slot0.getCode());
        }
    }

    // #89: CABO_REVEAL must expose drawn card to all viewers (same as hands), not only current player
    @Test
    void caboReveal_drawnCardRevealedToNonCurrentPlayer() {
        Game game = new Game();
        game.setId("g-89-cabo-drawn");
        game.setStatus(GameStatus.CABO_REVEAL);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Card drawn = new Card();
        drawn.setValue(5);
        drawn.setCode("5S");
        drawn.setVisibility(false);
        game.setDrawnCard(drawn);

        GameStateBroadcastDTO asPlayer2 = mapper.toBroadcastForViewer(game, 2L);
        assertNotNull(asPlayer2.getDrawnCard());
        assertEquals(5, asPlayer2.getDrawnCard().getValue().intValue());
        assertEquals("5S", asPlayer2.getDrawnCard().getCode());
        assertFalse(asPlayer2.getDrawnCard().isFaceDown());
    }

    // #84: game status ROUND_ENDED or ROUND_AWAITING_REMATCH: drawn card revealed to everyone
    @Test
    void roundEndedOrAwaitingRematch_drawnCardRevealedToAllViewers() {
        for (GameStatus status : List.of(GameStatus.ROUND_ENDED, GameStatus.ROUND_AWAITING_REMATCH)) {
            Game game = new Game();
            game.setId("g-84-drawn-" + status.name());
            game.setStatus(status);
            // owns the drawn card
            game.setCurrentPlayerId(2L);
            game.setOrderedPlayerIds(List.of(1L, 2L));

            Card drawn = new Card();
            drawn.setValue(11);
            drawn.setCode("11S");
            game.setDrawnCard(drawn);

            // both players see drawn card
            for (Long viewerId : List.of(1L, 2L)) {
                GameStateBroadcastDTO dto = mapper.toBroadcastForViewer(game, viewerId);
                assertNotNull(dto.getDrawnCard());
                assertEquals(11, dto.getDrawnCard().getValue().intValue());
                assertEquals("11S", dto.getDrawnCard().getCode());
                assertFalse(dto.getDrawnCard().isFaceDown());
            }
        }
    }

    // #84: game status ROUND_ENDED or ROUND_AWAITING_REMATCH: all hand cards revealed to everyone
    @Test
    void roundEndedOrAwaitingRematch_allHandValuesRevealedToOpponentViewer() {
        for (GameStatus status : List.of(GameStatus.ROUND_ENDED, GameStatus.ROUND_AWAITING_REMATCH)) {
            Game game = new Game();
            game.setId("g-84-hands-" + status.name());
            game.setStatus(status);
            game.setCurrentPlayerId(1L);
            game.setOrderedPlayerIds(List.of(1L, 2L));

            Map<Long, List<Card>> hands = new HashMap<>();
            hands.put(1L, List.of(faceDownCard(8, "8H")));
            hands.put(2L, List.of(faceDownCard(12, "QD")));
            game.setPlayerHands(hands);

            GameStateBroadcastDTO asPlayer1 = mapper.toBroadcastForViewer(game, 1L);
            CardViewDTO player2CardViewedByPlayer1 = findPlayerHand(asPlayer1, 2L).getCards().get(0);
            assertEquals(12, player2CardViewedByPlayer1.getValue().intValue());
            assertEquals("QD", player2CardViewedByPlayer1.getCode());
        }
    }

    @Test
    void hiddenHandToHandMove_redactsValuesForActorOpponentAndSpectator() {
        Game game = gameWithMoveEvent(
                moveStep("HAND", 1L, "HAND", 2L, true, 12),
                moveStep("HAND", 2L, "HAND", 1L, true, 13));

        for (Long viewerId : List.of(1L, 2L, 99L)) {
            GameStateBroadcastDTO dto = mapper.toBroadcastForViewer(game, viewerId);
            assertNotNull(dto.getLastMoveEvent());
            assertNull(dto.getLastMoveEvent().getPrimary().getValue());
            assertNull(dto.getLastMoveEvent().getSecondary().getValue());
        }
    }

    @Test
    void hiddenDrawPileToHandMove_revealsValueOnlyToActor() {
        Game game = gameWithMoveEvent(
                moveStep("DRAW_PILE", null, "HAND", 1L, true, 8),
                null);

        GameStateBroadcastDTO actorView = mapper.toBroadcastForViewer(game, 1L);
        assertEquals(8, actorView.getLastMoveEvent().getPrimary().getValue().intValue());

        for (Long viewerId : List.of(2L, 99L)) {
            GameStateBroadcastDTO dto = mapper.toBroadcastForViewer(game, viewerId);
            assertNull(dto.getLastMoveEvent().getPrimary().getValue());
        }
    }

    @Test
    void moveTouchingDiscardPile_keepsFaceUpValuePublic() {
        Game game = gameWithMoveEvent(
                moveStep("HAND", 1L, "DISCARD_PILE", null, true, 6),
                null);

        for (Long viewerId : List.of(1L, 2L, 99L)) {
            GameStateBroadcastDTO dto = mapper.toBroadcastForViewer(game, viewerId);
            assertEquals(6, dto.getLastMoveEvent().getPrimary().getValue().intValue());
        }
    }

    private static Game gameWithMoveEvent(GameMoveStep primary, GameMoveStep secondary) {
        GameMoveEvent event = new GameMoveEvent();
        event.setSequence(7L);
        event.setActorUserId(1L);
        event.setPrimary(primary);
        event.setSecondary(secondary);

        Game game = new Game();
        game.setId("move-event-game");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setPlayerHands(Map.of(1L, List.of(), 2L, List.of()));
        game.setLastMoveEvent(event);
        return game;
    }

    private static GameMoveStep moveStep(
            String sourceZone,
            Long sourceUserId,
            String targetZone,
            Long targetUserId,
            boolean hidden,
            Integer value) {
        GameMoveStep step = new GameMoveStep();
        step.setSourceZone(sourceZone);
        step.setSourceUserId(sourceUserId);
        step.setTargetZone(targetZone);
        step.setTargetUserId(targetUserId);
        step.setHidden(hidden);
        step.setValue(value);
        return step;
    }

    private static Card faceDownCard(int value, String code) {
        Card c = new Card();
        c.setValue(value);
        c.setCode(code);
        c.setVisibility(false);
        return c;
    }

    // helper to get the PlayerHandViewDTO instance from GameStateBroadcastDTO instance based on userId match
    private static PlayerHandViewDTO findPlayerHand(GameStateBroadcastDTO dto, long userId) {
        return dto.getPlayers().stream()
                .filter(p -> p.getUserId() == userId)
                .findFirst()
                .orElseThrow();
    }
}
