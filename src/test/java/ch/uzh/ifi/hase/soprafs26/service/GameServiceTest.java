package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.config.settings.GameSettingsProperties;
import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.GameRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardViewDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameStateBroadcastDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.PeekSelectionDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.GameStateBroadcastMapper;
import ch.uzh.ifi.hase.soprafs26.util.PeekType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class GameServiceTest {

    private static final String GAME_ID = "id1";

    @Mock
    private GameRepository gameRepository;
    
    @Mock
    private UserRepository userRepository;

    @Mock
    private DeckOfCardsAPIService deckOfCardsAPIService;

    @Mock
    private GameEventPublisher gameEventPublisher;

    @Mock
    private ScheduledExecutorService scheduler;

    @Mock
    private GameSettingsProperties gameSettings;

    @Mock
    private LobbyService lobbyService;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private LobbyChatService lobbyChatService;

    @InjectMocks
    private GameService gameService;

    private int scheduleCallCount;

    @BeforeEach
    void setup() {
        scheduleCallCount = 0;
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
        Mockito.when(lobbyService.isPlayerTimedOutInPlaying(Mockito.anyLong())).thenReturn(false);
        // mock timer without waiting for it
        Mockito.when(scheduler.schedule(Mockito.any(Runnable.class), Mockito.anyLong(), Mockito.any(TimeUnit.class)))
                .thenAnswer(invocation -> Mockito.mock(ScheduledFuture.class));
    }

    @Test
    void verify_wrongPlayer_throwsForbidden() {
        User wrongUser = new User();
        wrongUser.setId(2L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(wrongUser);

        Game game = new Game();
        game.setCurrentPlayerId(1L);
        Mockito.when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.verifyMoveCallerIsCurrentPlayer(GAME_ID, "token"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("Not your turn", ex.getReason());
    }

    @Test
    void applyInitialPeek_validTwoIndices_revealsOnlyThoseMarksPeekDoneAndPublishes() {
        User player = new User();
        player.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(player);

        List<Card> hand1 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setVisibility(false);
            c.setValue(i);
            hand1.add(c);
        }

        List<Card> hand2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setVisibility(false);
            c.setValue(i);
            hand2.add(c);
        }

        Map<Long, List<Card>> hands = new HashMap<>();
        hands.put(1L, hand1);
        hands.put(2L, hand2);

        Game game = new Game();
        game.setId(GAME_ID);
        game.setStatus(GameStatus.INITIAL_PEEK);
        game.setPlayerHands(hands);
        game.setOrderedPlayerIds(List.of(1L, 2L));

        // Optional.of() used because gameRepository.findById() returns Optional<Game>
        Mockito.when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        // when we call gameRepository.save() and pass it an instance of type Game
        // return the game instance that was passed as the argument
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.INITIAL);
        body.setIndices(List.of(0, 2));
        gameService.applyPeek(GAME_ID, "token", body);

        assertTrue(hand1.get(0).getVisibility());
        assertFalse(hand1.get(1).getVisibility());
        assertTrue(hand1.get(2).getVisibility());
        assertFalse(hand1.get(3).getVisibility());
        assertTrue(game.getInitialPeekDoneByUserId().get(1L));
        Mockito.verify(gameEventPublisher).publishFilteredState(game);
    }

    @Test
    void duplicateInitialPeek_throwsForbidden() {
        User player = new User();
        player.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(player);

        List<Card> hand1 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setVisibility(false);
            c.setValue(i);
            hand1.add(c);
        }
        Map<Long, List<Card>> hands = new HashMap<>();
        hands.put(1L, hand1);

        Game game = new Game();
        game.setId(GAME_ID);
        game.setStatus(GameStatus.INITIAL_PEEK);
        game.setPlayerHands(hands);

        // Optional.of() used because gameRepository.findById() returns Optional<Game>
        Mockito.when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        // when we call gameRepository.save() and pass it an instance of type Game
        // return the game instance that was passed as the argument
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        PeekSelectionDTO first = new PeekSelectionDTO();
        first.setPeekType(PeekType.INITIAL);
        first.setIndices(List.of(0, 1));
        gameService.applyPeek(GAME_ID, "token", first);

        assertTrue(Boolean.TRUE.equals(game.getInitialPeekDoneByUserId().get(1L)));

        PeekSelectionDTO second = new PeekSelectionDTO();
        second.setPeekType(PeekType.INITIAL);
        second.setIndices(List.of(2, 3));

        // assert an exception is thrown on the second "initial peek" attempt
        // save the exception instance for further checks
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek(GAME_ID, "token", second));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("Initial peek already used", ex.getReason());
        // verify gameEventPublisher.publishFilteredState was called once during first successful peek only
        Mockito.verify(gameEventPublisher, Mockito.times(1)).publishFilteredState(game);
    }

    @Test
    void advanceTurnToNextPlayer_timedOutNextPlayer_autoCallsCaboOnThatTurn() {
        Game game = new Game();
        game.setId(GAME_ID);
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L)));
        game.setCurrentPlayerId(1L);

        Mockito.when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();
        Mockito.when(lobbyService.isPlayerTimedOutInPlaying(2L)).thenReturn(true);

        gameService.advanceTurnToNextPlayer(GAME_ID);

        assertTrue(game.isCaboCalled());
        assertEquals(2L, game.getCaboCalledByUserId());
        assertEquals(3L, game.getCurrentPlayerId());
        verify(lobbyService).isPlayerTimedOutInPlaying(2L);
        verify(gameEventPublisher, Mockito.atLeastOnce()).publishFilteredState(any(Game.class));
    }

    // placeholder testing 1/3
    @Test
    void startGame_validPlayers_returnsSavedGameWithId() {
        List<CardDTO> deck = new ArrayList<>();
        for (int i = 0; i < 52; i++) {
            CardDTO dto = new CardDTO();
            dto.setCode("AS");
            deck.add(dto);
        }
        Mockito.when(deckOfCardsAPIService.createNewDeckId()).thenReturn("test-deck-id");
        // doNothing because method is void
        Mockito.doNothing().when(deckOfCardsAPIService).shuffleDeck(Mockito.anyString());
        Mockito.when(deckOfCardsAPIService.drawFromDeck(Mockito.eq("test-deck-id"), Mockito.eq(52))).thenReturn(deck);
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> {
            Game g = inv.getArgument(0);
            g.setId("game-1");
            return g;
        });
        Mockito.doNothing().when(gameRepository).flush();

        Game result = gameService.startGame(List.of(1L, 2L));

        assertNotNull(result);
        assertEquals("game-1", result.getId());
        assertEquals(2, result.getOrderedPlayerIds().size());
        Mockito.verify(gameEventPublisher, Mockito.times(1)).publishFilteredState(result);
    }

    // placeholder testing 2/3  
    @Test
    void startGame_whenDeckApiFails_usesFallbackDeckAndStillStarts() {
        Mockito.when(deckOfCardsAPIService.createNewDeckId()).thenThrow(new RuntimeException("Deck API down"));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> {
            Game g = inv.getArgument(0);
            g.setId("game-fallback");
            return g;
        });
        Mockito.doNothing().when(gameRepository).flush();

        Game result = gameService.startGame(List.of(1L, 2L));

        assertNotNull(result);
        assertEquals("game-fallback", result.getId());
        assertEquals(2, result.getPlayerHands().size());
        assertEquals(4, result.getPlayerHands().get(1L).size());
        assertEquals(4, result.getPlayerHands().get(2L).size());
        assertEquals(43, result.getDrawPile().size());
        assertEquals(1, result.getDiscardPile().size());
        Mockito.verify(gameEventPublisher, Mockito.times(1)).publishFilteredState(result);
    }

    // placeholder testing 3/3
    @Test
    void startGame_withTooFewPlayers_throwsConflict() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> gameService.startGame(List.of(1L))
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Lobby requires 2 to 4 players", ex.getReason());
    }

    // this tests that no ability is triggered if the card should not trigger one and that the 
    // turn is immediately passed to the next player
    @Test
    void testCardAbility_cardWithoutAbility_doesNothing() {
        // create a game which is in an active round and a card without ability is drawn from the draw pile
        Game game = new Game();
        game.setId("testGameId");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        List<Long> orderedPlayerIds = List.of(1L, 2L);
        game.setDiscardPile(new ArrayList<>());
        game.setOrderedPlayerIds(orderedPlayerIds);
        game.setCurrentPlayerId(1L);
        Card cardWithoutAbility = new Card();
        cardWithoutAbility.setValue(5);
        game.setDrawnCard(cardWithoutAbility);
        game.setDrawnFromDeck(true);
        User user = new User();
        user.setToken("testToken");
        user.setId(1L);


        Mockito.when(gameRepository.findById("testGameId")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenReturn(game);
        Mockito.when(userRepository.findByToken("testToken")).thenReturn(user);

        // call the method to apply the card ability
        gameService.moveCardToDiscardPile("testGameId", "testToken");

        // make sure the outcome is as we expect for a card without ability
        assertNull(game.getDrawnCard());
        assertTrue(game.getDiscardPile().contains(cardWithoutAbility));
        assertEquals(GameStatus.ROUND_ACTIVE, game.getStatus());
        assertEquals(2L, game.getCurrentPlayerId());

        // make sure game was saved and state was published
        Mockito.verify(gameRepository, Mockito.times(2)).save(game);
        Mockito.verify(gameEventPublisher, Mockito.times(2)).publishFilteredState(game);
    }

    // this tests that the correct ability is triggered if the card has an ability and that the 
    // turn is not advanced
    @Test
    void testCardAbility_peekCard_setsNewStatus() {
        Game game = new Game();
        game.setId("testGameId");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setDiscardPile(new ArrayList<>());
        List<Long> orderedPlayerIds = List.of(1L, 2L, 3L);
        game.setOrderedPlayerIds(orderedPlayerIds);
        game.setCurrentPlayerId(1L);
        Card peekCard = new Card();
        peekCard.setValue(10);
        game.setDrawnCard(peekCard);
        game.setDrawnFromDeck(true);
        User user = new User();
        user.setId(1L);
        user.setToken("testToken");

        Mockito.when(gameRepository.findById("testGameId")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenReturn(game);
        Mockito.when(userRepository.findByToken("testToken")).thenReturn(user);

        gameService.moveCardToDiscardPile("testGameId", "testToken");

        assertNull(game.getDrawnCard());
        assertTrue(game.getDiscardPile().contains(peekCard));
        assertEquals(GameStatus.ABILITY_PEEK_OPPONENT, game.getStatus());
        assertEquals(1L, game.getCurrentPlayerId());

        Mockito.verify(gameRepository, Mockito.times(1)).save(game);
        Mockito.verify(gameEventPublisher, Mockito.times(1)).publishFilteredState(game);
    }

    // this tests that the that no ability is triggered if the card does not come from the draw 
    // pile and that the turn is advanced to the next player
    @Test
    void testCardAility_swapCardFromDiscardPile_noNewStatus() {
        // set the game up
        Game game = new Game();
        game.setId("testGameId");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setDiscardPile(new ArrayList<>());
        List<Long> orderedPlayerIds = List.of(1L, 2L, 3L);
        game.setOrderedPlayerIds(orderedPlayerIds);
        game.setCurrentPlayerId(1L);
        Card swapCard = new Card();
        swapCard.setValue(11);
        Card topDiscard = new Card();
        topDiscard.setValue(5);
        game.setDrawnCard(topDiscard);
        game.setPlayerHands(Map.of(
                1L, new ArrayList<>(List.of(swapCard, new Card(), new Card(), new Card())),
                2L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card())),
                3L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card()))
        ));
        game.setDrawnFromDeck(false);
        game.setDiscardPile(new ArrayList<>());

        // set up the user
        User user = new User();
        user.setId(1L);
        user.setToken("testToken");

        Mockito.when(gameRepository.findById("testGameId")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenReturn(game);
        Mockito.when(userRepository.findByToken("testToken")).thenReturn(user);
        
        // apply the move
        gameService.moveSwapDrawnCard("testGameId", "testToken", 0);

        assertNull(game.getDrawnCard());
        assertTrue(game.getDiscardPile().contains(swapCard));
        assertEquals(GameStatus.ROUND_ACTIVE, game.getStatus());
        assertEquals(2L, game.getCurrentPlayerId());

        Mockito.verify(gameRepository, Mockito.times(2)).save(game);
        Mockito.verify(gameEventPublisher, Mockito.times(2)).publishFilteredState(game);
    }

    @Test
    void applySpecialPeek_selfPeek_reveals_staysInAbilityPhase() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        List<Card> hand1 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setVisibility(false);
            c.setValue(i);
            hand1.add(c);
        }

        List<Card> hand2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setVisibility(false);
            c.setValue(i);
            hand2.add(c);
        }

        Map<Long, List<Card>> hands = new HashMap<>();
        hands.put(1L, hand1);
        hands.put(2L, hand2);

        Game game = new Game();
        game.setId("g-peek-self");
        game.setStatus(GameStatus.ABILITY_PEEK_SELF);
        game.setPlayerHands(hands);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);

        Mockito.when(gameRepository.findById("g-peek-self")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.SPECIAL);
        body.setIndices(List.of(1));

        gameService.applyPeek("g-peek-self", "token", body);

        assertTrue(hand1.get(1).getVisibility());
        assertFalse(hand1.get(0).getVisibility());
        assertEquals(GameStatus.ABILITY_PEEK_SELF, game.getStatus());
        assertEquals(1L, game.getCurrentPlayerId());
        Mockito.verify(gameEventPublisher, Mockito.times(1)).publishFilteredState(game);
    }

    @Test
    void applySpecialPeek_opponentPeek_reveals_staysInAbilityPhase() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        List<Card> hand1 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            hand1.add(c);
        }

        List<Card> hand2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            c.setVisibility(false);
            c.setValue(i);
            hand2.add(c);
        }

        Map<Long, List<Card>> hands = new HashMap<>();
        hands.put(1L, hand1);
        hands.put(2L, hand2);

        Game game = new Game();
        game.setId("g-spy");
        game.setStatus(GameStatus.ABILITY_PEEK_OPPONENT);
        game.setPlayerHands(hands);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);

        Mockito.when(gameRepository.findById("g-spy")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.SPECIAL);
        body.setHandUserId(2L);
        body.setIndices(List.of(2));

        gameService.applyPeek("g-spy", "token", body);

        assertTrue(hand2.get(2).getVisibility());
        assertEquals(GameStatus.ABILITY_PEEK_OPPONENT, game.getStatus());
        assertEquals(1L, game.getCurrentPlayerId());
        Mockito.verify(gameEventPublisher, Mockito.times(1)).publishFilteredState(game);
    }


    @Test
    void applySpecialPeek_whenNotInAbilityPhase_throwsConflict() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        List<Card> hand1 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card c = new Card();
            hand1.add(c);
        }

        Map<Long, List<Card>> hands = new HashMap<>();
        hands.put(1L, hand1);

        Game game = new Game();
        game.setId("g-peek-conflict");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setPlayerHands(hands);

        Mockito.when(gameRepository.findById("g-peek-conflict")).thenReturn(Optional.of(game));

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.SPECIAL);
        body.setIndices(List.of(0));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("g-peek-conflict", "token", body));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        Mockito.verify(gameEventPublisher, Mockito.never()).publishFilteredState(any());
    }

    // #64: draw from discard then swap drawn card so the top discard lands in playerHands (two service calls)
    @Test
    void moveDrawFromDiscardPile_success_discardTopEndsUpInHandAfterSwapDrawnCard() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card top = new Card();
        top.setValue(9);
        top.setCode("9H");

        Card handP1CardIndex0 = new Card();
        handP1CardIndex0.setValue(3);
        handP1CardIndex0.setCode("3C");

        List<Card> handP1 = new ArrayList<>();
        handP1.add(handP1CardIndex0);
        for (int i = 0; i < 3; i++) {
            handP1.add(new Card());
        }
        List<Card> handP2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            handP2.add(new Card());
        }

        Game game = new Game();
        game.setId("g-discard-draw");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDiscardPile(new ArrayList<>(List.of(top)));
        game.setDrawnCard(null);
        game.setPlayerHands(new HashMap<>(Map.of(1L, handP1, 2L, handP2)));

        Mockito.when(gameRepository.findById("g-discard-draw")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        gameService.moveDrawFromDiscardPile("g-discard-draw", "token");

        assertTrue(game.getDiscardPile().isEmpty());
        assertNotNull(game.getDrawnCard());
        assertEquals(9, game.getDrawnCard().getValue());

        gameService.moveSwapDrawnCard("g-discard-draw", "token", 0);

        assertNull(game.getDrawnCard());
        assertEquals(9, handP1.get(0).getValue());
        assertEquals("9H", handP1.get(0).getCode());
        assertFalse(handP1.get(0).getVisibility());
        assertEquals(1, game.getDiscardPile().size());
        Card newTop = game.getDiscardPile().get(game.getDiscardPile().size() - 1);
        assertEquals(3, newTop.getValue());
        assertEquals("3C", newTop.getCode());
        assertTrue(newTop.getVisibility());
        assertEquals(2L, game.getCurrentPlayerId());

        Mockito.verify(gameRepository, Mockito.times(3)).save(game);
        Mockito.verify(gameEventPublisher, Mockito.times(3)).publishFilteredState(game);
    }

    @Test
    void moveDrawFromDiscardPile_emptyDiscard_throwsConflict() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Game game = new Game();
        game.setId("g-discard-empty");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setDiscardPile(new ArrayList<>());
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Mockito.when(gameRepository.findById("g-discard-empty")).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveDrawFromDiscardPile("g-discard-empty", "token"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Discard pile is empty.", ex.getReason());
    }

    @Test
    void moveDrawFromDiscardPile_alreadyHasDrawnCard_throwsConflict() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card top = new Card();
        Game game = new Game();
        game.setId("g-discard-double");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDiscardPile(new ArrayList<>(List.of(top)));
        // this will cause conflict
        game.setDrawnCard(new Card());

        Mockito.when(gameRepository.findById("g-discard-double")).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveDrawFromDiscardPile("g-discard-double", "token"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("You have already drawn a card!", ex.getReason());
    }

    @Test
    void moveDrawFromDiscardPile_notRoundActive_throwsConflict() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card top = new Card();
        Game game = new Game();
        game.setId("g-discard-phase");
        game.setStatus(GameStatus.INITIAL_PEEK);
        game.setCurrentPlayerId(1L);
        game.setDiscardPile(new ArrayList<>(List.of(top)));
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Mockito.when(gameRepository.findById("g-discard-phase")).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveDrawFromDiscardPile("g-discard-phase", "token"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Round is not active.", ex.getReason());
    }

    @Test
    void moveSwapWithDiscardPile_success_swapsTopWithHandAndAdvancesTurn() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card fromDiscard = new Card();
        fromDiscard.setValue(7);
        fromDiscard.setCode("7D");
        Card handSlot = new Card();
        handSlot.setValue(3);
        handSlot.setCode("3C");

        List<Card> p1 = new ArrayList<>();
        p1.add(handSlot);
        for (int i = 0; i < 3; i++) {
            p1.add(new Card());
        }
        List<Card> p2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            p2.add(new Card());
        }
        Game game = new Game();
        game.setId("g-swap-discard");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDiscardPile(new ArrayList<>(List.of(fromDiscard)));
        game.setDrawnCard(null);
        game.setPlayerHands(new HashMap<>(Map.of(1L, p1, 2L, p2)));

        Mockito.when(gameRepository.findById("g-swap-discard")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        gameService.moveSwapWithDiscardPile("g-swap-discard", "token", 0);

        // swapped directly with hand, drawn card slot not used
        assertNull(game.getDrawnCard());
        assertEquals(7, p1.get(0).getValue());
        assertEquals("7D", p1.get(0).getCode());
        assertFalse(p1.get(0).getVisibility());
        assertEquals(1, game.getDiscardPile().size());
        // get top card from discard pile
        // do not use getDiscardPileTopCard() to isolate behavior
        Card newTop = game.getDiscardPile().get(game.getDiscardPile().size() - 1);
        assertEquals(3, newTop.getValue());
        assertTrue(newTop.getVisibility());
        assertEquals(2L, game.getCurrentPlayerId());
        Mockito.verify(gameRepository, Mockito.times(2)).save(game);
        Mockito.verify(gameEventPublisher, Mockito.times(2)).publishFilteredState(game);
    }

    // #67: tests broadcast mapper in combination with real service logic execution
    @Test
    void moveSwapWithDiscardPile_broadcast_showsDiscardTopToAllAndHidesSwappedInHandCard() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card fromDiscard = new Card();
        fromDiscard.setValue(7);
        fromDiscard.setCode("7D");
        Card handSlot = new Card();
        handSlot.setValue(3);
        handSlot.setCode("3C");

        List<Card> p1 = new ArrayList<>();
        p1.add(handSlot);
        for (int i = 0; i < 3; i++) {
            p1.add(new Card());
        }
        List<Card> p2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            p2.add(new Card());
        }
        Game game = new Game();
        game.setId("g-swap-broadcast-chain");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDiscardPile(new ArrayList<>(List.of(fromDiscard)));
        game.setDrawnCard(null);
        game.setPlayerHands(new HashMap<>(Map.of(1L, p1, 2L, p2)));

        Mockito.when(gameRepository.findById("g-swap-broadcast-chain")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        gameService.moveSwapWithDiscardPile("g-swap-broadcast-chain", "token", 0);

        GameStateBroadcastMapper broadcastMapper = new GameStateBroadcastMapper(lobbyService);
        for (Long viewerId : List.of(1L, 2L)) {
            GameStateBroadcastDTO dto = broadcastMapper.toBroadcastForViewer(game, viewerId);
            assertNotNull(dto.getDiscardPileTop());
            assertEquals(3, dto.getDiscardPileTop().getValue());
            assertEquals("3C", dto.getDiscardPileTop().getCode());
            CardViewDTO p1slot0 = dto.getPlayers().stream()
                    .filter(ph -> ph.getUserId() == 1L)
                    .findFirst()
                    .orElseThrow()
                    .getCards()
                    .get(0);
            assertTrue(p1slot0.isFaceDown());
            assertNull(p1slot0.getValue());
            assertNull(p1slot0.getCode());
        }
    }

    // #67: draw + swap drawn card — same behavior as moveSwapWithDiscardPile_broadcast_showsDiscardTopToAllAndHidesSwappedInHandCard
    @Test
    void moveDrawFromDiscardPile_thenSwapDrawnCard_broadcast_showsDiscardTopToAllAndHidesSwappedInHandCard() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card fromDiscard = new Card();
        fromDiscard.setValue(7);
        fromDiscard.setCode("7D");
        Card handSlot = new Card();
        handSlot.setValue(3);
        handSlot.setCode("3C");

        List<Card> p1 = new ArrayList<>();
        p1.add(handSlot);
        for (int i = 0; i < 3; i++) {
            p1.add(new Card());
        }
        List<Card> p2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            p2.add(new Card());
        }
        Game game = new Game();
        game.setId("g-draw-swap-broadcast-chain");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDiscardPile(new ArrayList<>(List.of(fromDiscard)));
        game.setDrawnCard(null);
        game.setPlayerHands(new HashMap<>(Map.of(1L, p1, 2L, p2)));

        Mockito.when(gameRepository.findById("g-draw-swap-broadcast-chain")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        gameService.moveDrawFromDiscardPile("g-draw-swap-broadcast-chain", "token");
        gameService.moveSwapDrawnCard("g-draw-swap-broadcast-chain", "token", 0);

        GameStateBroadcastMapper broadcastMapper = new GameStateBroadcastMapper(lobbyService);
        for (Long viewerId : List.of(1L, 2L)) {
            GameStateBroadcastDTO dto = broadcastMapper.toBroadcastForViewer(game, viewerId);
            assertNotNull(dto.getDiscardPileTop());
            assertEquals(3, dto.getDiscardPileTop().getValue());
            assertEquals("3C", dto.getDiscardPileTop().getCode());
            CardViewDTO p1slot0 = dto.getPlayers().stream()
                    .filter(ph -> ph.getUserId() == 1L)
                    .findFirst()
                    .orElseThrow()
                    .getCards()
                    .get(0);
            assertTrue(p1slot0.isFaceDown());
            assertNull(p1slot0.getValue());
            assertNull(p1slot0.getCode());
        }
    }

    @Test
    void moveSwapWithDiscardPile_drawnCardAlreadySet_throwsConflict() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card top = new Card();
        Game game = new Game();
        game.setId("g-swap-block");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDiscardPile(new ArrayList<>(List.of(top)));
        game.setDrawnCard(new Card());
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            hand.add(new Card());
        }
        game.setPlayerHands(Map.of(1L, hand));

        Mockito.when(gameRepository.findById("g-swap-block")).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveSwapWithDiscardPile("g-swap-block", "token", 0));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Cannot swap with discard pile after drawing a card", ex.getReason());
    }

    @Test
    void moveSwapWithDiscardPile_emptyDiscard_throwsConflict() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Game game = new Game();
        game.setId("g-swap-empty");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDiscardPile(new ArrayList<>());
        game.setDrawnCard(null);
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            hand.add(new Card());
        }
        game.setPlayerHands(Map.of(1L, hand));

        Mockito.when(gameRepository.findById("g-swap-empty")).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveSwapWithDiscardPile("g-swap-empty", "token", 0));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Discard pile is empty", ex.getReason());
    }

    @Test
    void moveSwapWithDiscardPile_invalidIndex_throwsBadRequest() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card top = new Card();
        Game game = new Game();
        game.setId("g-swap-bad-index");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDiscardPile(new ArrayList<>(List.of(top)));
        game.setDrawnCard(null);
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            hand.add(new Card());
        }
        game.setPlayerHands(Map.of(1L, hand));

        Mockito.when(gameRepository.findById("g-swap-bad-index")).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveSwapWithDiscardPile("g-swap-bad-index", "token", 10));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Invalid card index", ex.getReason());
    }

    // #76: 30s ability timeout ends peek phase and passes turn when player never picks a target
    @Test
    void abilityTimeout_endsPeekAbility_andAdvancesTurn() {
        Game game = new Game();
        game.setId("g-ability-timeout-peek");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setDiscardPile(new ArrayList<>());
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);
        game.setPlayerHands(new HashMap<>(Map.of(
                1L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card())),
                2L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card()))
        )));
        User user = new User();
        user.setId(1L);
        user.setToken("testToken");

        // findById -> return game
        when(gameRepository.findById("g-ability-timeout-peek")).thenReturn(Optional.of(game));
        // save game -> return game
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(gameRepository).flush();
        Mockito.when(userRepository.findByToken("testToken")).thenReturn(user);

        List<Runnable> listOfScheduled = new ArrayList<>();
        // schedule call returns a future object, so mock them 
        ScheduledFuture<?> abilitySchedFuture = Mockito.mock(ScheduledFuture.class);
        ScheduledFuture<?> turnSchedFuture = Mockito.mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            // add whatever we are scheduling to the list
            listOfScheduled.add(inv.getArgument(0));
            // scheduleCallCount is an instance variable in test class, initialized to 0 for each new test
            int n = scheduleCallCount++;
            // first -> ability timer, second -> turn timer
            return n == 0 ? abilitySchedFuture : turnSchedFuture;
        });

        Card fromDeck = new Card();
        fromDeck.setValue(8);
        game.setDrawnCard(fromDeck);
        game.setDrawnFromDeck(true);
        // schedules first timer (ability timer)
        gameService.moveCardToDiscardPile("g-ability-timeout-peek", "testToken");

        assertEquals(GameStatus.ABILITY_PEEK_SELF, game.getStatus());
        assertEquals(1L, game.getCurrentPlayerId());
        assertEquals(1, listOfScheduled.size(), "only ability timeout should be scheduled so far");

        // simulate 30s timeout of ability timer
        // this ends the ability, advances the turn, and schedules the turn timer
        listOfScheduled.get(0).run();

        assertEquals(GameStatus.ROUND_ACTIVE, game.getStatus());
        assertEquals(2L, game.getCurrentPlayerId());
    }

    // #76: 30s ability timeout ends swap phase and passes turn when player never swaps
    @Test
    void abilityTimeout_endsSwapAbility_andAdvancesTurn() {
        Game game = new Game();
        game.setId("g-ability-timeout-swap");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setDiscardPile(new ArrayList<>());
        game.setOrderedPlayerIds(List.of(1L, 2L, 3L));
        game.setCurrentPlayerId(1L);
        game.setPlayerHands(new HashMap<>(Map.of(
                1L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card())),
                2L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card())),
                3L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card()))
        )));
        User user = new User();
        user.setId(1L);
        user.setToken("testToken");

        // findById -> return game
        when(gameRepository.findById("g-ability-timeout-swap")).thenReturn(Optional.of(game));
        // save game -> return game
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(gameRepository).flush();
        Mockito.when(userRepository.findByToken("testToken")).thenReturn(user);

        List<Runnable> listOfScheduled = new ArrayList<>();
        // schedule call returns a future object, so mock them 
        ScheduledFuture<?> abilitySchedFuture = Mockito.mock(ScheduledFuture.class);
        ScheduledFuture<?> turnSchedFuture = Mockito.mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            // add whatever we are scheduling to the list
            listOfScheduled.add(inv.getArgument(0));
            // scheduleCallCount is an instance variable in test class, initialized to 0 for each new test
            int n = scheduleCallCount++;
            // first -> ability timer, second -> turn timer
            return n == 0 ? abilitySchedFuture : turnSchedFuture;
        });

        Card fromDeck = new Card();
        fromDeck.setValue(11);
        game.setDrawnCard(fromDeck);
        game.setDrawnFromDeck(true);
        // schedules first timer (ability timer)
        gameService.moveCardToDiscardPile("g-ability-timeout-swap", "testToken");

        assertEquals(GameStatus.ABILITY_SWAP, game.getStatus());
        assertEquals(1L, game.getCurrentPlayerId());
        assertEquals(1, listOfScheduled.size());

        // simulates ability timer timeout
        // this ends the ability, advances the turn, and schedules the turn timer
        listOfScheduled.get(0).run();

        assertEquals(GameStatus.ROUND_ACTIVE, game.getStatus());
        assertEquals(2L, game.getCurrentPlayerId());
    }

    @Test
    void outdatedAbilityTimer_isIgnored_afterNewAbilityCycleStarts() {
        User p1 = new User();
        p1.setId(1L);
        User p2 = new User();
        p2.setId(2L);
        when(userRepository.findByToken("token-1")).thenReturn(p1);
        when(userRepository.findByToken("token-2")).thenReturn(p2);

        Game game = new Game();
        game.setId("g-outdated-ability-timer");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);

        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);
        game.setDrawPile(new ArrayList<>(List.of(new Card(), new Card(), new Card())));
        game.setDiscardPile(new ArrayList<>());
        game.setPlayerHands(new HashMap<>(Map.of(
                1L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card())),
                2L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card()))
        )));

        when(gameRepository.findById("g-outdated-ability-timer")).thenReturn(Optional.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(gameRepository).flush();

        List<Runnable> listOfScheduled = new ArrayList<>();
        // schedule call returns a future object, so mock them 
        ScheduledFuture<?> firstAbilityFuture = Mockito.mock(ScheduledFuture.class);
        ScheduledFuture<?> turnFuture = Mockito.mock(ScheduledFuture.class);
        ScheduledFuture<?> secondAbilityFuture = Mockito.mock(ScheduledFuture.class);

        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            // add whatever we are scheduling to the list
            listOfScheduled.add(inv.getArgument(0));
            // scheduleCallCount is an instance variable in test class, initialized to 0 for each new test
            int n = scheduleCallCount++;
            if (n == 0) return firstAbilityFuture; // ability timer from first cycle
            if (n == 1) return turnFuture; // turn timer after skip
            if (n == 2) return secondAbilityFuture; // ability timer from second cycle
            return Mockito.mock(ScheduledFuture.class);
        });

        Card firstAbilityCard = new Card();
        firstAbilityCard.setValue(7);
        game.setDrawnCard(firstAbilityCard);
        game.setDrawnFromDeck(true);
        // schedule first ability timer
        gameService.moveCardToDiscardPile("g-outdated-ability-timer", "token-1");
        assertEquals(GameStatus.ABILITY_PEEK_SELF, game.getStatus());

        // cancel first ability timer, schedule turn timer
        gameService.skipAbility("g-outdated-ability-timer", "token-1");
        assertEquals(GameStatus.ROUND_ACTIVE, game.getStatus());
        assertEquals(2L, game.getCurrentPlayerId());
        // verify the first ability timer was canceled
        verify(firstAbilityFuture).cancel(anyBoolean());

        Card secondAbilityCard = new Card();
        secondAbilityCard.setValue(9);
        game.setDrawnCard(secondAbilityCard);
        game.setDrawnFromDeck(true);
        // cancel turn timer, schedule second ability timer
        gameService.moveCardToDiscardPile("g-outdated-ability-timer", "token-2");
        assertEquals(GameStatus.ABILITY_PEEK_OPPONENT, game.getStatus());
        assertEquals(2L, game.getCurrentPlayerId());

        // run outdated ability timer
        Runnable outdatedAbilityTimer = listOfScheduled.get(0);
        outdatedAbilityTimer.run();

        // nothing changes after trying to run an outdated ability timer
        assertEquals(GameStatus.ABILITY_PEEK_OPPONENT, game.getStatus());
        assertEquals(2L, game.getCurrentPlayerId());
    }

    @Test
    public void moveDrawFromDrawPile_emptyDrawPile_triggersAPIShuffleAndDrawsCard() {

        User user = new User();
        user.setId(1L);
        user.setToken("testToken");

        Game game = new Game();
        game.setId("gameId");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setDeckApiId("testId");
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);
        game.setDiscardPile(new ArrayList<>());

        Card bottom1 = new Card();
        bottom1.setCode("2H");
        Card bottom2 = new Card();
        bottom2.setCode("3C");
        Card bottom3 = new Card();
        bottom3.setCode("AS");
        game.setDiscardPile(new ArrayList<>(List.of(bottom1, bottom2, bottom3)));

        when(gameRepository.findById("gameId")).thenReturn(Optional.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByToken("testToken")).thenReturn(user);

        doNothing().when(deckOfCardsAPIService).returnDrawnCardsToDeck(eq("testId"), anyList());
        doNothing().when(deckOfCardsAPIService).shuffleDeck(eq("testId"));
        
        CardDTO fresh1 = new CardDTO();
        fresh1.setCode("2H");
        CardDTO fresh2 = new CardDTO();
        fresh2.setCode("3C");
        when(deckOfCardsAPIService.drawFromDeck(eq("testId"), eq(2))).thenReturn(List.of(fresh1, fresh2));

        gameService.moveDrawFromDrawPile("gameId", "testToken");

        verify(deckOfCardsAPIService).returnDrawnCardsToDeck(eq("testId"), anyList());
        verify(deckOfCardsAPIService).shuffleDeck(eq("testId"));
        verify(deckOfCardsAPIService).drawFromDeck(eq("testId"), eq(2));

        assertEquals(1, game.getDiscardPile().size(), "Discard pile should have 1 card left");
        assertEquals("AS", game.getDiscardPile().get(0).getCode());

        assertNotNull(game.getDrawnCard(), "A card should have been drawn");
        assertEquals("2H", game.getDrawnCard().getCode());

        assertEquals(1, game.getDrawPile().size(), "Draw pile should have 1 card left");
    }

    // drawing during INITIAL_PEEK phase is blocked
    @Test
    void moveDrawFromDrawPile_duringInitialPeek_throwsConflict() {
        Game game = new Game();
        game.setId("g-peek-block");
        game.setStatus(GameStatus.INITIAL_PEEK);
        game.setDrawPile(new ArrayList<>(List.of(new Card())));
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);

        User user = new User();
        user.setId(1L);
        user.setToken("testToken");

        Mockito.when(gameRepository.findById("g-peek-block")).thenReturn(Optional.of(game));
        Mockito.when(userRepository.findByToken("testToken")).thenReturn(user);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveDrawFromDrawPile("g-peek-block", "testToken"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Cannot draw a card right now", ex.getReason());
    }

    // ability swap exchanges cards between two players without revealing values
    @Test
    void moveAbilitySwap_success_exchangesCardsWithoutRevealingValues() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card ownCard = new Card();
        ownCard.setValue(3);
        ownCard.setCode("3C");
        ownCard.setVisibility(false);

        Card targetCard = new Card();
        targetCard.setValue(9);
        targetCard.setCode("9H");
        targetCard.setVisibility(false);

        List<Card> ownHand = new ArrayList<>();
        ownHand.add(ownCard);
        for (int i = 0; i < 3; i++) ownHand.add(new Card());

        List<Card> targetHand = new ArrayList<>();
        targetHand.add(targetCard);
        for (int i = 0; i < 3; i++) targetHand.add(new Card());

        Game game = new Game();
        game.setId("g-ability-swap");
        game.setStatus(GameStatus.ABILITY_SWAP);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setPlayerHands(new HashMap<>(Map.of(1L, ownHand, 2L, targetHand)));
        game.setDiscardPile(new ArrayList<>());

        Mockito.when(gameRepository.findById("g-ability-swap")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        gameService.moveAbilitySwap("g-ability-swap", "token", 0, 2L, 0);

        // cards should be swapped
        assertEquals("9H", ownHand.get(0).getCode());
        assertEquals("3C", targetHand.get(0).getCode());
        // neither card should be revealed
        assertFalse(ownHand.get(0).getVisibility());
        assertFalse(targetHand.get(0).getVisibility());
        // game should return to ROUND_ACTIVE
        assertEquals(GameStatus.ROUND_ACTIVE, game.getStatus());
        // turn should advance
        assertEquals(2L, game.getCurrentPlayerId());
    }

    // Cabo is called, exactly N-1 players get a final turn then rematch/no-rematch decision starts
    @Test
    void moveCallCabo_thenAllOtherPlayersTakeTurn_entersRematchDecision() {
        User caboUser = new User();
        caboUser.setId(1L);
        Mockito.when(userRepository.findByToken("token-1")).thenReturn(caboUser);

        Game game = new Game();
        game.setId("g-cabo");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L, 4L)));
        game.setDiscardPile(new ArrayList<>());
        game.setDrawPile(new ArrayList<>());
        game.setPlayerHands(new HashMap<>(Map.of(
                1L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card())),
                2L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card())),
                3L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card())),
                4L, new ArrayList<>(List.of(new Card(), new Card(), new Card(), new Card()))
        )));

        Mockito.when(gameRepository.findById("g-cabo")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        Session playingSession = new Session();
        playingSession.setSessionId("session-g-cabo");
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds()))
                .thenReturn("session-g-cabo");
        Mockito.when(sessionRepository.findBySessionId("session-g-cabo")).thenReturn(playingSession);
       
        // player 1 calls cabo
        gameService.moveCallCabo("g-cabo", "token-1");
        assertTrue(game.isCaboCalled());
        assertEquals(1L, game.getCaboCalledByUserId());
        assertEquals(2L, game.getCurrentPlayerId()); // turn advances to player 2

        // player 2 takes final turn
        gameService.advanceTurnToNextPlayer("g-cabo");
        assertEquals(3L, game.getCurrentPlayerId()); // turn advances to player 3

        // player 3 takes final turn
        gameService.advanceTurnToNextPlayer("g-cabo");
        assertEquals(4L, game.getCurrentPlayerId()); // turn advances to player 4

        // player 4 takes final turn — next would be player 1 (who called cabo) so round ends
        gameService.advanceTurnToNextPlayer("g-cabo");
        assertEquals(GameStatus.CABO_REVEAL, game.getStatus());
        for (List<Card> hand : game.getPlayerHands().values()) {
            for (Card c : hand) {
                assertTrue(c.getVisibility(), "All hand cards should be face-up");
            }
        }
    }

    // #83: Cabo caller must not act as current player during the post-Cabo final lap (draw / swap)

    // 4 players, ROUND_ACTIVE, player 1 calls cabo
    // after return current player is player 2
    private Game createFourPlayerGameAndCallCaboAsPlayer1(String gameId, List<Card> discardPile) {
        User user1 = new User();
        user1.setId(1L);
        Mockito.when(userRepository.findByToken("token-1")).thenReturn(user1);

        Game game = new Game();
        game.setId(gameId);
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L, 3L, 4L)));
        game.setDiscardPile(new ArrayList<>(discardPile == null ? List.of() : discardPile));
        Card drawCard = new Card();
        drawCard.setValue(4);
        drawCard.setCode("4D");
        game.setDrawPile(new ArrayList<>(List.of(drawCard)));
        Map<Long, List<Card>> hands = new HashMap<>();
        for (Long player_id : List.of(1L, 2L, 3L, 4L)) {
            List<Card> hand = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Card c = new Card();
                c.setValue(i + 1);
                c.setCode("X" + i);
                c.setVisibility(false);
                hand.add(c);
            }
            hands.put(player_id, hand);
        }
        game.setPlayerHands(hands);

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        gameService.moveCallCabo(gameId, "token-1");
        assertTrue(game.isCaboCalled());
        assertEquals(1L, game.getCaboCalledByUserId());
        assertEquals(2L, game.getCurrentPlayerId());
        assertEquals(GameStatus.ROUND_ACTIVE, game.getStatus());
        return game;
    }

    @Test
    void caboCallerAfterCabo_verifyMoveCallerIsCurrentPlayer_throwsForbidden() {
        createFourPlayerGameAndCallCaboAsPlayer1("g-83-verify", List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.verifyMoveCallerIsCurrentPlayer("g-83-verify", "token-1"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("Not your turn", ex.getReason());
    }

    @Test
    void caboCallerAfterCabo_moveDrawFromDiscardPile_throwsForbidden() {
        Card top = new Card();
        top.setValue(9);
        top.setCode("9S");
        createFourPlayerGameAndCallCaboAsPlayer1("g-83-discard-draw", List.of(top));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveDrawFromDiscardPile("g-83-discard-draw", "token-1"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void caboCallerAfterCabo_moveSwapWithDiscardPile_throwsForbidden() {
        Card top = new Card();
        top.setValue(3);
        top.setCode("3H");
        createFourPlayerGameAndCallCaboAsPlayer1("g-83-discard-swap", List.of(top));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveSwapWithDiscardPile("g-83-discard-swap", "token-1", 0));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void caboCallerAfterCabo_moveSwapDrawnCard_throwsForbidden() {
        Game game = createFourPlayerGameAndCallCaboAsPlayer1("g-83-drawn-swap", List.of());

        Card drawn = new Card();
        drawn.setValue(7);
        drawn.setCode("7C");
        game.setDrawnCard(drawn);
        game.setDrawnFromDeck(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveSwapDrawnCard("g-83-drawn-swap", "token-1", 0));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void caboCallerAfterCabo_moveAbilitySwap_throwsForbidden() {
        Game game = createFourPlayerGameAndCallCaboAsPlayer1("g-83-ability-swap", List.of());
        game.setStatus(GameStatus.ABILITY_SWAP);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveAbilitySwap("g-83-ability-swap", "token-1", 0, 3L, 0));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void caboCallerAfterCabo_skipAbility_throwsForbidden() {
        Game game = createFourPlayerGameAndCallCaboAsPlayer1("g-83-skip", List.of());
        game.setStatus(GameStatus.ABILITY_SWAP);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.skipAbility("g-83-skip", "token-1"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void currentPlayerDuringFinalLapAfterCabo_canMoveDrawFromDiscardPile() {
        Card top = new Card();
        top.setValue(8);
        top.setCode("8D");
        Game game = createFourPlayerGameAndCallCaboAsPlayer1("g-83-currentplayer", List.of(top));

        Long currentId = game.getCurrentPlayerId();
        User currentUser = new User();
        currentUser.setId(currentId);
        String currentToken = "token-" + currentId;
        Mockito.when(userRepository.findByToken(currentToken)).thenReturn(currentUser);

        gameService.moveDrawFromDiscardPile("g-83-currentplayer", currentToken);
        assertNotNull(game.getDrawnCard());
        assertEquals("8D", game.getDrawnCard().getCode());
    }

    // #84: rematch decision timer expires -  broadcast sends ROUND_ENDED + all hands are visible
    @Test
    void rematchDecisionTimerExpires_publishesRoundEndedWithRevealedHands() throws Exception {
        // list of scheduled tasks
        List<Runnable> scheduled = new ArrayList<>();
        // @BeforeEach that applies to all tests mocks scheduler in a way that does not give us access to scheduled runnables (tasks)
        // in this test we reset the general mock and implement a new mock that gives us access to scheduled runnables (tasks)
        Mockito.reset(scheduler);
        // when we schedule a task, add them all to scheduled list
        Mockito.when(scheduler.schedule(Mockito.any(Runnable.class), Mockito.anyLong(), Mockito.any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    // runnable that was passed to the scheduler as argument
                    scheduled.add(invocation.getArgument(0));
                    return Mockito.mock(ScheduledFuture.class);
                });

        User user1 = new User();
        user1.setId(1L);
        User user2 = new User();
        user2.setId(2L);
        Mockito.when(userRepository.findByToken("t1")).thenReturn(user1);
        Mockito.when(userRepository.findByToken("t2")).thenReturn(user2);

        Game game = new Game();
        game.setId("g-84-timer");
        game.setStatus(GameStatus.ROUND_AWAITING_REMATCH);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L)));
        game.setRematchDecisionByUserId(new HashMap<>());
        game.setCaboCalled(true);
        game.setCaboCalledByUserId(1L);
        game.setCurrentPlayerId(2L);
        game.setRematchDecisionSeconds(60L);

        Map<Long, List<Card>> hands = new HashMap<>();
        for (Long playerId : List.of(1L, 2L)) {
            Card c = new Card();
            c.setValue(playerId.intValue());
            c.setCode(playerId + "X");
            c.setVisibility(false);
            hands.put(playerId, new ArrayList<>(List.of(c)));
        }
        game.setPlayerHands(hands);
        game.setDiscardPile(new ArrayList<>());
        Card deckTop = new Card();
        deckTop.setValue(10);
        deckTop.setCode("AC");
        game.setDrawPile(new ArrayList<>(List.of(deckTop)));

        Mockito.when(gameRepository.findById("g-84-timer")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.doNothing().when(gameRepository).flush();

        Method startRematchTimer = GameService.class.getDeclaredMethod("startRematchDecisionTimer", String.class);
        // make private method accessible in this test
        startRematchTimer.setAccessible(true);
        // this call will add a runnable (task) to the scheduled list
        startRematchTimer.invoke(gameService, "g-84-timer");

        assertEquals(1, scheduled.size());
        // run the scheduled runnable (task)
        scheduled.get(0).run();

        assertEquals(GameStatus.ROUND_ENDED, game.getStatus());

        // verify publishFilteredState for game was called 
        Mockito.verify(gameEventPublisher, Mockito.times(1)).publishFilteredState(game);

        GameStateBroadcastMapper broadcastMapper = new GameStateBroadcastMapper(lobbyService);
        GameStateBroadcastDTO asPlayer1 = broadcastMapper.toBroadcastForViewer(game, 1L);
        // verify player 1 sees game status as ROUND_ENDED
        assertEquals(GameStatus.ROUND_ENDED, asPlayer1.getStatus());
        // get first card of player 2 from 1st player's game representation
        CardViewDTO player2CardFromPlayer1View = asPlayer1.getPlayers().stream()
                .filter(p -> Long.valueOf(2L).equals(p.getUserId())) // user with id 2
                .findFirst()    
                .orElseThrow()
                .getCards()
                .get(0); // first card
        assertEquals(2, player2CardFromPlayer1View.getValue().intValue());
        assertEquals("2X", player2CardFromPlayer1View.getCode());
    }

    @Test
    void resolveRematchDecision_totalScoreHits100_reducesTo50Once() throws Exception {
        Game game = new Game();
        game.setId("g-91");
        game.setStatus(GameStatus.ROUND_AWAITING_REMATCH);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L)));
        game.setRematchDecisionByUserId(new HashMap<>(Map.of(
                1L, GameService.REMATCH_DECISION_NONE,
                2L, GameService.REMATCH_DECISION_NONE
        )));
        Mockito.when(gameRepository.findById("g-91")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        Session session = new Session();
        session.setSessionId("S91");
        session.setUserScoresPerRound(new ArrayList<>(List.of(
                // round 1 user 1: 60
                // round 1 user 2: 12
                // round 2 user 1: 40
                // round 2 user 2: 5
                new HashMap<>(Map.of(1L, 60, 2L, 12)),
                new HashMap<>(Map.of(1L, 40, 2L, 5))
        )));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("S91");
        Mockito.when(sessionRepository.findBySessionId("S91")).thenReturn(session);
        Mockito.when(sessionRepository.save(Mockito.any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

        // get the resolve method
        Method resolve = GameService.class.getDeclaredMethod("resolveRematchDecisionLocked", String.class, Game.class, Long.class);
        // set accessible because method is private
        resolve.setAccessible(true);
        // code not triggered by timer, so last argument null
        resolve.invoke(gameService, "g-91", game, null);

        // round counts start from 0, so at index 1 we have second round
        // last round's score is reduced by 50 to compensate (40 -> -10)
        assertEquals(-10, session.getUserScoresPerRound().get(1).get(1L));
        // second user's score from last round unaffected
        assertEquals(5, session.getUserScoresPerRound().get(1).get(2L));
        // first user's score 50 instead of 100
        assertEquals(50, session.getTotalScoreByUserId().get(1L));
        // second user's score is just the total of rounds, unaffected
        assertEquals(17, session.getTotalScoreByUserId().get(2L));
        // 100 -> 50 reduction applied to 1st user, but not 2nd user
        assertTrue(Boolean.TRUE.equals(session.getHundredReductionAppliedByUserId().get(1L)));
        assertNull(session.getHundredReductionAppliedByUserId().get(2L));
    }

    @Test
    void resolveRematchDecision_totalScoreHits100Again_noSecondReductionForSamePlayer() throws Exception {
        Game game = new Game();
        game.setId("g-91-once");
        game.setStatus(GameStatus.ROUND_AWAITING_REMATCH);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L)));
        game.setRematchDecisionByUserId(new HashMap<>(Map.of(1L, GameService.REMATCH_DECISION_NONE)));
        Mockito.when(gameRepository.findById("g-91-once")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        Session session = new Session();
        session.setSessionId("S91-ONCE");
        session.setUserScoresPerRound(new ArrayList<>(List.of(
                new HashMap<>(Map.of(1L, 60)),
                new HashMap<>(Map.of(1L, 40))
        )));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("S91-ONCE");
        Mockito.when(sessionRepository.findBySessionId("S91-ONCE")).thenReturn(session);
        Mockito.when(sessionRepository.save(Mockito.any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

        // get the resolve method
        Method resolve = GameService.class.getDeclaredMethod("resolveRematchDecisionLocked", String.class, Game.class, Long.class);
        // set accessible because method is private
        resolve.setAccessible(true);
        // code not triggered by timer, so last argument null
        resolve.invoke(gameService, "g-91-once", game, null);
        // resolve subtracted 50 from last rounds score to compensate (40 -> -10)
        // and set the total score from 100 to 50
        assertEquals(-10, session.getUserScoresPerRound().get(1).get(1L));
        assertEquals(50, session.getTotalScoreByUserId().get(1L));

        // resolve changed the status, but to check to logic that the "100 -> 50" rule does not run again,
        // we need to be in this status again
        game.setStatus(GameStatus.ROUND_AWAITING_REMATCH);
        // total is 100 again
        session.getUserScoresPerRound().add(new HashMap<>(Map.of(1L, 50))); 
        session.getTotalScoreByUserId().put(1L, 100);
        // resolve again, given adjusted scores
        resolve.invoke(gameService, "g-91-once", game, null);

        // last round's score stays 50
        assertEquals(50, session.getUserScoresPerRound().get(2).get(1L));
        // total stays 100, does not get rewritten
        assertEquals(100, session.getTotalScoreByUserId().get(1L));
        assertTrue(Boolean.TRUE.equals(session.getHundredReductionAppliedByUserId().get(1L)));
    }


    // reshuffle when draw pile is empty
    @Test
    void moveDrawFromDrawPile_emptyPile_triggersReshuffle() {
        Game game = new Game();
        game.setId("g-reshuffle");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setDrawPile(new ArrayList<>()); // Empty pile triggers reshuffle logic
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);

        User user = new User();
        user.setId(1L);
        user.setToken("testToken");

        // Create a card that WILL be in the draw pile after reshuffle
        Card cardAfterReshuffle = new Card();
        cardAfterReshuffle.setCode("2H");
        cardAfterReshuffle.setValue(2);

        // Mock behavior: 
        // 1. First call to findById returns empty draw pile game
        // 2. We mock the reshuffle (you can verify it was called if you want)
        // 3. We simulate the "reload" by having the second call return a game with cards
        Mockito.when(gameRepository.findById("g-reshuffle")).thenAnswer(new org.mockito.stubbing.Answer<Optional<Game>>() {
            private int count = 0;
            public Optional<Game> answer(org.mockito.invocation.InvocationOnMock invocation) {
                if (count++ == 0) return Optional.of(game); // First call (empty)
                game.setDrawPile(new ArrayList<>(List.of(cardAfterReshuffle))); // Simulate reshuffle result
                return Optional.of(game); // Second call (filled)
            }
        });
        Mockito.when(userRepository.findByToken("testToken")).thenReturn(user);

        gameService.moveDrawFromDrawPile("g-reshuffle", "testToken");

        // Verify the card was drawn and set as the current drawn card
        assertNotNull(game.getDrawnCard());
        assertEquals("2H", game.getDrawnCard().getCode());
        assertTrue(game.isDrawnFromDeck());
    }

    // swap discard pile only on turn
    @Test
    void moveSwapWithDiscard_notCurrentPlayer_throwsForbidden() {
        Game game = new Game();
        game.setId("g-turn-check");
        game.setCurrentPlayerId(1L); // It is Player 1's turn
    
        User player2 = new User();
        player2.setId(2L);
        Mockito.when(userRepository.findByToken("token-p2")).thenReturn(player2);
        Mockito.when(gameRepository.findById("g-turn-check")).thenReturn(Optional.of(game));

        // Player 2 tries to move
        assertThrows(ResponseStatusException.class, 
            () -> gameService.moveSwapWithDiscardPile("g-turn-check", "token-p2", 0));
    }

    // abilities only tringger from draw pile
    @Test
    void moveSwapWithDiscard_abilityCard_doesNotTriggerAbility() {
        String gameId = "g1";
        String userToken = "token-123";
        Long userId = 1L;

        // 1. Setup User: Token must match and ID must match the Game's current player
        User user = new User();
        user.setId(userId);
        user.setToken(userToken);
        Mockito.when(userRepository.findByToken(userToken)).thenReturn(user);

        // 2. Setup Game: CurrentPlayerId must match the User's ID
        Game game = new Game();
        game.setId(gameId);
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(userId);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(userId, 2L)));
        game.setDrawnCard(null); // Satisfy Guard 2

        // Create a 7 (Ability Card)
        Card seven = new Card();
        seven.setCode("7H");
        seven.setValue(7);

        // Setup piles: Discard pile needs a card to satisfy Guard 3
        game.setDiscardPile(new ArrayList<>(List.of(seven)));
    
        // Setup player hand
        List<Card> hand = new ArrayList<>(List.of(new Card()));
        game.setPlayerHands(new HashMap<>(Map.of(userId, hand)));

        // 3. Mock Repositories
        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any())).thenReturn(game);

        // 4. Execute
        gameService.moveSwapWithDiscardPile(gameId, userToken, 0);

        // 5. Verify: Even though a '7' was put on the discard pile, 
        // status should NOT change to a PEEK state because swaps don't trigger abilities.
        assertEquals(GameStatus.ROUND_ACTIVE, game.getStatus());
    }

    // test that initial peek timer transitions to ROUND_ACTIVE state and assigns a random player as starter
    @Test
    public void startGame_peekingTimerCompletes_transitionsToRoundActiveAndPicksRandomStarter() {

        when(gameSettings.getMinPlayers()).thenReturn(2);
        when(gameSettings.getMaxPlayers()).thenReturn(4);
        when(gameSettings.getStarterCardsPerPlayer()).thenReturn(4);
        when(gameSettings.getInitialPeekSeconds()).thenReturn(5L); // The 5-second timer
        when(gameSettings.getTurnSeconds()).thenReturn(30L); // Needed for the turn timer

        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> {
            Game g = inv.getArgument(0);
            if (g.getId() == null) g.setId("test-game-id");
            return g;
        });

        List<Runnable> scheduledTasks = new ArrayList<>();
        ScheduledFuture<?> mockFuture = Mockito.mock(ScheduledFuture.class);

        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS))).thenAnswer(inv -> {
            scheduledTasks.add(inv.getArgument(0));
            return mockFuture;
        });

        List<Long> playerIds = List.of(1L, 2L, 3L);
        Game startedGame = gameService.startGame(playerIds);

        assertEquals(GameStatus.INTRO, startedGame.getStatus(), "Game should start in INTRO");
        assertTrue(scheduledTasks.size() >= 1, "The intro timer should be scheduled");

        when(gameRepository.findById("test-game-id")).thenReturn(Optional.of(startedGame));
        
        // Intro timer completion transitions into INITIAL_PEEK and schedules the peek timer.
        scheduledTasks.get(0).run();
        assertEquals(GameStatus.INITIAL_PEEK, startedGame.getStatus(), "Game should transition to INITIAL_PEEK after intro");
        assertTrue(scheduledTasks.size() >= 2, "The peeking timer should be scheduled after intro");

        // Initial peek timer completion transitions into ROUND_ACTIVE and assigns a starter.
        scheduledTasks.get(1).run();
        assertEquals(GameStatus.ROUND_ACTIVE, startedGame.getStatus(), "Game should transition to ROUND_ACTIVE");
        
        assertTrue(playerIds.contains(startedGame.getCurrentPlayerId()), "A random player should be assigned the first turn");
        
    }

    // test whether opponents get null for drawn card to prevent cheating
    @Test
    public void getDrawnCard_opponentRequests_returnsNull() {
        String snooperToken = "player2-token";
        User opponent = new User(); 
        opponent.setId(2L); 
        opponent.setToken(snooperToken);
        
        Game game = new Game();
        game.setId("g-drawn-card-snoop");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L)));
        game.setCurrentPlayerId(1L);

        Card drawnCard = new Card();
        drawnCard.setCode("AS");
        game.setDrawnCard(drawnCard);

        when(userRepository.findByToken(snooperToken)).thenReturn(opponent);
        when(gameRepository.findById("g-drawn-card-snoop")).thenReturn(Optional.of(game));

        Card result = gameService.getDrawnCard("g-drawn-card-snoop", snooperToken);

        assertNull(result, "Opponents should receive null to prevent cheating");
    }

    // #89: REST drawn card matches websocket — any participant sees it after CABO_REVEAL, not only current player
    @Test
    public void getDrawnCard_caboReveal_nonCurrentPlayerSeesDrawnCard() {
        String token2 = "p2-token";
        User player2 = new User();
        player2.setId(2L);
        player2.setToken(token2);

        Game game = new Game();
        game.setId("g-drawn-reveal");
        game.setStatus(GameStatus.CABO_REVEAL);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L)));
        game.setCurrentPlayerId(1L);
        Card drawn = new Card();
        drawn.setCode("KD");
        drawn.setValue(13);
        game.setDrawnCard(drawn);

        when(userRepository.findByToken(token2)).thenReturn(player2);
        when(gameRepository.findById("g-drawn-reveal")).thenReturn(Optional.of(game));

        Card result = gameService.getDrawnCard("g-drawn-reveal", token2);
        assertNotNull(result);
        assertEquals("KD", result.getCode());
    }

    // #89: non-participants never receive drawn card via REST, even during reveal
    @Test
    public void getDrawnCard_caboReveal_notParticipant_returnsNull() {
        String notParticipantToken = "not-participant-token";
        User notParticipant = new User();
        notParticipant.setId(99L);
        notParticipant.setToken(notParticipantToken);

        Game game = new Game();
        game.setId("g-drawn-not-participant");
        game.setStatus(GameStatus.CABO_REVEAL);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L)));
        game.setCurrentPlayerId(1L);
        Card drawn = new Card();
        drawn.setCode("AC");
        drawn.setValue(13);
        game.setDrawnCard(drawn);

        when(userRepository.findByToken(notParticipantToken)).thenReturn(notParticipant);
        when(gameRepository.findById("g-drawn-not-participant")).thenReturn(Optional.of(game));

        assertNull(gameService.getDrawnCard("g-drawn-not-participant", notParticipantToken));
    }

    // test that if a player times out without drawing, the game auto-draws a card for them and discards it, then advances the turn
    @Test
    public void executeTimoutMove_playerHasNotDrawnCard_autoDrawsAndDiscards() {

        Game game = new Game();
        game.setId("g-timeout-nodraw");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);
        game.setDrawnCard(null); // No card drawn yet

        Card topDraw = new Card(); topDraw.setCode("2H");
        game.setDrawPile(new ArrayList<>(List.of(topDraw)));
        game.setDiscardPile(new ArrayList<>());

        when(gameRepository.findById("g-timeout-nodraw")).thenReturn(Optional.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        gameService.executeTimoutMove("g-timeout-nodraw", 1L);

        assertEquals(0, game.getDrawPile().size(), "The card should be removed from the draw pile");
        assertEquals(1, game.getDiscardPile().size(), "The card should be added to the discard pile");
        
        Card discardedCard = game.getDiscardPile().get(0);
        assertEquals("2H", discardedCard.getCode(), "The discarded card should be the one from the draw pile");
        assertTrue(discardedCard.getVisibility(), "The discarded card must be face-up (visible)");
        
        assertNull(game.getDrawnCard(), "The drawn card slot should be empty again");

        assertEquals(2L, game.getCurrentPlayerId(), "Turn should advance to Player 2");
    }

    // tests that swapping a drawn card with a card in hand properly discards the swapped-out card face-up and hides the new card in hand, then advances the turn
    @Test
    public void moveSwapDrawnCard_validIndex_swapsCardsAndDiscardsFaceUp() {
        String token = "player1-token";
        User player1 = new User();
        player1.setId(1L);
        player1.setToken(token);

        Game game = new Game();
        game.setId("g-swap-drawn");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);

        Card handCard0 = new Card(); handCard0.setCode("2H");
        Card handCard1 = new Card(); handCard1.setCode("3C"); 
        List<Card> hand = new ArrayList<>(Arrays.asList(handCard0, handCard1));
        game.setPlayerHands(new HashMap<>(Map.of(1L, hand)));

        Card drawnCard = new Card(); drawnCard.setCode("AS");
        game.setDrawnCard(drawnCard);
        game.setDrawnFromDeck(true);

        game.setDiscardPile(new ArrayList<>());

        when(userRepository.findByToken(token)).thenReturn(player1);
        when(gameRepository.findById("g-swap-drawn")).thenReturn(Optional.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        gameService.moveSwapDrawnCard("g-swap-drawn", token, 1);

        List<Card> updatedHand = game.getPlayerHands().get(1L);
        assertEquals(2, updatedHand.size());
        assertEquals("AS", updatedHand.get(1).getCode(), "The drawn Ace of Spades should now be at index 1");
        assertFalse(updatedHand.get(1).getVisibility(), "The new card in hand must be face-down");

        assertEquals(1, game.getDiscardPile().size(), "The discard pile should now have 1 card");
        Card discardedCard = game.getDiscardPile().get(0);
        
        assertEquals("3C", discardedCard.getCode(), "The discarded card should be the 3 of Clubs removed from the hand");
        assertTrue(discardedCard.getVisibility(), "CRITICAL: The discarded card MUST be face-up!");

        assertNull(game.getDrawnCard(), "The drawn card slot should be cleared after the swap");
        assertEquals(2L, game.getCurrentPlayerId(), "Turn should successfully advance to Player 2");
    }

    @Test
    public void saveRoundScores_noSession_returnsFalseGracefully() {
        // 1. Arrange: Create a fake game
        Game mockGame = new Game();
        mockGame.setId("test-game-id");
        mockGame.setOrderedPlayerIds(List.of(1L, 2L));

        Mockito.when(gameRepository.findById("test-game-id")).thenReturn(Optional.of(mockGame));
        
        // Mock the lobby service to return null (simulating the bug environment)
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(any())).thenReturn(null);

        Map<Long, Integer> dummyScores = Map.of(1L, 10, 2L, 50);

        // 2. Act: Run your pipeline
        boolean isGameOver = gameService.saveRoundScoreAndCheckGameOver("test-game-id", dummyScores);

        // 3. Assert: It should return false and NOT throw an exception!
        assertFalse(isGameOver);
        
        // Verify the database save was never called
        Mockito.verify(sessionRepository, Mockito.never()).save(any());
    }

    @Test
    void moveDrawFromDrawPile_spectatorInterference_throwsForbidden() {
        // Setup: A game where it is currently Player 1's turn
        Game game = new Game();
        game.setId("test-game-123");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L); 

        // Setup: A spectator (User ID 99) who is trying to interfere
        User spectator = new User();
        spectator.setId(99L); // CRITICAL: This does NOT match the currentPlayerId
        spectator.setToken("spectatorToken");

        Mockito.when(gameRepository.findById("test-game-123")).thenReturn(Optional.of(game));
        Mockito.when(userRepository.findByToken("spectatorToken")).thenReturn(spectator);

        // Action & Assertion: Try to draw a card as the spectator, expect a 403 FORBIDDEN
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.moveDrawFromDrawPile("test-game-123", "spectatorToken");
        });

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Not your turn", exception.getReason());
    }

    @Test
    void moveCardToDiscardPile_spectatorInterference_throwsForbidden() {
        // Setup: A game where it is currently Player 1's turn
        Game game = new Game();
        game.setId("test-game-123");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L); 
        
        // Give the game a drawn card so it doesn't fail for the wrong reason
        Card drawnCard = new Card();
        drawnCard.setValue(5);
        game.setDrawnCard(drawnCard);

        // Setup: A spectator (User ID 99) who is trying to interfere
        User spectator = new User();
        spectator.setId(99L); // CRITICAL: This does NOT match the currentPlayerId
        spectator.setToken("spectatorToken");

        Mockito.when(gameRepository.findById("test-game-123")).thenReturn(Optional.of(game));
        Mockito.when(userRepository.findByToken("spectatorToken")).thenReturn(spectator);

        // Action & Assertion: Try to discard the card as the spectator, expect a 403 FORBIDDEN
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.moveCardToDiscardPile("test-game-123", "spectatorToken");
        });

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Not your turn", exception.getReason());
        
        // Verify that the game was NEVER saved because the move was rejected
        Mockito.verify(gameRepository, Mockito.never()).save(Mockito.any(Game.class));
    }

    @Test
    void saveRoundScoreAndCheckGameOver_limitsNotReached_returnsFalse() {
        // 1. Mock Game Settings
        Mockito.when(gameSettings.getRoundLimit()).thenReturn(5);
        Mockito.when(gameSettings.getScoreLimit()).thenReturn(100);

        // 2. Setup Game and Session
        Game game = new Game();
        game.setId("test-game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Session session = new Session();
        session.setSessionId("session-123");
        // Simulate 2 rounds played so far (less than the limit of 5)
        session.setUserScoresPerRound(new ArrayList<>(List.of(new HashMap<>(), new HashMap<>())));
        // Simulate scores below 100
        session.setTotalScoreByUserId(new HashMap<>(Map.of(1L, 45, 2L, 60)));

        // 3. Mock Repositories and Services
        Mockito.when(gameRepository.findById("test-game-123")).thenReturn(Optional.of(game));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("session-123");
        Mockito.when(sessionRepository.findBySessionId("session-123")).thenReturn(session);

        // 4. Action
        Map<Long, Integer> newRoundScores = Map.of(1L, 10, 2L, 5);
        boolean isGameOver = gameService.saveRoundScoreAndCheckGameOver("test-game-123", newRoundScores);

        // 5. Assertion
        assertFalse(isGameOver, "Game should not be over yet.");
        assertFalse(session.isEnded(), "Session should not be marked as ended.");
        Mockito.verify(sessionRepository, Mockito.times(1)).save(session);
    }

    @Test
    void saveRoundScoreAndCheckGameOver_scoreLimitReached_returnsTrue() {
        // 1. Mock Game Settings
        Mockito.when(gameSettings.getRoundLimit()).thenReturn(5);
        Mockito.when(gameSettings.getScoreLimit()).thenReturn(100); // Score limit is 100

        // 2. Setup Game and Session
        Game game = new Game();
        game.setId("test-game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Session session = new Session();
        session.setSessionId("session-123");
        session.setUserScoresPerRound(new ArrayList<>());
        
        // Simulate Player 2 being dangerously close to the limit
        session.setTotalScoreByUserId(new HashMap<>(Map.of(1L, 45, 2L, 95)));

        Mockito.when(gameRepository.findById("test-game-123")).thenReturn(Optional.of(game));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("session-123");
        Mockito.when(sessionRepository.findBySessionId("session-123")).thenReturn(session);

        // 3. Action: Player 2 gets 10 points, pushing them to 105 (over the limit!)
        Map<Long, Integer> newRoundScores = Map.of(1L, 5, 2L, 10);
        boolean isGameOver = gameService.saveRoundScoreAndCheckGameOver("test-game-123", newRoundScores);

        // 4. Assertion
        assertTrue(isGameOver, "Game should be over because score limit was reached.");
        assertTrue(session.isEnded(), "Session should be marked as ended.");
    }

    @Test
    void saveRoundScoreAndCheckGameOver_roundLimitReached_returnsTrue() {
        // 1. Mock Game Settings
        Mockito.when(gameSettings.getRoundLimit()).thenReturn(3); // Round limit is 3
        Mockito.when(gameSettings.getScoreLimit()).thenReturn(100);

        // 2. Setup Game and Session
        Game game = new Game();
        game.setId("test-game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Session session = new Session();
        session.setSessionId("session-123");
        
        // Simulate 2 rounds ALREADY played. 
        // When the service runs, it will append the 3rd round, hitting the limit!
        session.setUserScoresPerRound(new ArrayList<>(List.of(new HashMap<>(), new HashMap<>())));
        session.setTotalScoreByUserId(new HashMap<>(Map.of(1L, 10, 2L, 15)));

        Mockito.when(gameRepository.findById("test-game-123")).thenReturn(Optional.of(game));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("session-123");
        Mockito.when(sessionRepository.findBySessionId("session-123")).thenReturn(session);

        // 3. Action: Play the 3rd round
        Map<Long, Integer> newRoundScores = Map.of(1L, 5, 2L, 5);
        boolean isGameOver = gameService.saveRoundScoreAndCheckGameOver("test-game-123", newRoundScores);

        // 4. Assertion
        assertTrue(isGameOver, "Game should be over because round limit was reached.");
        assertTrue(session.isEnded(), "Session should be marked as ended.");
    }

    @Test
    void saveRoundScore_firstRound_initializesAndSavesCorrectly() {
        // 1. Mock Game Settings (to prevent null pointers during the game-over check)
        Mockito.when(gameSettings.getRoundLimit()).thenReturn(5);
        Mockito.when(gameSettings.getScoreLimit()).thenReturn(100);

        // 2. Setup Game and a brand new Session (null scores)
        Game game = new Game();
        game.setId("test-game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Session session = new Session();
        session.setSessionId("session-123");
        // A new session has null for these fields initially
        session.setUserScoresPerRound(null); 
        session.setTotalScoreByUserId(null);

        Mockito.when(gameRepository.findById("test-game-123")).thenReturn(Optional.of(game));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("session-123");
        Mockito.when(sessionRepository.findBySessionId("session-123")).thenReturn(session);

        // 3. Action: Save the first round scores
        Map<Long, Integer> round1Scores = Map.of(1L, 10, 2L, 5);
        gameService.saveRoundScoreAndCheckGameOver("test-game-123", round1Scores);

        // 4. Assertion: Verify the DB save was called and the data is correct
        Mockito.verify(sessionRepository, Mockito.times(1)).save(session);
        
        // Verify history list was created and populated
        assertNotNull(session.getUserScoresPerRound());
        assertEquals(1, session.getUserScoresPerRound().size());
        assertEquals(10, session.getUserScoresPerRound().get(0).get(1L));

        // Verify total scores were initialized
        assertNotNull(session.getTotalScoreByUserId());
        assertEquals(10, session.getTotalScoreByUserId().get(1L));
        assertEquals(5, session.getTotalScoreByUserId().get(2L));
    }

    @Test
    void saveRoundScore_subsequentRounds_accumulatesTotalsAndSaves() {
        // 1. Mock Game Settings
        Mockito.when(gameSettings.getRoundLimit()).thenReturn(5);
        Mockito.when(gameSettings.getScoreLimit()).thenReturn(100);

        // 2. Setup Game and an existing Session (has previous scores)
        Game game = new Game();
        game.setId("test-game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Session session = new Session();
        session.setSessionId("session-123");
        
        // Simulate Round 1 already existing in the database
        List<Map<Long, Integer>> previousRounds = new ArrayList<>();
        previousRounds.add(Map.of(1L, 20, 2L, 30));
        session.setUserScoresPerRound(previousRounds);
        session.setTotalScoreByUserId(new HashMap<>(Map.of(1L, 20, 2L, 30)));

        Mockito.when(gameRepository.findById("test-game-123")).thenReturn(Optional.of(game));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("session-123");
        Mockito.when(sessionRepository.findBySessionId("session-123")).thenReturn(session);

        // 3. Action: Save Round 2 scores
        Map<Long, Integer> round2Scores = Map.of(1L, 15, 2L, 5);
        gameService.saveRoundScoreAndCheckGameOver("test-game-123", round2Scores);

        // 4. Assertion: Verify the DB save was called and the math is correct
        Mockito.verify(sessionRepository, Mockito.times(1)).save(session);
        
        // Verify history list now has 2 entries
        assertEquals(2, session.getUserScoresPerRound().size());
        
        // Verify the math! Player 1 (20 + 15 = 35), Player 2 (30 + 5 = 35)
        assertEquals(35, session.getTotalScoreByUserId().get(1L));
        assertEquals(35, session.getTotalScoreByUserId().get(2L));
    }


    @Test
    void saveRoundScore_subsequentRound_appendsAndMergesCorrectly() {
        // 1. Setup Game and an existing Session with Round 1 already played
        Game game = new Game();
        game.setId("test-game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Session session = new Session();
        session.setSessionId("session-123");
        
        // Setup Round 1 history
        List<Map<Long, Integer>> history = new ArrayList<>();
        history.add(new HashMap<>(Map.of(1L, 10, 2L, 5)));
        session.setUserScoresPerRound(history);
        
        // Setup Round 1 totals
        session.setTotalScoreByUserId(new HashMap<>(Map.of(1L, 10, 2L, 5)));

        Mockito.when(gameSettings.getRoundLimit()).thenReturn(5);
        Mockito.when(gameSettings.getScoreLimit()).thenReturn(100);
        Mockito.when(gameRepository.findById("test-game-123")).thenReturn(Optional.of(game));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("session-123");
        Mockito.when(sessionRepository.findBySessionId("session-123")).thenReturn(session);

        // 2. Action: Save round 2 scores
        Map<Long, Integer> round2Scores = Map.of(1L, 15, 2L, 0); // Player 1 gets 15, Player 2 gets 0
        gameService.saveRoundScoreAndCheckGameOver("test-game-123", round2Scores);

        // 3. Assertion: Verify history is appended and totals are merged (added)
        assertEquals(2, session.getUserScoresPerRound().size(), "Should now have 2 rounds recorded");
        assertEquals(15, session.getUserScoresPerRound().get(1).get(1L), "Round 2 history for Player 1 should be 15");

        // The totals should be: Player 1 (10 + 15 = 25), Player 2 (5 + 0 = 5)
        assertEquals(25, session.getTotalScoreByUserId().get(1L), "Player 1 total should be mathematically merged to 25");
        assertEquals(5, session.getTotalScoreByUserId().get(2L), "Player 2 total should remain 5");

        Mockito.verify(sessionRepository, Mockito.times(1)).save(session);
    }

    @Test
    void saveRoundScore_missingSessionId_abortsWithoutCrashing() {
        // 1. Setup Game
        Game game = new Game();
        game.setId("test-game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));

        Mockito.when(gameRepository.findById("test-game-123")).thenReturn(Optional.of(game));
        // Simulate the LobbyService failing to find an active session
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn(null);

        // 2. Action: Attempt to save scores
        Map<Long, Integer> roundScores = Map.of(1L, 10, 2L, 5);
        boolean isGameOver = gameService.saveRoundScoreAndCheckGameOver("test-game-123", roundScores);

        // 3. Assertion: It should safely abort, return false, and NEVER call the database save
        assertFalse(isGameOver, "Should return false if session is missing");
        Mockito.verify(sessionRepository, Mockito.never()).save(Mockito.any(Session.class));
    }

}
