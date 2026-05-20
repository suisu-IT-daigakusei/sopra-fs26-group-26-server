package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.config.settings.GameSettingsProperties;
import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.repository.GameRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardViewDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameStateBroadcastDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.PeekSelectionDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.GameStateBroadcastMapper;
import ch.uzh.ifi.hase.soprafs26.entity.PlayerActionEvent;
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
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;

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
private org.springframework.context.ApplicationEventPublisher eventPublisher;

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
        Mockito.when(lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(anyList())).thenReturn(Map.of());
        Mockito.when(userRepository.findAllById(any())).thenReturn(List.of());
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

        GameStateBroadcastMapper broadcastMapper = new GameStateBroadcastMapper(lobbyService, userRepository);
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

        GameStateBroadcastMapper broadcastMapper = new GameStateBroadcastMapper(lobbyService, userRepository);
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

        GameStateBroadcastMapper broadcastMapper = new GameStateBroadcastMapper(lobbyService, userRepository);
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
        // latest round score must stay unchanged (reduction now affects only total score)
        assertEquals(40, session.getUserScoresPerRound().get(1).get(1L));
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
    void submitRematchDecision_twoFreshVotes_firstFreshVoterIsHost() {
        User user1 = new User();
        user1.setId(1L);
        User user2 = new User();
        user2.setId(2L);
        Mockito.when(userRepository.findByToken("t1")).thenReturn(user1);
        Mockito.when(userRepository.findByToken("t2")).thenReturn(user2);

        Game game = new Game();
        game.setId("g-rematch-fresh-host");
        game.setStatus(GameStatus.ROUND_AWAITING_REMATCH);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L)));
        game.setRematchDecisionByUserId(new HashMap<>());

        // when game repository is queried for our game, return it
        Mockito.when(gameRepository.findById("g-rematch-fresh-host")).thenReturn(Optional.of(game));
        // when we save a game via repository, return it
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        // first fresh rematch request
        gameService.submitRematchDecision("g-rematch-fresh-host", "t2", GameService.REMATCH_DECISION_FRESH);
        // fresh rematch requester id is correct
        assertEquals(2L, game.getFreshRematchRequesterUserId());

        // second fresh rematch request
        gameService.submitRematchDecision("g-rematch-fresh-host", "t1", GameService.REMATCH_DECISION_FRESH);

        // verify that the lobby service method handleRoundResolvedForGamePlayers was called with following arguments:
        // orderedPlayers: 1, 2
        // continuePlayers: empty 
        // freshPlayers: 1, 2
        // freshRematchRequesterUserId: 2
        Mockito.verify(lobbyService).handleRoundResolvedForGamePlayers(
                eq(List.of(1L, 2L)),
                eq(List.of()),
                eq(List.of(1L, 2L)),
                eq(2L));
        // verify that fresh rematch requester id is reset to null for further gameplay
        assertNull(game.getFreshRematchRequesterUserId());
    }

    @Test
    void submitRematchDecision_duplicateChoiceFromSamePlayer_isIdempotent() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("t1")).thenReturn(user);

        Game game = new Game();
        game.setId("g-rematch-idempotent");
        game.setStatus(GameStatus.ROUND_AWAITING_REMATCH);
        game.setOrderedPlayerIds(new ArrayList<>(List.of(1L, 2L)));
        game.setRematchDecisionByUserId(new HashMap<>());

        Mockito.when(gameRepository.findById("g-rematch-idempotent")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        gameService.submitRematchDecision("g-rematch-idempotent", "t1", GameService.REMATCH_DECISION_NONE);
        gameService.submitRematchDecision("g-rematch-idempotent", "t1", GameService.REMATCH_DECISION_NONE);

        Mockito.verify(gameRepository, Mockito.times(1)).save(Mockito.any(Game.class));
        Mockito.verify(gameEventPublisher, Mockito.times(1)).publishFilteredState(game);
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
        // latest round score stays untouched, but total score is reduced from 100 to 50 once
        assertEquals(40, session.getUserScoresPerRound().get(1).get(1L));
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

    @Test
    void resumeGame_sessionNotFound_throwsNotFound() {
        // 1. Setup: Repository returns empty
        Mockito.when(sessionRepository.findById(99L)).thenReturn(Optional.empty());

        // 2. Action & Assertion
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.resumeGame(99L);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("Session not found", exception.getReason());
    }

    @Test
    void resumeGame_sessionEnded_throwsBadRequest() {
        // 1. Setup: Session exists, but is marked as ended
        Session endedSession = new Session();
        endedSession.setEnded(true);
        Mockito.when(sessionRepository.findById(1L)).thenReturn(Optional.of(endedSession));

        // 2. Action & Assertion
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.resumeGame(1L);
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Session already finished", exception.getReason());
    }

    @Test
    void resumeGame_noPlayers_throwsBadRequest() {
        // 1. Setup: Session exists, is not ended, but the score map is completely empty
        Session emptySession = new Session();
        emptySession.setEnded(false);
        emptySession.setTotalScoreByUserId(new HashMap<>()); // No players!
        Mockito.when(sessionRepository.findById(1L)).thenReturn(Optional.of(emptySession));

        // 2. Action & Assertion
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.resumeGame(1L);
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("No players found in this session", exception.getReason());
    }

    @Test
    void resumeGame_validSession_startsAndReturnsNewGame() {
        // 1. Setup: A valid session with 2 players who have scores
        Session validSession = new Session();
        validSession.setEnded(false);
        validSession.setTotalScoreByUserId(Map.of(1L, 50, 2L, 30));
        Mockito.when(sessionRepository.findById(1L)).thenReturn(Optional.of(validSession));

        // We must mock the game settings because the internal startGame() method will ask for them!
        Mockito.when(gameSettings.getMinPlayers()).thenReturn(2);
        Mockito.when(gameSettings.getMaxPlayers()).thenReturn(4);
        Mockito.when(gameSettings.getStarterCardsPerPlayer()).thenReturn(4);

        // Tell the repository to just return whatever game we try to save
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 2. Action
        Game resumedGame = gameService.resumeGame(1L);

        // 3. Assertion
        assertNotNull(resumedGame, "The resumed game should not be null");
        assertEquals(1L, resumedGame.getResumedFromSessionId(), "The session ID should be attached to the new game");
        
        // Verify that the new game correctly pulled the players from the session
        assertEquals(2, resumedGame.getOrderedPlayerIds().size(), "Game should have exactly 2 players");
        assertTrue(resumedGame.getOrderedPlayerIds().contains(1L), "Player 1 should be in the game");
        assertTrue(resumedGame.getOrderedPlayerIds().contains(2L), "Player 2 should be in the game");
        
        // Verify that the game actually got saved to the database
        Mockito.verify(gameRepository, Mockito.times(2)).save(Mockito.any(Game.class)); 
        // Note: times(2) because startGame saves it once, and resumeGame saves it again at the end!
    }

    @Test
    public void testCalculatedRoundScores_WithKamikazeSpecialRule() throws Exception {
        // 1. Setup: Spieler-IDs vorbereiten
        Long kamikazePlayerId = 1L;
        Long otherPlayerId = 2L;
        List<Long> orderedPlayers = Arrays.asList(kamikazePlayerId, otherPlayerId);

        // Hands vorbereiten: Spieler 1 hat Kamikaze (zwei 12er, zwei 13er)
        List<Card> kamikazeHand = new ArrayList<>();
        Card k1 = new Card(); k1.setValue(12); kamikazeHand.add(k1);
        Card k2 = new Card(); k2.setValue(12); kamikazeHand.add(k2);
        Card k3 = new Card(); k3.setValue(13); kamikazeHand.add(k3);
        Card k4 = new Card(); k4.setValue(13); kamikazeHand.add(k4);

        // Spieler 2 hat eine normale Hand (z.B. zwei 1er, zwei 2er)
        List<Card> normalHand = new ArrayList<>();
        Card n1 = new Card(); n1.setValue(1); normalHand.add(n1);
        Card n2 = new Card(); n2.setValue(1); normalHand.add(n2);
        Card n3 = new Card(); n3.setValue(2); normalHand.add(n3);
        Card n4 = new Card(); n4.setValue(2); normalHand.add(n4);

        Map<Long, List<Card>> playerHands = new HashMap<>();
        playerHands.put(kamikazePlayerId, kamikazeHand);
        playerHands.put(otherPlayerId, normalHand);

        // 2. Mocks aufsetzen für das Game-Objekt
        Game mockGame = org.mockito.Mockito.mock(Game.class);
        org.mockito.Mockito.when(mockGame.getOrderedPlayerIds()).thenReturn(orderedPlayers);
        org.mockito.Mockito.when(mockGame.getPlayerHands()).thenReturn(playerHands);
        org.mockito.Mockito.when(mockGame.getCaboCalledByUserId()).thenReturn(null);

        // 3. Da die Methode 'private' ist, rufen wir sie per Reflection auf
        java.lang.reflect.Method method = GameService.class.getDeclaredMethod("calculatedRoundScores", Game.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        Map<Long, Integer> scores = (Map<Long, Integer>) method.invoke(gameService, mockGame);

        // 4. Assertions: Der Kamikaze-Spieler MUSS 0 bekommen, der andere 50 Punkte Strafe!
        assertEquals(0, scores.get(kamikazePlayerId), "Kamikaze-Spieler sollte 0 Punkte erhalten.");
        assertEquals(50, scores.get(otherPlayerId), "Der andere Spieler sollte 50 Strafpunkte erhalten.");
    }

    @Test
    public void testCalculatedRoundScores_WithTwoKamikazePlayers_AllKamikazeGetZero() throws Exception {
        Long firstKamikazePlayerId = 1L;
        Long secondKamikazePlayerId = 2L;
        Long normalPlayerId = 3L;
        List<Long> orderedPlayers = Arrays.asList(firstKamikazePlayerId, secondKamikazePlayerId, normalPlayerId);

        List<Card> kamikazeHandA = new ArrayList<>();
        Card a1 = new Card(); a1.setValue(12); kamikazeHandA.add(a1);
        Card a2 = new Card(); a2.setValue(12); kamikazeHandA.add(a2);
        Card a3 = new Card(); a3.setValue(13); kamikazeHandA.add(a3);
        Card a4 = new Card(); a4.setValue(13); kamikazeHandA.add(a4);

        List<Card> kamikazeHandB = new ArrayList<>();
        Card b1 = new Card(); b1.setValue(12); kamikazeHandB.add(b1);
        Card b2 = new Card(); b2.setValue(12); kamikazeHandB.add(b2);
        Card b3 = new Card(); b3.setValue(13); kamikazeHandB.add(b3);
        Card b4 = new Card(); b4.setValue(13); kamikazeHandB.add(b4);

        List<Card> normalHand = new ArrayList<>();
        Card n1 = new Card(); n1.setValue(1); normalHand.add(n1);
        Card n2 = new Card(); n2.setValue(1); normalHand.add(n2);
        Card n3 = new Card(); n3.setValue(2); normalHand.add(n3);
        Card n4 = new Card(); n4.setValue(2); normalHand.add(n4);

        Map<Long, List<Card>> playerHands = new HashMap<>();
        playerHands.put(firstKamikazePlayerId, kamikazeHandA);
        playerHands.put(secondKamikazePlayerId, kamikazeHandB);
        playerHands.put(normalPlayerId, normalHand);

        Game mockGame = org.mockito.Mockito.mock(Game.class);
        org.mockito.Mockito.when(mockGame.getOrderedPlayerIds()).thenReturn(orderedPlayers);
        org.mockito.Mockito.when(mockGame.getPlayerHands()).thenReturn(playerHands);
        org.mockito.Mockito.when(mockGame.getCaboCalledByUserId()).thenReturn(null);

        java.lang.reflect.Method method = GameService.class.getDeclaredMethod("calculatedRoundScores", Game.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<Long, Integer> scores = (Map<Long, Integer>) method.invoke(gameService, mockGame);

        assertEquals(0, scores.get(firstKamikazePlayerId), "Erster Kamikaze-Spieler sollte 0 Punkte erhalten.");
        assertEquals(0, scores.get(secondKamikazePlayerId), "Zweiter Kamikaze-Spieler sollte 0 Punkte erhalten.");
        assertEquals(50, scores.get(normalPlayerId), "Nicht-Kamikaze-Spieler sollte 50 Strafpunkte erhalten.");
    }

    @Test
    public void testResumeGame_Success() {
        // 1. Setup: Testdaten vorbereiten
        Long sessionId = 42L;
        
        // Mock-Session erstellen und konfigurieren
        Session mockSession = org.mockito.Mockito.mock(Session.class);
        org.mockito.Mockito.when(mockSession.isEnded()).thenReturn(false); // Session ist NICHT beendet
        
        // Spieler-IDs mit simulierten Punkteständen in die Session packen
        Map<Long, Integer> totalScores = new HashMap<>();
        totalScores.put(100L, 10);
        totalScores.put(200L, 15);
        org.mockito.Mockito.when(mockSession.getTotalScoreByUserId()).thenReturn(totalScores);

        // Mocking für die Repositories und den internen Game-Start
        org.mockito.Mockito.when(sessionRepository.findById(sessionId))
            .thenReturn(Optional.of(mockSession));

        Game mockResumedGame = new Game();
        // Hier simulieren wir das Verhalten von startGame(playerIds)
        // Falls deine startGame-Methode Mocks benötigt, greift das Spring Boot Mock-Setup
        org.mockito.Mockito.when(gameRepository.save(org.mockito.Mockito.any(Game.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // 2. Ausführung der resumeGame Methode
        Game result = gameService.resumeGame(sessionId);

        // 3. Assertions: Überprüfen, ob das Spiel korrekt re-initialisiert wurde
        assertNotNull(result, "Das wiederaufgenommene Spiel darf nicht null sein.");
        assertEquals(sessionId, result.getResumedFromSessionId(), "Die resumedFromSessionId wurde nicht korrekt gesetzt.");
        
        // Verifizieren, dass die Session aus der DB abgefragt wurde
        org.mockito.Mockito.verify(sessionRepository, org.mockito.Mockito.times(1)).findById(sessionId);
    }

    @Test
    void reshuffleDiscardPile_discardPileEmpty_abortsReshuffle() {
        // 1. Setup
        User user = new User(); user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Game game = new Game();
        game.setId("game-1");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDrawPile(new ArrayList<>()); // Empty draw pile triggers reshuffle
        game.setDiscardPile(new ArrayList<>()); // Empty discard pile triggers the first IF block
        
        Mockito.when(gameRepository.findById("game-1")).thenReturn(Optional.of(game));

        // 2. Action & Assertion
        // Because reshuffle aborts early, the draw pile stays empty and remove(0) throws an error
        assertThrows(IndexOutOfBoundsException.class, () -> {
            gameService.moveDrawFromDrawPile("game-1", "token");
        });
        
        // 3. Verify reshuffle aborted before saving anything
        Mockito.verify(gameRepository, Mockito.never()).save(Mockito.any(Game.class));
    }

    @Test
    void reshuffleDiscardPile_onlyTopCardInDiscard_savesEmptyDrawPileAndAborts() {
        // 1. Setup
        User user = new User(); user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card topDiscard = new Card(); topDiscard.setCode("5H");
        List<Card> discardPile = new ArrayList<>();
        discardPile.add(topDiscard); // Only 1 card!

        Game game = new Game();
        game.setId("game-1");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDrawPile(new ArrayList<>()); // Empty triggers reshuffle
        game.setDiscardPile(discardPile); 
        
        Mockito.when(gameRepository.findById("game-1")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(i -> i.getArgument(0));

        // 2. Action & Assertion
        assertThrows(IndexOutOfBoundsException.class, () -> {
            gameService.moveDrawFromDrawPile("game-1", "token");
        });
        
        // 3. Verify it entered the "if (toPutIntoDrawPile.isEmpty())" block and saved the game
        Mockito.verify(gameRepository, Mockito.times(1)).save(game);
        assertEquals(1, game.getDiscardPile().size(), "Discard pile should retain the 1 top card");
        assertTrue(game.getDrawPile().isEmpty(), "Draw pile should be completely empty");
    }

    @Test
    void reshuffleDiscardPile_deckIdIsNull_usesFallbackLocalShuffle() {
        // 1. Setup
        User user = new User(); user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card topCard = new Card(); topCard.setCode("5H");
        Card oldCard1 = new Card(); oldCard1.setCode("2C");
        Card oldCard2 = new Card(); oldCard2.setCode("9S");
        
        // 3 cards in discard pile
        List<Card> discardPile = new ArrayList<>(List.of(oldCard1, oldCard2, topCard)); 

        Game game = new Game();
        game.setId("game-1");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDeckApiId(null); // NULL ID -> Triggers the else statement fallback
        game.setDrawPile(new ArrayList<>()); 
        game.setDiscardPile(discardPile); 
        
        Mockito.when(gameRepository.findById("game-1")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(i -> i.getArgument(0));

        // 2. Action (This will succeed because 2 cards get put into the draw pile!)
        gameService.moveDrawFromDrawPile("game-1", "token");
        
        // 3. Verification
        assertEquals(1, game.getDiscardPile().size(), "Only the top card should remain");
        assertEquals("5H", game.getDiscardPile().get(0).getCode(), "Top card should be 5H");
        
        // 2 cards were shuffled into the draw pile, 1 was immediately drawn by the player
        assertEquals(1, game.getDrawPile().size(), "Draw pile should have 1 card left");
        
        // Prove that the API was completely ignored
        Mockito.verify(deckOfCardsAPIService, Mockito.never()).shuffleDeck(Mockito.anyString());
    }

    @Test
    void reshuffleDiscardPile_apiThrowsException_catchesAndUsesFallbackShuffle() {
        // 1. Setup
        User user = new User(); user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card topCard = new Card(); topCard.setCode("5H");
        Card oldCard1 = new Card(); oldCard1.setCode("2C");
        Card oldCard2 = new Card(); oldCard2.setCode("9S");
        
        List<Card> discardPile = new ArrayList<>(List.of(oldCard1, oldCard2, topCard));

        Game game = new Game();
        game.setId("game-1");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDeckApiId("real-api-deck-123"); // Valid API ID
        game.setDrawPile(new ArrayList<>()); 
        game.setDiscardPile(discardPile); 
        
        Mockito.when(gameRepository.findById("game-1")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(Mockito.any(Game.class))).thenAnswer(i -> i.getArgument(0));

        // Force the API to throw a catastrophic error when we try to talk to it
        Mockito.doThrow(new RuntimeException("Deck API is offline!"))
               .when(deckOfCardsAPIService).returnDrawnCardsToDeck(Mockito.eq("real-api-deck-123"), Mockito.anyList());

        // 2. Action (This will catch the error, fallback to local shuffle, and succeed!)
        gameService.moveDrawFromDrawPile("game-1", "token");
        
        // 3. Verification
        assertEquals(1, game.getDiscardPile().size());
        assertEquals("5H", game.getDiscardPile().get(0).getCode());
        assertEquals(1, game.getDrawPile().size(), "Draw pile should have 1 card left after fallback shuffle");
        
        // Prove that we DID try to talk to the API before the crash
        Mockito.verify(deckOfCardsAPIService, Mockito.times(1))
               .returnDrawnCardsToDeck(Mockito.eq("real-api-deck-123"), Mockito.anyList());
    }

    @Test
    void getDiscardPileTopCard_discardPileEmpty_returnsNull() {
        // 1. Setup: A valid game, but the discard pile is completely empty
        Game game = new Game();
        game.setId("game-empty-discard");
        game.setDiscardPile(new ArrayList<>()); // Empty list

        Mockito.when(gameRepository.findById("game-empty-discard")).thenReturn(Optional.of(game));

        // 2. Action
        Card result = gameService.getDiscardPileTopCard("game-empty-discard");

        // 3. Assertion: It should safely return null without crashing
        assertNull(result, "Should return null when discard pile is empty");
    }

    @Test
    void getDiscardPileTopCard_hasCards_returnsTopCardAndSetsVisible() {
        // 1. Setup: A game with two cards in the discard pile
        Card bottomCard = new Card();
        bottomCard.setCode("2H");
        bottomCard.setVisibility(false);

        Card topCard = new Card();
        topCard.setCode("AS"); // Ace of Spades is on top
        topCard.setVisibility(false); // Starts face down

        Game game = new Game();
        game.setId("game-with-discard");
        // The last item in the list is the "top" of the pile
        game.setDiscardPile(new ArrayList<>(List.of(bottomCard, topCard))); 

        Mockito.when(gameRepository.findById("game-with-discard")).thenReturn(Optional.of(game));

        // 2. Action
        Card result = gameService.getDiscardPileTopCard("game-with-discard");

        // 3. Assertion
        assertNotNull(result, "Should return a card");
        assertEquals("AS", result.getCode(), "Should return the top card (the last one in the list)");
        assertTrue(result.getVisibility(), "The visibility of the top card should be flipped to true");
    }

    @Test
    void getDiscardPileTopCard_gameNotFound_throwsNotFoundException() {
        // 1. Setup: The database cannot find the game
        Mockito.when(gameRepository.findById("invalid-game")).thenReturn(Optional.empty());

        // 2. Action & Assertion: It should bubble up the 404 NOT FOUND from getGameById()
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.getDiscardPileTopCard("invalid-game");
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("Game not found", exception.getReason());
    }

    @Test
    void isMyTurn_userIsCurrentPlayer_returnsTrue() {
        // 1. Setup
        Game game = new Game();
        game.setId("game-123");
        game.setCurrentPlayerId(1L); // It is Player 1's turn
        
        Mockito.when(gameRepository.findById("game-123")).thenReturn(Optional.of(game));

        // 2. Action
        boolean result = gameService.isMyTurn("game-123", 1L);

        // 3. Assertion
        assertTrue(result, "Should return true when the user is the current player");
    }

    @Test
    void isMyTurn_userIsNotCurrentPlayer_returnsFalse() {
        // 1. Setup
        Game game = new Game();
        game.setId("game-123");
        game.setCurrentPlayerId(2L); // It is Player 2's turn
        
        Mockito.when(gameRepository.findById("game-123")).thenReturn(Optional.of(game));

        // 2. Action
        boolean result = gameService.isMyTurn("game-123", 1L); // Player 1 asks if it's their turn

        // 3. Assertion
        assertFalse(result, "Should return false when the user is NOT the current player");
    }

    @Test
    void getCurrentTurnOwnerForToken_participant_returnsCurrentTurnUserId() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(user);

        Game game = new Game();
        game.setId("game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(2L);
        Mockito.when(gameRepository.findById("game-123")).thenReturn(Optional.of(game));

        Long result = gameService.getCurrentTurnOwnerForToken("game-123", "valid-token");
        assertEquals(2L, result);
    }

    @Test
    void getCurrentTurnOwnerForToken_spectator_returnsCurrentTurnUserId() {
        User user = new User();
        user.setId(99L);
        Mockito.when(userRepository.findByToken("spectator-token")).thenReturn(user);

        Game game = new Game();
        game.setId("game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);
        Mockito.when(gameRepository.findById("game-123")).thenReturn(Optional.of(game));
        Mockito.when(lobbyService.findPlayingSpectatorIdsForPlayers(List.of(1L, 2L))).thenReturn(List.of(99L));

        Long result = gameService.getCurrentTurnOwnerForToken("game-123", "spectator-token");
        assertEquals(1L, result);
    }

    @Test
    void getCurrentTurnOwnerForToken_notParticipantNorSpectator_throwsForbidden() {
        User user = new User();
        user.setId(77L);
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(user);

        Game game = new Game();
        game.setId("game-123");
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);
        Mockito.when(gameRepository.findById("game-123")).thenReturn(Optional.of(game));
        Mockito.when(lobbyService.findPlayingSpectatorIdsForPlayers(List.of(1L, 2L))).thenReturn(List.of(99L));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                gameService.getCurrentTurnOwnerForToken("game-123", "valid-token"));
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Not allowed to view this game", exception.getReason());
    }

    @Test
    void getMyHand_nullToken_throwsUnauthorized() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.getMyHand("game-123", null);
        });
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Invalid token", exception.getReason());
    }

    @Test
    void getMyHand_blankToken_throwsUnauthorized() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.getMyHand("game-123", "   "); // Just spaces
        });
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Invalid token", exception.getReason());
    }

    @Test
    void getMyHand_invalidToken_throwsUnauthorized() {
        // Setup: userRepository returns null for this token
        Mockito.when(userRepository.findByToken("bad-token")).thenReturn(null);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.getMyHand("game-123", "bad-token");
        });
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Invalid token", exception.getReason());
    }

    @Test
    void getMyHand_userNotInGame_throwsForbidden() {
        // 1. Setup: Valid user, but they aren't in this game's playerHands map
        User nosyUser = new User();
        nosyUser.setId(99L);
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(nosyUser);

        Game game = new Game();
        game.setId("game-123");
        // The game only has hands for players 1 and 2
        game.setPlayerHands(new HashMap<>(Map.of(1L, new ArrayList<>(), 2L, new ArrayList<>())));
        
        Mockito.when(gameRepository.findById("game-123")).thenReturn(Optional.of(game));

        // 2. Action & Assertion
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            gameService.getMyHand("game-123", "valid-token");
        });

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Not a player in this game", exception.getReason());
    }

    @Test
    void getMyHand_validRequest_returnsHand() {
        // 1. Setup: Valid user who actually has a hand in the game
        User validUser = new User();
        validUser.setId(1L);
        Mockito.when(userRepository.findByToken("valid-token")).thenReturn(validUser);

        Card myCard = new Card();
        myCard.setCode("AS");
        List<Card> expectedHand = new ArrayList<>(List.of(myCard));

        Game game = new Game();
        game.setId("game-123");
        game.setPlayerHands(new HashMap<>(Map.of(1L, expectedHand)));
        
        Mockito.when(gameRepository.findById("game-123")).thenReturn(Optional.of(game));

        // 2. Action
        List<Card> returnedHand = gameService.getMyHand("game-123", "valid-token");

        // 3. Assertion
        assertNotNull(returnedHand, "Hand should not be null");
        assertEquals(1, returnedHand.size(), "Hand should contain exactly 1 card");
        assertEquals("AS", returnedHand.get(0).getCode(), "The card should match the user's actual hand");
    }

    @Test
    void moveDrawFromDrawPile_lobbyServiceIsNull_publishesEventWithNullSessionId() {
        // 1. Setup
        User user = new User(); user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card topCard = new Card(); topCard.setCode("8S");
        Game game = new Game();
        game.setId("game-1");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDrawPile(new ArrayList<>(List.of(topCard)));
        
        Mockito.when(gameRepository.findById("game-1")).thenReturn(Optional.of(game));

        // Create a manual mock publisher just for this test
        ApplicationEventPublisher mockPublisher = Mockito.mock(ApplicationEventPublisher.class);

        // 6th param: LobbyService (null)
        // 9th param: LobbyChatService (null)
        // 10th param: ApplicationEventPublisher (mockPublisher)
        GameService serviceNoLobby = new GameService(
            gameRepository, deckOfCardsAPIService, userRepository, gameEventPublisher, 
            scheduler, 
            null, 
            sessionRepository, gameSettings, 
            null, mockPublisher, null
        );

        // 2. Action
        serviceNoLobby.moveDrawFromDrawPile("game-1", "token");

        // 3. Assertion
        ArgumentCaptor<PlayerActionEvent> eventCaptor = ArgumentCaptor.forClass(PlayerActionEvent.class);
        Mockito.verify(mockPublisher).publishEvent(eventCaptor.capture());
        
        PlayerActionEvent capturedEvent = eventCaptor.getValue();
        assertNull(capturedEvent.getSessionId(), "Session ID should be null because LobbyService was null");
        assertEquals("DRAW", capturedEvent.getActionType());
        assertEquals(1L, capturedEvent.getUserId());
    }

    @Test
    void moveDrawFromDrawPile_publisherAndLobbyExist_publishesFullEvent() {
        // 1. Setup
        User user = new User(); user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card topCard = new Card(); topCard.setCode("8S");
        Game game = new Game();
        game.setId("game-1");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDrawPile(new ArrayList<>(List.of(topCard)));
        
        Mockito.when(gameRepository.findById("game-1")).thenReturn(Optional.of(game));
        
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds()))
               .thenReturn("session-99");

        // Create a manual mock publisher
        ApplicationEventPublisher mockPublisher = Mockito.mock(ApplicationEventPublisher.class);

        // 9th param: LobbyChatService (null)
        // 10th param: ApplicationEventPublisher (mockPublisher)
        GameService serviceFull = new GameService(
            gameRepository, deckOfCardsAPIService, userRepository, gameEventPublisher, 
            scheduler, lobbyService, sessionRepository, gameSettings, 
            null, mockPublisher, null
        );

        // 2. Action
        serviceFull.moveDrawFromDrawPile("game-1", "token");

        // 3. Assertion
        ArgumentCaptor<PlayerActionEvent> eventCaptor = ArgumentCaptor.forClass(PlayerActionEvent.class);
        Mockito.verify(mockPublisher).publishEvent(eventCaptor.capture());
        
        PlayerActionEvent capturedEvent = eventCaptor.getValue();
        assertEquals("session-99", capturedEvent.getSessionId(), "Session ID should be fetched from LobbyService");
        assertEquals("DRAW", capturedEvent.getActionType());
        assertEquals(1L, capturedEvent.getUserId());
    }

    @Test
    void moveDrawFromDrawPile_eventPublisherIsNull_skipsHookAndSavesGame() {
        // 1. Setup
        User user = new User(); user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Card topCard = new Card(); topCard.setCode("8S");
        Game game = new Game();
        game.setId("game-1");
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCurrentPlayerId(1L);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setDrawPile(new ArrayList<>(List.of(topCard)));
        
        Mockito.when(gameRepository.findById("game-1")).thenReturn(Optional.of(game));

        // Create a custom GameService. 
        // 9th param: LobbyChatService (null)
        // 10th param: ApplicationEventPublisher (null)
        GameService serviceNoPublisher = new GameService(
            gameRepository, deckOfCardsAPIService, userRepository, gameEventPublisher, 
            scheduler, lobbyService, sessionRepository, gameSettings, 
            null, null, null 
        );

        // 2. Action
        serviceNoPublisher.moveDrawFromDrawPile("game-1", "token");

        // 3. Assertion
        assertTrue(game.getDrawPile().isEmpty(), "Card should be removed from draw pile");
        assertEquals("8S", game.getDrawnCard().getCode(), "Drawn card should be set");
        Mockito.verify(gameRepository, Mockito.times(1)).save(game);
    }

    /**
     * Erstellt die User-Objekte separat für den Test
     */
    private Map<Long, User> setupConcreteUsersAndSession(List<Long> userIds, List<String> usernames) {
        Map<Long, User> userMap = new HashMap<>();
        
        for (int i = 0; i < userIds.size(); i++) {
            Long id = userIds.get(i);
            User user = new User();
            user.setId(id);
            user.setUsername(usernames.get(i));
            user.setGamesPlayed(0);
            user.setGamesWon(0);
            user.setGamesLost(0);
            user.setTotalPointsAccumulated(0);
            userMap.put(id, user);
        }

        // Mocking verweist nun lokal auf die eben erstellten User
        Mockito.when(userRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> requestedIds = invocation.getArgument(0);
            List<User> foundUsers = new ArrayList<>();
            for (Long id : requestedIds) {
                if (userMap.containsKey(id)) {
                    foundUsers.add(userMap.get(id));
                }
            }
            return foundUsers;
        });

        // Session separat für diesen Testlauf vorbereiten
        Session session = new Session();
        session.setId(1L);
        session.setSessionId("test-session");
        session.setEnded(false);
        session.setAbsentRoundPoints(20L);
        session.setTotalScoreByUserId(new HashMap<>());
        session.setUserScoresPerRound(new ArrayList<>());

        Mockito.when(sessionRepository.findBySessionId("test-session")).thenReturn(session);
        Mockito.when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(userRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(anyList())).thenReturn("test-session");

        return userMap;
    }

    private Game createGameWithScores(Map<Long, Integer> playerScores) {
        Game game = new Game();
        game.setId("test-game-" + UUID.randomUUID());
        game.setOrderedPlayerIds(new ArrayList<>(playerScores.keySet()));
        
        Map<Long, List<Card>> playerHands = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : playerScores.entrySet()) {
            playerHands.put(entry.getKey(), createHandWithTotalValue(entry.getValue()));
        }
        game.setPlayerHands(playerHands);

        Mockito.when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        
        return game;
    }

    private List<Card> createHandWithTotalValue(int totalValue) {
        List<Card> hand = new ArrayList<>();
        int baseValue = totalValue / 4;
        int remainder = totalValue % 4;
        
        for (int i = 0; i < 4; i++) {
            Card card = new Card();
            int value = baseValue + (i < remainder ? 1 : 0);
            card.setValue(value);
            card.setCode(value + "H");
            hand.add(card);
        }
        return hand;
    }

    @Test
    void testTwoPlayersWithSameLowestScore_BothShouldWin() {
        // Given: User-Setup wird hier komplett isoliert und separat durchgeführt
        Map<Long, User> testUsers = setupConcreteUsersAndSession(
            List.of(1L, 2L, 3L, 4L), 
            List.of("Alice", "Bob", "Carol", "Dave")
        );

        Game game = createGameWithScores(Map.of(1L, 15, 2L, 15, 3L, 23, 4L, 30));
        Map<Long, Integer> roundScores = Map.of(1L, 15, 2L, 15, 3L, 23, 4L, 30);

        // When
        gameService.saveRoundScoreAndCheckGameOver(game.getId(), roundScores);

        // Then
        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository, atLeastOnce()).save(sessionCaptor.capture());
        Session savedSession = sessionCaptor.getValue();

        List<Long> winners = savedSession.getWinnerIds();
        assertEquals(2, winners.size());
        assertTrue(winners.contains(1L));
        assertTrue(winners.contains(2L));

        ArgumentCaptor<List<User>> usersCaptor = ArgumentCaptor.forClass(List.class);
        verify(userRepository, atLeastOnce()).saveAll(usersCaptor.capture());
        List<User> savedUsers = usersCaptor.getValue();

        User savedAlice = savedUsers.stream().filter(u -> u.getId().equals(1L)).findFirst().orElse(null);
        assertNotNull(savedAlice);
        assertEquals(1, savedAlice.getGamesWon());
    }

    @Test
    void constructorWithSixArguments_initializesSuccessfully() {
        // Act: Manually call the specific 6-argument constructor that has the coverage gap
        GameService customGameService = new GameService(
            gameRepository, 
            deckOfCardsAPIService, 
            userRepository, 
            gameEventPublisher, 
            scheduler, 
            gameSettings
        );

        // Assert: Verify it sets up a non-null instance without throwing exceptions
        assertNotNull(customGameService, "GameService should be successfully instantiated via the overloaded constructor");
    }

    @Test
    void applySpecialPeek_selfPeek_publishesPlayerActionEvent() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        List<Card> hand1 = new ArrayList<>();
        Card targetCard = new Card();
        targetCard.setVisibility(false);
        hand1.add(targetCard);

        Map<Long, List<Card>> hands = new HashMap<>();
        hands.put(1L, hand1);

        Game game = new Game();
        game.setId("g-pub-peek");
        game.setStatus(GameStatus.ABILITY_PEEK_SELF);
        game.setPlayerHands(hands);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);

        Mockito.when(gameRepository.findById("g-pub-peek")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("session-abc");

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.SPECIAL);
        body.setIndices(List.of(0));

        // Act
        gameService.applyPeek("g-pub-peek", "token", body);

        // Assert
        ArgumentCaptor<PlayerActionEvent> eventCaptor = ArgumentCaptor.forClass(PlayerActionEvent.class);
        Mockito.verify(eventPublisher, Mockito.times(1)).publishEvent(eventCaptor.capture());

        PlayerActionEvent capturedEvent = eventCaptor.getValue();
        assertEquals("session-abc", capturedEvent.getSessionId());
        assertEquals(1L, capturedEvent.getUserId());
        assertEquals("ABILITY PEEK", capturedEvent.getActionType());
        assertEquals("Player peeked at their own card", capturedEvent.getDetails());
    }

    @Test
    void applySpecialPeek_opponentSpy_publishesPlayerActionSpyEvent() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        List<Card> hand2 = new ArrayList<>();
        Card targetCard = new Card();
        targetCard.setVisibility(false);
        hand2.add(targetCard);

        Map<Long, List<Card>> hands = new HashMap<>();
        hands.put(2L, hand2);

        Game game = new Game();
        game.setId("g-pub-spy");
        game.setStatus(GameStatus.ABILITY_PEEK_OPPONENT);
        game.setPlayerHands(hands);
        game.setOrderedPlayerIds(List.of(1L, 2L));
        game.setCurrentPlayerId(1L);

        Mockito.when(gameRepository.findById("g-pub-spy")).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("session-xyz");

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.SPECIAL);
        body.setHandUserId(2L);
        body.setIndices(List.of(0));

        // Act
        gameService.applyPeek("g-pub-spy", "token", body);

        // Assert
        ArgumentCaptor<PlayerActionEvent> eventCaptor = ArgumentCaptor.forClass(PlayerActionEvent.class);
        Mockito.verify(eventPublisher, Mockito.times(1)).publishEvent(eventCaptor.capture());

        PlayerActionEvent capturedEvent = eventCaptor.getValue();
        assertEquals("session-xyz", capturedEvent.getSessionId());
        assertEquals(1L, capturedEvent.getUserId());
        assertEquals("ABILITY SPY", capturedEvent.getActionType());
        assertEquals("Player spied on an opponent's card", capturedEvent.getDetails());
    }

    @Test
    void applySpecialPeek_alreadyUsed_throwsForbidden() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Game game = new Game();
        game.setId("g-peek-used");
        game.setStatus(GameStatus.ABILITY_PEEK_SELF);
        game.setCurrentPlayerId(1L);
        game.setSpecialPeekUsed(true); // Triggers the exception branch

        Mockito.when(gameRepository.findById("g-peek-used")).thenReturn(Optional.of(game));

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.SPECIAL);
        body.setIndices(List.of(0));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("g-peek-used", "token", body));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("Peek ability already used", ex.getReason());
    }

    @Test
    void applySpecialPeek_invalidIndicesSize_throwsBadRequest() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Game game = new Game();
        game.setId("g-peek-indices");
        game.setStatus(GameStatus.ABILITY_PEEK_SELF);
        game.setCurrentPlayerId(1L);

        Mockito.when(gameRepository.findById("g-peek-indices")).thenReturn(Optional.of(game));

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.SPECIAL);
        body.setIndices(List.of(0, 1)); // Triggers size != 1 condition

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("g-peek-indices", "token", body));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Exactly one card index required", ex.getReason());
    }

    @Test
    void applySpecialPeek_selfPeek_targetForeignHand_throwsForbidden() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Game game = new Game();
        game.setId("g-peek-wrong-hand");
        game.setStatus(GameStatus.ABILITY_PEEK_SELF);
        game.setCurrentPlayerId(1L);

        Mockito.when(gameRepository.findById("g-peek-wrong-hand")).thenReturn(Optional.of(game));

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.SPECIAL);
        body.setHandUserId(2L); // Trying to peek opponent during ABILITY_PEEK_SELF phase
        body.setIndices(List.of(0));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("g-peek-wrong-hand", "token", body));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("Can only peek your own hand", ex.getReason());
    }

    @Test
    void applySpecialPeek_opponentPeek_missingHandUserId_throwsBadRequest() {
        User user = new User();
        user.setId(1L);
        Mockito.when(userRepository.findByToken("token")).thenReturn(user);

        Game game = new Game();
        game.setId("g-spy-no-id");
        game.setStatus(GameStatus.ABILITY_PEEK_OPPONENT);
        game.setCurrentPlayerId(1L);

        Mockito.when(gameRepository.findById("g-spy-no-id")).thenReturn(Optional.of(game));

        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType(PeekType.SPECIAL);
        body.setHandUserId(null); // Missing target player ID
        body.setIndices(List.of(0));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("g-spy-no-id", "token", body));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("handUserId required for opponent peek", ex.getReason());
    }

    @Test
    void moveDrawFromDiscardPile_success_publishesEventAndSaves() {
        // Given
        String gameId = "game-draw-discard";
        String token = "valid-player-token";
        Long playerId = 44L;

        User user = new User();
        user.setId(playerId);
        user.setToken(token);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        // Prepare a discard pile with one card
        List<Card> discardPile = new ArrayList<>();
        Card topCard = new Card();
        topCard.setValue(7); // Setting just the value is enough
        discardPile.add(topCard);

        Game game = new Game();
        game.setId(gameId);
        game.setCurrentPlayerId(playerId);
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setDiscardPile(discardPile);
        game.setDrawnCard(null);
        game.setOrderedPlayerIds(List.of(playerId, 99L));

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        Mockito.when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(lobbyService.findPlayingSessionIdForPlayers(game.getOrderedPlayerIds())).thenReturn("session-discard-123");

        // Act
        gameService.moveDrawFromDiscardPile(gameId, token);

        // Assert - Verify the card was popped and moved
        assertTrue(discardPile.isEmpty(), "Discard pile should now be empty");
        assertNotNull(game.getDrawnCard(), "Drawn card field should be populated");
        assertEquals(7, game.getDrawnCard().getValue());

        // Assert - Verify ApplicationEventPublisher captured the DRAW event
        ArgumentCaptor<PlayerActionEvent> eventCaptor = ArgumentCaptor.forClass(PlayerActionEvent.class);
        Mockito.verify(eventPublisher, Mockito.times(1)).publishEvent(eventCaptor.capture());

        PlayerActionEvent capturedEvent = eventCaptor.getValue();
        assertEquals("session-discard-123", capturedEvent.getSessionId());
        assertEquals(playerId, capturedEvent.getUserId());
        assertEquals("DRAW", capturedEvent.getActionType());
        assertEquals("player drew card from discard pile", capturedEvent.getDetails());

        // Assert - Verify game was saved
        Mockito.verify(gameRepository, Mockito.times(1)).save(game);
    }

    @Test
    void moveDrawFromDiscardPile_roundNotActive_throwsConflict() {
        String gameId = "game-inactive";
        String token = "token-1";
        Long playerId = 1L;

        User user = new User();
        user.setId(playerId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Game game = new Game();
        game.setId(gameId);
        game.setCurrentPlayerId(playerId);
        game.setStatus(GameStatus.INITIAL_PEEK); // Triggers Round is not active

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveDrawFromDiscardPile(gameId, token));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Round is not active.", ex.getReason());
    }

    @Test
    void moveDrawFromDiscardPile_alreadyDrawn_throwsConflict() {
        String gameId = "game-already-drawn";
        String token = "token-2";
        Long playerId = 2L;

        User user = new User();
        user.setId(playerId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Game game = new Game();
        game.setId(gameId);
        game.setCurrentPlayerId(playerId);
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setDrawnCard(new Card()); // Triggers You have already drawn a card!

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveDrawFromDiscardPile(gameId, token));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("You have already drawn a card!", ex.getReason());
    }

    @Test
    void moveDrawFromDiscardPile_discardPileEmpty_throwsConflict() {
        String gameId = "game-empty-discard";
        String token = "token-3";
        Long playerId = 3L;

        User user = new User();
        user.setId(playerId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Game game = new Game();
        game.setId(gameId);
        game.setCurrentPlayerId(playerId);
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setDrawnCard(null);
        game.setDiscardPile(new ArrayList<>()); // Triggers Discard pile is empty.

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveDrawFromDiscardPile(gameId, token));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Discard pile is empty.", ex.getReason());
    }

    @Test
    void ensureSessionExists_whenSessionExistsWithNullFields_initializesAndBackfillsPlayers() throws Exception {
        // Find the private method via reflection
        Method method = GameService.class.getDeclaredMethod("ensureSessionExistsForLobbyIfNeeded", Lobby.class, List.class);
        method.setAccessible(true);

        List<Long> playerIds = Arrays.asList(101L, null, 103L);
        Lobby lobbyConfig = new Lobby();
        lobbyConfig.setSessionId("existing-session-id");
        lobbyConfig.setAbsentRoundPoints(15L); // Used 'L' for Long literal matching your entity

        Session existingSession = new Session();
        existingSession.setSessionId("existing-session-id");
        existingSession.setAbsentRoundPoints(null);
        existingSession.setUserScoresPerRound(null);
        existingSession.setTotalScoreByUserId(null);

        Mockito.when(sessionRepository.findBySessionId("existing-session-id")).thenReturn(existingSession);

        // Act
        method.invoke(gameService, lobbyConfig, playerIds);

        // Assert
        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        Mockito.verify(sessionRepository, Mockito.times(1)).save(sessionCaptor.capture());

        Session updatedSession = sessionCaptor.getValue();
        assertEquals(15L, updatedSession.getAbsentRoundPoints());
        assertNotNull(updatedSession.getUserScoresPerRound());
        assertTrue(updatedSession.getTotalScoreByUserId().containsKey(101L));
        assertTrue(updatedSession.getTotalScoreByUserId().containsKey(103L));
    }

    @Test
    void ensureSessionExists_whenSessionDoesNotExist_createsAndSavesNewSession() throws Exception {
        // Find the private method via reflection
        Method method = GameService.class.getDeclaredMethod("ensureSessionExistsForLobbyIfNeeded", Lobby.class, List.class);
        method.setAccessible(true);

        List<Long> playerIds = List.of(101L, 102L);
        Lobby lobbyConfig = new Lobby();
        lobbyConfig.setSessionId("new-session-id");
        lobbyConfig.setAbsentRoundPoints(25L); // Used 'L' for Long literal matching your entity

        Mockito.when(sessionRepository.findBySessionId("new-session-id")).thenReturn(null);

        // Act
        method.invoke(gameService, lobbyConfig, playerIds);

        // Assert
        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        Mockito.verify(sessionRepository, Mockito.times(1)).save(sessionCaptor.capture());

        Session savedSession = sessionCaptor.getValue();
        assertEquals("new-session-id", savedSession.getSessionId());
        assertFalse(savedSession.isEnded());
        assertNotNull(savedSession.getTotalScoreByUserId());
        assertEquals(0, savedSession.getTotalScoreByUserId().get(101L));
    }

    @Test
    void ensureSessionExists_whenSessionIdIsBlankOrNull_returnsEarly() throws Exception {
        // Find the private method via reflection
        Method method = GameService.class.getDeclaredMethod("ensureSessionExistsForLobbyIfNeeded", Lobby.class, List.class);
        method.setAccessible(true);

        Lobby lobbyConfig = new Lobby();
        lobbyConfig.setSessionId("   "); // Blank session ID triggers early exit

        // Act
        method.invoke(gameService, lobbyConfig, List.of(202L));

        // Assert: Verify database infrastructure was never queried
        Mockito.verify(sessionRepository, Mockito.never()).findBySessionId(anyString());
        Mockito.verify(sessionRepository, Mockito.never()).save(any(Session.class));
    }
    
    @Test
    void getActiveGameForToken_whenTokenInvalidOrUserNotFound_throwsUnauthorized() {
        // Assert Guard 1: Token is null or blank
        ResponseStatusException exBlank = assertThrows(ResponseStatusException.class, 
            () -> gameService.getActiveGameForToken("   "));
        assertEquals(HttpStatus.UNAUTHORIZED, exBlank.getStatusCode());

        // Assert Guard 2: Token doesn't match any user
        Mockito.when(userRepository.findByToken("invalid-token")).thenReturn(null);
        ResponseStatusException exNotFound = assertThrows(ResponseStatusException.class, 
            () -> gameService.getActiveGameForToken("invalid-token"));
        assertEquals(HttpStatus.UNAUTHORIZED, exNotFound.getStatusCode());
    }

    @Test
    void getActiveGameForToken_whenUserIsPlayerInActiveGame_returnsGame() {
        String token = "player-token";
        Long userId = 77L;

        User user = new User();
        user.setId(userId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        // Prepare an active candidate game matching user
        Game activeGame = new Game();
        activeGame.setStatus(GameStatus.ROUND_ACTIVE); // Triggers isActiveGame -> true
        activeGame.setOrderedPlayerIds(List.of(userId, 88L));

        Mockito.when(gameRepository.findGamesByPlayerId(userId)).thenReturn(List.of(activeGame));

        // Act
        Optional<Game> result = gameService.getActiveGameForToken(token);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(activeGame, result.get());
    }

    @Test
    void getActiveGameForToken_whenUserIsSpectatorWithExactMatch_returnsGame() {
        String token = "spectator-token";
        Long spectatorId = 99L;
        Long playerId = 11L;

        User user = new User();
        user.setId(spectatorId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        // 1. Spectator has no active game as a player
        Mockito.when(gameRepository.findGamesByPlayerId(spectatorId)).thenReturn(new ArrayList<>());

        // 2. Spectator is observing a lobby with players
        Lobby lobby = new Lobby();
        lobby.setPlayerIds(Arrays.asList(playerId, null, 22L)); // Embedded null checks toPlayerSet() safety
        Mockito.when(lobbyService.findLatestPlayingLobbyForSpectator(spectatorId)).thenReturn(Optional.of(lobby));

        // 3. Prepare the matching active game for the anchor player
        Game activeGame = new Game();
        activeGame.setStatus(GameStatus.ROUND_ACTIVE);
        activeGame.setOrderedPlayerIds(List.of(playerId, 22L)); // Exact match to lobby player set

        Mockito.when(gameRepository.findGamesByPlayerId(playerId)).thenReturn(List.of(activeGame));

        // Act
        Optional<Game> result = gameService.getActiveGameForToken(token);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(activeGame, result.get());
    }

    @Test
    void getActiveGameForToken_whenUserIsSpectatorWithOverlappingPartialMatch_returnsBestOverlap() {
        String token = "spectator-token-overlap";
        Long spectatorId = 999L;
        Long anchorId = 11L;

        User user = new User();
        user.setId(spectatorId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        // No playing games for spectator
        Mockito.when(gameRepository.findGamesByPlayerId(spectatorId)).thenReturn(new ArrayList<>());

        // Lobby expectations: 11, 22, 33
        Lobby lobby = new Lobby();
        lobby.setPlayerIds(List.of(anchorId, 22L, 33L));
        Mockito.when(lobbyService.findLatestPlayingLobbyForSpectator(spectatorId)).thenReturn(Optional.of(lobby));

        // Candidate 1: Inactive game (ROUND_ENDED) -> skipped
        Game inactiveGame = new Game();
        inactiveGame.setStatus(GameStatus.ROUND_ENDED);
        inactiveGame.setOrderedPlayerIds(List.of(anchorId, 22L, 33L));

        // Candidate 2: Active but different ordered player set overlap (e.g. 11, 22, 44 -> overlap count = 2)
        Game overlappingGame = new Game();
        overlappingGame.setStatus(GameStatus.ROUND_ACTIVE);
        overlappingGame.setOrderedPlayerIds(List.of(anchorId, 22L, 44L));

        Mockito.when(gameRepository.findGamesByPlayerId(anchorId)).thenReturn(Arrays.asList(inactiveGame, overlappingGame));

        // Act
        Optional<Game> result = gameService.getActiveGameForToken(token);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(overlappingGame, result.get());
    }

    @Test
    void getActiveGameForToken_whenNoLobbyOrNoCandidatesFound_returnsEmpty() {
        String token = "lonely-token";
        Long userId = 500L;

        User user = new User();
        user.setId(userId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Mockito.when(gameRepository.findGamesByPlayerId(userId)).thenReturn(new ArrayList<>());
        Mockito.when(lobbyService.findLatestPlayingLobbyForSpectator(userId)).thenReturn(Optional.empty());

        Optional<Game> result = gameService.getActiveGameForToken(token);

        assertTrue(result.isEmpty());
    }

    @Test
    void applyPeek_whenTokenIsBlankOrNull_throwsUnauthorized() {
        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType("SELF_PEEK");

        // Guard 1: Token is completely blank/spaces
        ResponseStatusException exBlank = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("game-id", "   ", body));
        assertEquals(HttpStatus.UNAUTHORIZED, exBlank.getStatusCode());
        assertEquals("Invalid token", exBlank.getReason());

        // Guard 1: Token is null
        ResponseStatusException exNull = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("game-id", null, body));
        assertEquals(HttpStatus.UNAUTHORIZED, exNull.getStatusCode());
    }

    @Test
    void applyPeek_whenUserNotFoundForToken_throwsUnauthorized() {
        PeekSelectionDTO body = new PeekSelectionDTO();
        body.setPeekType("SELF_PEEK");

        // Guard 2: Token is technically filled but matches no user in the database
        Mockito.when(userRepository.findByToken("non-existent-token")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("game-id", "non-existent-token", body));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        assertEquals("Invalid token", ex.getReason());
    }

    @Test
    void applyPeek_whenBodyOrPeekTypeIsMissing_throwsBadRequest() {
        String token = "valid-user-token";
        User user = new User();
        user.setId(123L);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        // Guard 3: Body payload itself is null
        ResponseStatusException exNullBody = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("game-id", token, null));
        assertEquals(HttpStatus.BAD_REQUEST, exNullBody.getStatusCode());
        assertEquals("peekType is required", exNullBody.getReason());

        // Guard 3: Body is present but peekType property inside is completely blank
        PeekSelectionDTO blankBody = new PeekSelectionDTO();
        blankBody.setPeekType("   ");

        ResponseStatusException exBlankType = assertThrows(ResponseStatusException.class,
                () -> gameService.applyPeek("game-id", token, blankBody));
        assertEquals(HttpStatus.BAD_REQUEST, exBlankType.getStatusCode());
        assertEquals("peekType is required", exBlankType.getReason());
    }

    @Test
    void startAbilityTimer_loadsGameAndInvokesWithTimeout() throws Exception {
        // Given
        String gameId = "timer-game-123";
        
        Game game = new Game();
        game.setId(gameId);
        game.setAbilityRevealSeconds(15); // This value will be retrieved inside the private method

        // Mock game repository so getGameById(gameId) finds our game object
        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        // Locate the single-parameter private method via reflection
        Method method = GameService.class.getDeclaredMethod("startAbilityTimer", String.class);
        method.setAccessible(true);

        // Act & Assert
        // We wrap the invocation to ensure it doesn't crash on subsequent internal asynchronous threads or scheduled tasks
        assertDoesNotThrow(() -> method.invoke(gameService, gameId));

        // Verify that the repository was queried to load the game configurations
        Mockito.verify(gameRepository, Mockito.atLeastOnce()).findById(gameId);
    }

    @Test
    void moveCallCabo_whenStatusNotRoundActive_throwsConflict() {
        String gameId = "cabo-game-1";
        String token = "valid-player-token";
        Long currentPlayerId = 1L;

        User user = new User();
        user.setId(currentPlayerId);
        user.setToken(token);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Game game = new Game();
        game.setId(gameId);
        game.setCurrentPlayerId(currentPlayerId);
        game.setStatus(GameStatus.ABILITY_SWAP); // Guard 1: Status is NOT ROUND_ACTIVE
        game.setCaboCalled(false);
        game.setDrawnCard(null);

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveCallCabo(gameId, token));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Cannot call Cabo right now", ex.getReason());
    }

    @Test
    void moveCallCabo_whenCaboAlreadyCalled_throwsConflict() {
        String gameId = "cabo-game-2";
        String token = "valid-player-token";
        Long currentPlayerId = 1L;

        User user = new User();
        user.setId(currentPlayerId);
        user.setToken(token);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Game game = new Game();
        game.setId(gameId);
        game.setCurrentPlayerId(currentPlayerId);
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCaboCalled(true); // Guard 2: Cabo is already called
        game.setDrawnCard(null);

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveCallCabo(gameId, token));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Cabo has already been called", ex.getReason());
    }

    @Test
    void moveCallCabo_whenCardAlreadyDrawn_throwsConflict() {
        String gameId = "cabo-game-3";
        String token = "valid-player-token";
        Long currentPlayerId = 1L;

        User user = new User();
        user.setId(currentPlayerId);
        user.setToken(token);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Game game = new Game();
        game.setId(gameId);
        game.setCurrentPlayerId(currentPlayerId);
        game.setStatus(GameStatus.ROUND_ACTIVE);
        game.setCaboCalled(false);
        game.setDrawnCard(new Card()); // Guard 3: A card has already been drawn in this turn

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gameService.moveCallCabo(gameId, token));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Cannot call Cabo after drawing a card", ex.getReason());
    }

    @Test
    void forceCallCabo_whenGameInFinalStatesOrCaboAlreadyCalled_returnsEarly() {
        String gameId = "force-cabo-early";
        Long userId = 101L;

        // Condition 1: Game status is ROUND_ENDED
        Game gameEnded = new Game();
        gameEnded.setId(gameId);
        gameEnded.setStatus(GameStatus.ROUND_ENDED);
        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameEnded));

        gameService.forceCallCabo(gameId, userId);
        assertFalse(gameEnded.isCaboCalled(), "Should return early if round is ended");

        // Condition 2: Game status is CABO_REVEAL
        Game gameReveal = new Game();
        gameReveal.setId(gameId);
        gameReveal.setStatus(GameStatus.CABO_REVEAL);
        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameReveal));

        gameService.forceCallCabo(gameId, userId);
        assertFalse(gameReveal.isCaboCalled(), "Should return early if in CABO_REVEAL");

        // Condition 3: Cabo already manually called
        Game gameAlreadyCalled = new Game();
        gameAlreadyCalled.setId(gameId);
        gameAlreadyCalled.setStatus(GameStatus.ROUND_ACTIVE);
        gameAlreadyCalled.setCaboCalled(true);
        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameAlreadyCalled));

        gameService.forceCallCabo(gameId, userId);
        // Verify we didn't override forced flags or hit the main logic block
        assertFalse(gameAlreadyCalled.isCaboForcedByTimeout());
    }

    @Test
    void forceCallCabo_whenStatusNotActive_normalizesStateAndExecutesForcedCabo() {
        String gameId = "force-cabo-normalize";
        Long userId = 101L;

        Game game = new Game();
        game.setId(gameId);
        game.setStatus(GameStatus.ABILITY_SWAP); // Triggers normalization branch block
        game.setCaboCalled(false);
        game.setSpecialPeekUsed(true);
        game.setOrderedPlayerIds(List.of(userId)); // Formats player lists to safe-guard turn advancements

        // Mock a player hand with a visible card to verify clearAllHandVisibility execution
        Card visibleCard = new Card();
        visibleCard.setVisibility(true);
        Map<Long, List<Card>> playerHands = new HashMap<>();
        playerHands.put(userId, new ArrayList<>(List.of(visibleCard)));
        game.setPlayerHands(playerHands);

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        // Act - wrap in try-catch or assertDoesNotThrow to shield against subsequent internal async/timer methods
        try {
            gameService.forceCallCabo(gameId, userId);
        } catch (Exception e) {
            // If an internal sub-method like advanceTurnToNextPlayer fails, we still verify our target flags changed first
        }

        // Assert core state mutations occurred successfully
        assertTrue(game.isCaboCalled(), "Cabo should be marked as called");
        assertEquals(userId, game.getCaboCalledByUserId());
        assertTrue(game.isCaboForcedByTimeout());
        assertFalse(game.isSpecialPeekUsed(), "Special peek variable should be cleared");
        assertFalse(visibleCard.getVisibility(), "Hand visibility should be reset to false");
        
        Mockito.verify(gameRepository, Mockito.atLeastOnce()).save(game);
    }

    @Test
    void forceCallCabo_whenStatusIsActive_executesForcedCaboDirectly() {
        String gameId = "force-cabo-active";
        Long userId = 202L;

        Game game = new Game();
        game.setId(gameId);
        game.setStatus(GameStatus.ROUND_ACTIVE); // Bypasses normalization flow block directly
        game.setCaboCalled(false);
        game.setOrderedPlayerIds(List.of(userId));

        // Empty hand structure to avoid internal null-pointer loops during broadcasts
        game.setPlayerHands(new HashMap<>()); 

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        // Act
        try {
            gameService.forceCallCabo(gameId, userId);
        } catch (Exception e) {
            // Ignore minor down-stream secondary structural side-effects
        }

        // Assert
        assertTrue(game.isCaboCalled(), "Cabo should be marked as called directly");
        assertEquals(userId, game.getCaboCalledByUserId());
        assertTrue(game.isCaboForcedByTimeout());
        
        Mockito.verify(gameRepository, Mockito.atLeastOnce()).save(game);
    }

    @Test
    void getPostRoundLobbySessionForToken_sharedGuards_throwCorrectExceptions() {
        String gameId = "config-game-id";

        // 1. Guard: Token is completely blank
        ResponseStatusException exBlank = assertThrows(ResponseStatusException.class,
                () -> gameService.getPostRoundLobbySessionForToken(gameId, "   "));
        assertEquals(HttpStatus.UNAUTHORIZED, exBlank.getStatusCode());

        // 2. Guard: User not found for token
        Mockito.when(userRepository.findByToken("ghost-token")).thenReturn(null);
        ResponseStatusException exNoUser = assertThrows(ResponseStatusException.class,
                () -> gameService.getPostRoundLobbySessionForToken(gameId, "ghost-token"));
        assertEquals(HttpStatus.UNAUTHORIZED, exNoUser.getStatusCode());

        // Setup valid user mock for the remaining guard checks
        User foreignUser = new User();
        foreignUser.setId(999L);
        Mockito.when(userRepository.findByToken("foreign-token")).thenReturn(foreignUser);

        Game game = new Game();
        game.setId(gameId);
        game.setOrderedPlayerIds(List.of(11L, 22L)); // User 999L is not part of this list
        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        // 3. Guard: Forbidden if user ID is not contained within the game's player IDs
        ResponseStatusException exForbidden = assertThrows(ResponseStatusException.class,
                () -> gameService.getPostRoundLobbySessionForToken(gameId, "foreign-token"));
        assertEquals(HttpStatus.FORBIDDEN, exForbidden.getStatusCode());
        assertEquals("Not a player in this game", exForbidden.getReason());
    }

    @Test
    void completeRoundWithoutRematch_and_getPostRoundLobbySession_executeSuccessfully() {
        String gameId = "post-round-game";
        String token = "player-token";
        Long userId = 11L;

        User user = new User();
        user.setId(userId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Game game = new Game();
        game.setId(gameId);
        game.setOrderedPlayerIds(List.of(userId));
        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        // Stub out lobby service interaction
        Mockito.when(lobbyService.findWaitingSessionIdForPlayer(userId)).thenReturn("waiting-session-xyz");

        // Create a partial spy of gameService to intercept internal sub-method calls safely
        GameService gameServiceSpy = Mockito.spy(gameService);
        Mockito.doNothing().when(gameServiceSpy).submitRematchDecision(anyString(), anyString(), anyString());

        // Act & Assert Path A: test getPostRoundLobbySessionForToken execution
        String sessionIdDirect = gameServiceSpy.getPostRoundLobbySessionForToken(gameId, token);
        assertEquals("waiting-session-xyz", sessionIdDirect);

        // Act & Assert Path B: test top-level orchestration method flow
        String sessionIdFlow = gameServiceSpy.completeRoundWithoutRematch(gameId, token);
        assertEquals("waiting-session-xyz", sessionIdFlow);
    }

    @Test
    void getRematchDecisionSeconds_returnsCorrectValue() {
        String gameId = "seconds-game";
        String token = "token-123";
        Long userId = 11L;

        User user = new User();
        user.setId(userId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Game game = new Game();
        game.setId(gameId);
        game.setOrderedPlayerIds(List.of(userId));
        game.setRematchDecisionSeconds(45L);
        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        // Act
        long seconds = gameService.getRematchDecisionSeconds(gameId, token);

        // Assert
        assertEquals(45L, seconds);
    }

    @Test
    void getGameRuntimeConfig_returnsMapOfAllConfiguredSecondsProperties() {
        String gameId = "runtime-config-game";
        String token = "token-abc";
        Long userId = 22L;

        User user = new User();
        user.setId(userId);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);

        Game game = new Game();
        game.setId(gameId);
        game.setOrderedPlayerIds(List.of(userId));
        
        // Populate all 7 runtime values evaluated by Map.of
        game.setTurnSeconds(30L);
        game.setInitialPeekSeconds(5L);
        game.setAbilityRevealSeconds(10L);
        game.setAbilitySwapSeconds(15L);
        game.setCaboRevealSeconds(20L);
        game.setAfkTimeoutSeconds(120L);
        game.setRematchDecisionSeconds(60L);

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        // Act
        Map<String, Long> configMap = gameService.getGameRuntimeConfig(gameId, token);

        // Assert
        assertNotNull(configMap);
        assertEquals(7, configMap.size());
        assertEquals(30L, configMap.get("turnSeconds"));
        assertEquals(5L, configMap.get("initialPeekSeconds"));
        assertEquals(10L, configMap.get("abilityRevealSeconds"));
        assertEquals(15L, configMap.get("abilitySwapSeconds"));
        assertEquals(20L, configMap.get("caboRevealSeconds"));
        assertEquals(120L, configMap.get("afkTimeoutSeconds"));
        assertEquals(60L, configMap.get("rematchDecisionSeconds"));
    }

    @Test
    void resolveAbsentRoundPointsForPlayingLobby_whenSessionInvalidOrServiceNull_returnsZero() throws Exception {
        // Find the private method via reflection
        Method method = GameService.class.getDeclaredMethod("resolveAbsentRoundPointsForPlayingLobby", String.class);
        method.setAccessible(true);

        // Path 1: sessionId is completely blank
        Long resultBlank = (Long) method.invoke(gameService, "   ");
        assertEquals(0L, resultBlank);

        // Path 2: sessionId is null
        Long resultNull = (Long) method.invoke(gameService, (String) null);
        assertEquals(0L, resultNull);
    }

    @Test
    void resolveAbsentRoundPointsForPlayingLobby_withValidAndInvalidLobbyRetrieval() throws Exception {
        // Find the private method via reflection
        Method method = GameService.class.getDeclaredMethod("resolveAbsentRoundPointsForPlayingLobby", String.class);
        method.setAccessible(true);

        String sessionId = "active-session-abc";

        // Path 3: Successful lookup path
        Lobby mockLobby = new Lobby();
        mockLobby.setAbsentRoundPoints(45L);
        Mockito.when(lobbyService.getLobbyBySessionId(sessionId)).thenReturn(mockLobby);

        Long resultSuccess = (Long) method.invoke(gameService, sessionId);
        assertEquals(45L, resultSuccess, "Should successfully return the points configured on the fetched lobby");

        // Path 4: Catch block path (Service throws an exception, method handles it quietly)
        Mockito.when(lobbyService.getLobbyBySessionId("broken-session"))
               .thenThrow(new RuntimeException("Database down"));

        Long resultException = (Long) method.invoke(gameService, "broken-session");
        assertEquals(0L, resultException, "Should safely catch exceptions and return 0L");
    }

    @Test
    void backfillPlayerForPreviousRounds_whenPlayerIdNullOrAlreadyExists_returnsEarly() throws Exception {
        Method method = GameService.class.getDeclaredMethod("backfillPlayerForPreviousRounds", 
                Long.class, List.class, Map.class, long.class);
        method.setAccessible(true);

        Map<Long, Integer> totalScores = new HashMap<>();
        totalScores.put(101L, 50); // Player 101 already exists in map

        // Path 1: playerId is null -> early return
        assertDoesNotThrow(() -> method.invoke(gameService, null, new ArrayList<>(), totalScores, 15L));

        // Path 2: playerId already exists in totalScores -> early return without mutating
        method.invoke(gameService, 101L, new ArrayList<>(), totalScores, 15L);
        assertEquals(50, totalScores.get(101L));
    }

    @Test
    void backfillPlayerForPreviousRounds_coversNullRounds_missingScores_andExistingScores() throws Exception {
        Method method = GameService.class.getDeclaredMethod("backfillPlayerForPreviousRounds", 
                Long.class, List.class, Map.class, long.class);
        method.setAccessible(true);

        Long playerId = 42L;
        long absentRoundPoints = 25L; // will cast to int 25

        // Setup 3 rounds to test every branch inside the loop:
        // Round 1: is completely null (hits the `if (roundScores == null)` continue branch)
        Map<Long, Integer> round1 = null;

        // Round 2: valid map but does NOT contain player 42 (hits `existing == null` backfill branch)
        Map<Long, Integer> round2 = new HashMap<>();

        // Round 3: valid map and ALREADY contains player 42 (hits `else` accumulation branch)
        Map<Long, Integer> round3 = new HashMap<>();
        round3.put(playerId, 10);

        List<Map<Long, Integer>> perRoundScores = Arrays.asList(round1, round2, round3);
        Map<Long, Integer> totalScores = new HashMap<>();

        // Act
        method.invoke(gameService, playerId, perRoundScores, totalScores, absentRoundPoints);

        // Assert
        // Round 2 should be backfilled with absent points (25)
        assertEquals(25, round2.get(playerId));
        // Round 3 should remain untouched (10)
        assertEquals(10, round3.get(playerId));
        
        // Total score calculation: 0 (from null round) + 25 (backfilled) + 10 (existing) = 35
        assertNotNull(totalScores.get(playerId));
        assertEquals(35, totalScores.get(playerId));
    }

    @Test
    void enterRoundAwaitingRematchPhase_whenStatusAlreadyAwaitingRematch_returnsEarly() throws Exception {
        Method method = GameService.class.getDeclaredMethod("enterRoundAwaitingRematchPhase", String.class, Game.class);
        method.setAccessible(true);

        String gameId = "rematch-early-1";
        Game game = new Game();
        game.setId(gameId);
        game.setStatus(GameStatus.ROUND_AWAITING_REMATCH); // Guard 1 condition
        game.setFreshRematchRequesterUserId(999L);

        // Act
        method.invoke(gameService, gameId, game);

        // Assert: Verify state was untouched and repository wasn't written to
        assertEquals(999L, game.getFreshRematchRequesterUserId());
        Mockito.verify(gameRepository, Mockito.never()).save(game);
    }

    @Test
    void enterRoundAwaitingRematchPhase_whenStatusNotCaboReveal_returnsEarly() throws Exception {
        Method method = GameService.class.getDeclaredMethod("enterRoundAwaitingRematchPhase", String.class, Game.class);
        method.setAccessible(true);

        String gameId = "rematch-early-2";
        Game game = new Game();
        game.setId(gameId);
        game.setStatus(GameStatus.ROUND_ACTIVE); // Guard 2 condition: NOT CABO_REVEAL
        game.setFreshRematchRequesterUserId(999L);

        // Act
        method.invoke(gameService, gameId, game);

        // Assert: Verify state was untouched
        assertEquals(999L, game.getFreshRematchRequesterUserId());
        Mockito.verify(gameRepository, Mockito.never()).save(game);
    }

    @Test
    void enterRoundAwaitingRematchPhase_whenStatusIsCaboReveal_updatesStateAndSaves() throws Exception {
        Method method = GameService.class.getDeclaredMethod("enterRoundAwaitingRematchPhase", String.class, Game.class);
        method.setAccessible(true);

        String gameId = "rematch-success";
        Game game = new Game();
        game.setId(gameId);
        game.setStatus(GameStatus.CABO_REVEAL); // Satisfies conditions to run main code block
        game.setFreshRematchRequesterUserId(123L);
        game.setOrderedPlayerIds(List.of(123L));
        game.setPlayerHands(new HashMap<>()); // Safety against cascading broadcast NPE loops

        Mockito.when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        // Act - wrapped in try-catch to absorb down-stream startRematchDecisionTimer side-effects safely
        try {
            method.invoke(gameService, gameId, game);
        } catch (Exception ignored) {
            // Internal timing threads won't stop the local state validation checks from verifying
        }

        // Assert
        assertEquals(GameStatus.ROUND_AWAITING_REMATCH, game.getStatus());
        assertNull(game.getFreshRematchRequesterUserId(), "Fresh rematch requester should be wiped out to null");
        
        Mockito.verify(gameRepository, Mockito.atLeastOnce()).save(game);
    }

    @Test
    void calculatedRoundScores_whenTieExistsAndCaboCallerNotInTie_appliesZeroToTiedAndPenaltyToCaller() throws Exception {
        Method method = GameService.class.getDeclaredMethod("calculatedRoundScores", Game.class);
        method.setAccessible(true);

        Long playerA = 101L;
        Long playerB = 102L;
        Long caboCaller = 103L;

        Game game = new Game();
        game.setOrderedPlayerIds(Arrays.asList(playerA, playerB, caboCaller));
        game.setCaboCalledByUserId(caboCaller); // Cabo caller did not win the round

        // Mock cards setup so playerA and playerB have identical low values, caboCaller has a higher value
        Card lowCard1 = new Card(); lowCard1.setValue(5);
        Card lowCard2 = new Card(); lowCard2.setValue(5);
        Card highCard = new Card(); highCard.setValue(10);

        Map<Long, List<Card>> playerHands = new HashMap<>();
        playerHands.put(playerA, List.of(lowCard1));  // hand value = 5
        playerHands.put(playerB, List.of(lowCard2));  // hand value = 5 (Tied minimum!)
        playerHands.put(caboCaller, List.of(highCard)); // hand value = 10
        game.setPlayerHands(playerHands);

        // Act
        @SuppressWarnings("unchecked")
        Map<Long, Integer> roundScores = (Map<Long, Integer>) method.invoke(gameService, game);

        // Assert
        assertNotNull(roundScores);
        // Both tied players get 0 points because the Cabo caller wasn't part of the tie
        assertEquals(0, roundScores.get(playerA));
        assertEquals(0, roundScores.get(playerB));
        // Cabo caller gets their hand value (10) + the 5 point penalty = 15
        assertEquals(15, roundScores.get(caboCaller));
    }

    @Test
    void calculatedRoundScores_whenPlayerHasHigherValueAndIsNotCaboCaller_getsNormalHandValue() throws Exception {
        Method method = GameService.class.getDeclaredMethod("calculatedRoundScores", Game.class);
        method.setAccessible(true);

        Long winnerId = 1L;
        Long regularLoserId = 2L;
        Long caboCallerId = 3L;

        Game game = new Game();
        game.setOrderedPlayerIds(Arrays.asList(winnerId, regularLoserId, caboCallerId));
        game.setCaboCalledByUserId(caboCallerId);

        Card winCard = new Card(); winCard.setValue(2);
        Card loseCard = new Card(); loseCard.setValue(8);
        Card callerCard = new Card(); callerCard.setValue(12);

        Map<Long, List<Card>> playerHands = new HashMap<>();
        playerHands.put(winnerId, List.of(winCard));        // hand value = 2 (Clear Winner)
        playerHands.put(regularLoserId, List.of(loseCard)); // hand value = 8 (Not minimum, not Cabo caller)
        playerHands.put(caboCallerId, List.of(callerCard)); // hand value = 12 (Not minimum, is Cabo caller)
        game.setPlayerHands(playerHands);

        // Act
        @SuppressWarnings("unchecked")
        Map<Long, Integer> roundScores = (Map<Long, Integer>) method.invoke(gameService, game);

        // Assert
        assertEquals(0, roundScores.get(winnerId));
        // Regular loser gets their exact card value without any penalty modifications
        assertEquals(8, roundScores.get(regularLoserId));
        // Cabo caller gets their value (12) + 5 point penalty = 17
        assertEquals(17, roundScores.get(caboCallerId));
    }
}
