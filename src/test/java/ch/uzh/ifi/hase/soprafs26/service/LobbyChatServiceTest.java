package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatMessageDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatSendDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LobbyChatServiceTest {

    @Mock
    private LobbyRepository lobbyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private LobbyChatService lobbyChatService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void sendMessage_validMessage_publishesAndCanResync() {
        stubUser("token-1", 1L, "alice");
        stubLobby("ABCD1234", List.of(1L), List.of(), 3L);

        LobbyChatSendDTO body = new LobbyChatSendDTO();
        body.setMessage("  hello world  ");

        LobbyChatMessageDTO message = lobbyChatService.sendMessage("token-1", "ABCD1234", body);
        List<LobbyChatMessageDTO> history = lobbyChatService.getMessages("token-1", "ABCD1234");

        assertEquals("hello world", message.getText());
        assertEquals(1L, message.getSequence());
        assertNotNull(message.getSentAt());
        assertFalse(history.isEmpty());
        assertEquals("hello world", history.get(0).getText());
        assertNotNull(history.get(0).getSentAt());
        assertTrue(message != history.get(0));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(messagingTemplate, Mockito.times(1))
                .convertAndSend(Mockito.eq("/topic/lobby/session/ABCD1234/chat"), payloadCaptor.capture());

        Object payload = payloadCaptor.getValue();
        assertTrue(payload instanceof LobbyChatMessageDTO);
        assertEquals("hello world", ((LobbyChatMessageDTO) payload).getText());
    }

    @Test
    void sendMessage_sentTooFast_throwsTooManyRequests() {
        stubUser("token-1", 1L, "alice");
        stubLobby("ABCD1234", List.of(1L), List.of(), 3L);

        LobbyChatSendDTO first = new LobbyChatSendDTO();
        first.setMessage("first");
        lobbyChatService.sendMessage("token-1", "ABCD1234", first);

        LobbyChatSendDTO second = new LobbyChatSendDTO();
        second.setMessage("second");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> lobbyChatService.sendMessage("token-1", "ABCD1234", second));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
    }

    @Test
    void sendMessage_defaultCooldownAppliesWhenLobbyCooldownMissing() {
        stubUser("token-1", 1L, "alice");
        stubLobby("ABCD1234", List.of(1L), List.of(), null);

        LobbyChatSendDTO body = new LobbyChatSendDTO();
        body.setMessage("hello");
        lobbyChatService.sendMessage("token-1", "ABCD1234", body);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> lobbyChatService.sendMessage("token-1", "ABCD1234", body));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
    }

    @Test
    void sendMessage_secondUserDoesNotShareCooldown() {
        stubUser("token-1", 1L, "alice");
        stubUser("token-2", 2L, "bob");
        stubLobby("ABCD1234", List.of(1L, 2L), List.of(), 3L);

        LobbyChatSendDTO first = new LobbyChatSendDTO();
        first.setMessage("first");
        LobbyChatSendDTO second = new LobbyChatSendDTO();
        second.setMessage("second");

        LobbyChatMessageDTO firstMessage = lobbyChatService.sendMessage("token-1", "ABCD1234", first);
        LobbyChatMessageDTO secondMessage = lobbyChatService.sendMessage("token-2", "ABCD1234", second);

        assertEquals(1L, firstMessage.getSequence());
        assertEquals(2L, secondMessage.getSequence());
        assertEquals("bob", secondMessage.getUsername());
    }

    @Test
    void sendMessage_spectatorIsAllowed() {
        stubUser("token-2", 2L, "spectator");
        stubLobby("ABCD1234", List.of(1L), List.of(2L), 3L);

        LobbyChatSendDTO body = new LobbyChatSendDTO();
        body.setMessage("spectating");

        LobbyChatMessageDTO message = lobbyChatService.sendMessage("token-2", "ABCD1234", body);
        assertEquals("spectator", message.getUsername());
        assertEquals("spectating", message.getText());
    }

    @Test
    void sendMessage_userOutsideLobby_forbidden() {
        stubUser("token-9", 9L, "outsider");
        stubLobby("ABCD1234", List.of(1L, 2L), List.of(3L), 3L);

        LobbyChatSendDTO body = new LobbyChatSendDTO();
        body.setMessage("hello");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> lobbyChatService.sendMessage("token-9", "ABCD1234", body));
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    void getMessages_invalidToken_unauthorized() {
        Mockito.when(userRepository.findByToken("bad-token")).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> lobbyChatService.getMessages("bad-token", "ABCD1234"));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    void sendMessage_missingMessage_throwsBadRequest() {
        stubUser("token-1", 1L, "alice");
        stubLobby("ABCD1234", List.of(1L), List.of(), 3L);

        LobbyChatSendDTO body = new LobbyChatSendDTO();
        body.setMessage(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> lobbyChatService.sendMessage("token-1", "ABCD1234", body));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void sendMessage_multiLine_throwsBadRequest() {
        stubUser("token-1", 1L, "alice");
        stubLobby("ABCD1234", List.of(1L), List.of(), 3L);

        LobbyChatSendDTO body = new LobbyChatSendDTO();
        body.setMessage("hello\nworld");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> lobbyChatService.sendMessage("token-1", "ABCD1234", body));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void sendMessage_nonAscii_throwsBadRequest() {
        stubUser("token-1", 1L, "alice");
        stubLobby("ABCD1234", List.of(1L), List.of(), 3L);

        LobbyChatSendDTO body = new LobbyChatSendDTO();
        body.setMessage("hello \ud83d\ude0a");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> lobbyChatService.sendMessage("token-1", "ABCD1234", body));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void sendMessage_overMaxLength_throwsBadRequest() {
        stubUser("token-1", 1L, "alice");
        stubLobby("ABCD1234", List.of(1L), List.of(), 3L);

        LobbyChatSendDTO body = new LobbyChatSendDTO();
        body.setMessage(IntStream.range(0, 51).mapToObj(i -> "a").collect(Collectors.joining()));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> lobbyChatService.sendMessage("token-1", "ABCD1234", body));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void clearSessionMessages_removesHistoryForSession() {
        stubUser("token-1", 1L, "alice");
        stubLobby("ABCD1234", List.of(1L), List.of(), 3L);

        LobbyChatSendDTO body = new LobbyChatSendDTO();
        body.setMessage("hello");
        lobbyChatService.sendMessage("token-1", "ABCD1234", body);
        assertEquals(1, lobbyChatService.getMessages("token-1", "ABCD1234").size());

        lobbyChatService.clearSessionMessages("ABCD1234");
        assertTrue(lobbyChatService.getMessages("token-1", "ABCD1234").isEmpty());
    }

    private void stubUser(String token, Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        Mockito.when(userRepository.findByToken(token)).thenReturn(user);
    }

    private void stubLobby(String sessionId, List<Long> playerIds, List<Long> spectatorIds, Long chatCooldownSeconds) {
        Lobby lobby = new Lobby();
        lobby.setSessionId(sessionId);
        lobby.setStatus("WAITING");
        lobby.setPlayerIds(new ArrayList<>(playerIds));
        lobby.setSpectatorIds(new ArrayList<>(spectatorIds));
        lobby.setChatCooldownSeconds(chatCooldownSeconds);
        Mockito.when(lobbyRepository.findBySessionId(sessionId)).thenReturn(lobby);
    }
}
