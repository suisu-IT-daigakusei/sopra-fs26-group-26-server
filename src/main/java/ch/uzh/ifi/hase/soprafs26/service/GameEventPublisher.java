package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameStateBroadcastDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.GameStateBroadcastMapper;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class GameEventPublisher {

    // client subscribes to /user/queue/game-state
    // based on the /user/ prefix Spring will handle per-user websocket communication
    public static final String USER_QUEUE_GAME_STATE = "/queue/game-state";
    // used to send messages to client
    private final SimpMessagingTemplate messagingTemplate;
    // has the logic for filtering
    private final GameStateBroadcastMapper gameStateBroadcastMapper;
    private final WebSocketSessionTracker webSocketSessionTracker;

    public GameEventPublisher(SimpMessagingTemplate messagingTemplate,
                              GameStateBroadcastMapper gameStateBroadcastMapper,
                              WebSocketSessionTracker webSocketSessionTracker) {
        this.messagingTemplate = messagingTemplate;
        this.gameStateBroadcastMapper = gameStateBroadcastMapper;
        this.webSocketSessionTracker = webSocketSessionTracker;
    }

    public void publishFilteredState(Game game) {
        if (game == null) {
            return;
        }
        // get all players of the game
        List<Long> playerIds = game.getOrderedPlayerIds();
        if (playerIds == null || playerIds.isEmpty()) {
            return;
        }
        Set<Long> recipientIds = new LinkedHashSet<>();
        recipientIds.addAll(playerIds);
        GameStateBroadcastMapper.SharedBroadcastContext sharedContext =
                gameStateBroadcastMapper.buildSharedContext(game);
        recipientIds.addAll(sharedContext.getSpectatorIds());

        for (Long userId : recipientIds) {
            if (userId == null) {
                continue;
            }
            GameStateBroadcastDTO dto = gameStateBroadcastMapper.toBroadcastForViewer(game, userId, sharedContext);
            sendToTrackedSessions(userId, dto);
        }
    }

    private void sendToTrackedSessions(Long userId, GameStateBroadcastDTO payload) {
        String userKey = String.valueOf(userId);
        List<String> trackedSessionIds = webSocketSessionTracker.getTrackedSessionIds(userId);
        if (trackedSessionIds == null || trackedSessionIds.isEmpty()) {
            messagingTemplate.convertAndSendToUser(userKey, USER_QUEUE_GAME_STATE, payload);
            return;
        }

        for (String sessionId : trackedSessionIds) {
            if (sessionId == null || sessionId.isBlank()) {
                continue;
            }
            SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
            headerAccessor.setSessionId(sessionId);
            headerAccessor.setLeaveMutable(true);
            MessageHeaders headers = headerAccessor.getMessageHeaders();
            messagingTemplate.convertAndSendToUser(userKey, USER_QUEUE_GAME_STATE, payload, headers);
        }
    }
}
