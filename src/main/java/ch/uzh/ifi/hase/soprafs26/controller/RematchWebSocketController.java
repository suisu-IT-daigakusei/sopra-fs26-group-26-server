package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.dto.RematchRequestDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class RematchWebSocketController {

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
            System.err.println("Error: Rematch request received without a valid sessionId!");
            return;
        }

        String destination = "/topic/session/" + message.getSessionId() + "/rematch";
        
        // broadcasting the message to all members of the session
        messagingTemplate.convertAndSend(destination, message);
        
        System.out.println("Rematch request from User " 
                + message.getRequesterUsername() + " (ID: " + message.getRequesterId() 
                + ") successfully broadcasted to " + destination);
    }
}