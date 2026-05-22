package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class LobbyEventPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private LobbyEventPublisher lobbyEventPublisher;

    @Test
    void broadcastLobbyUpdate_alwaysPublishesByLobbyIdAndOptionallyBySession() {
        Lobby withSession = new Lobby();
        withSession.setSessionId("session-7");

        lobbyEventPublisher.broadcastLobbyUpdate(7L, withSession);

        verify(messagingTemplate, times(1)).convertAndSend("/topic/lobby/7", withSession);
        verify(messagingTemplate, times(1)).convertAndSend("/topic/lobby/session/session-7", withSession);
    }

    @Test
    void broadcastLobbyUpdate_payloadWithoutSessionOnlyPublishesLobbyTopic() {
        lobbyEventPublisher.broadcastLobbyUpdate(8L, "payload");

        verify(messagingTemplate, times(1)).convertAndSend("/topic/lobby/8", "payload");
        verifyNoMoreInteractions(messagingTemplate);
    }
}
