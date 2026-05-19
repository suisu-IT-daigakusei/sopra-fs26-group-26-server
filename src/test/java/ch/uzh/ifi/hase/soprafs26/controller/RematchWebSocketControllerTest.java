package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.dto.RematchRequestDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class RematchWebSocketControllerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private RematchWebSocketController controller;

    @Test
    void handleRematchRequest_validSessionId_broadcastsMessage() {
        // 1. Setup: Create a mocked DTO so we don't have to worry about its actual implementation
        RematchRequestDTO mockMessage = Mockito.mock(RematchRequestDTO.class);
        Mockito.when(mockMessage.getSessionId()).thenReturn("session-123");
        Mockito.when(mockMessage.getRequesterUsername()).thenReturn("TestUser");
        Mockito.when(mockMessage.getRequesterId()).thenReturn(1L);

        // 2. Action: Call the method directly (no MockMvc needed!)
        controller.handleRematchRequest(mockMessage);

        // 3. Assertion: Verify the template sent the exact message to the exact URL
        String expectedDestination = "/topic/session/session-123/rematch";
        Mockito.verify(messagingTemplate, Mockito.times(1))
               .convertAndSend(expectedDestination, mockMessage);
    }

    @Test
    void handleRematchRequest_nullSessionId_abortsAndDoesNotBroadcast() {
        // 1. Setup: Simulate a payload with a null session ID
        RematchRequestDTO mockMessage = Mockito.mock(RematchRequestDTO.class);
        Mockito.when(mockMessage.getSessionId()).thenReturn(null);

        // 2. Action
        controller.handleRematchRequest(mockMessage);

        // 3. Assertion: Use Mockito.any(Object.class) to resolve the compiler ambiguity
        Mockito.verify(messagingTemplate, Mockito.never())
               .convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
    }

    @Test
    void handleRematchRequest_blankSessionId_abortsAndDoesNotBroadcast() {
        // 1. Setup: Simulate a payload with a blank session ID
        RematchRequestDTO mockMessage = Mockito.mock(RematchRequestDTO.class);
        Mockito.when(mockMessage.getSessionId()).thenReturn("   ");

        // 2. Action
        controller.handleRematchRequest(mockMessage);

        // 3. Assertion: Use Mockito.any(Object.class) to resolve the compiler ambiguity
        Mockito.verify(messagingTemplate, Mockito.never())
               .convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
    }
}