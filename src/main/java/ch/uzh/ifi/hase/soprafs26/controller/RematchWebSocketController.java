package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.dto.RematchRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class RematchWebSocketController {
    private static final Logger log = LoggerFactory.getLogger(RematchWebSocketController.class);

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public RematchWebSocketController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     Receives a rematch request from a player and broadcasts it to all other players subscribed to this session's rematch topic.
     */
    @MessageMapping("/session/rematch")
    public void handleRematchRequest(@Payload RematchRequestDTO message) {
        if (message.getSessionId() == null || message.getSessionId().isBlank()) {
            log.warn("Rematch request received without a valid sessionId");
            return;
        }

        String destination = "/topic/session/" + message.getSessionId() + "/rematch";
        
        // broadcasting the message to all members of the session
        messagingTemplate.convertAndSend(destination, message);
        
        log.debug(
                "Rematch request from user {} (ID: {}) broadcasted to {}",
                message.getRequesterUsername(),
                message.getRequesterId(),
                destination);
    }
}
