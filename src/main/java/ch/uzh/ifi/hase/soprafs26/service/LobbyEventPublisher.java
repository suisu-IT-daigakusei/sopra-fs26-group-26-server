package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class LobbyEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LobbyEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    public LobbyEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Broadcasts the full updated lobby state to all subscribers.
     * Call this whenever the lobby changes (player joins, leaves, game starts, etc.)
     *
     * @param lobbyId the lobby's unique ID
     * @param payload any object — will be serialized to JSON automatically
     */
    public void broadcastLobbyUpdate(Long lobbyId, Object payload) {
        PostCommitActionExecutor.execute(log, "lobby update " + lobbyId, () -> {
            messagingTemplate.convertAndSend("/topic/lobby/" + lobbyId, payload);
            if (payload instanceof Lobby lobby && lobby.getSessionId() != null) {
                messagingTemplate.convertAndSend(
                        "/topic/lobby/session/" + lobby.getSessionId(), payload);
            }
        });
    }
}
