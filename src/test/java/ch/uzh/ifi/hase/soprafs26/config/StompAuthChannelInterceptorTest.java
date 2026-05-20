package ch.uzh.ifi.hase.soprafs26.config;

import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WebSocketSessionTracker webSocketSessionTracker;

    @Mock
    private MessageChannel messageChannel;

    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(userRepository, webSocketSessionTracker);
    }

    @Test
    void preSend_connectStoresUserInSessionAndTouchesTracker() {
        User user = new User();
        user.setId(41L);
        when(userRepository.findByToken("token-41")).thenReturn(user);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session-41");
        accessor.setNativeHeader("Authorization", "token-41");
        Map<String, Object> sessionAttributes = new HashMap<>();
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, messageChannel);
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);

        assertNotNull(sessionAttributes);
        assertEquals(41L, sessionAttributes.get("userId"));
        assertNotNull(resultAccessor);
        assertNotNull(resultAccessor.getUser());
        assertEquals("41", resultAccessor.getUser().getName());
        verify(userRepository).save(user);
        verify(webSocketSessionTracker).registerSession(41L, "session-41");
        verify(webSocketSessionTracker).touchSession(41L, "session-41");
    }

    @Test
    void preSend_nonConnectMessageTouchesTrackedSessionWithoutAuthLookup() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("session-55");
        accessor.setSessionAttributes(new HashMap<>(Map.of("userId", 55L)));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, messageChannel);

        verify(webSocketSessionTracker).touchSession(55L, "session-55");
        verify(userRepository, never()).findByToken(org.mockito.ArgumentMatchers.anyString());
    }
}
