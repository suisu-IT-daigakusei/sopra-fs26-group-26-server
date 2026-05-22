package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameStateBroadcastDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.GameStateBroadcastMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameEventPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private GameStateBroadcastMapper gameStateBroadcastMapper;

    @Mock
    private WebSocketSessionTracker webSocketSessionTracker;

    @InjectMocks
    private GameEventPublisher gameEventPublisher;

    @Test
    void publishFilteredState_ignoresNullAndPlayerlessGames() {
        gameEventPublisher.publishFilteredState(null);

        Game gameWithoutPlayers = new Game();
        gameWithoutPlayers.setOrderedPlayerIds(List.of());
        gameEventPublisher.publishFilteredState(gameWithoutPlayers);

        verify(gameStateBroadcastMapper, never()).buildSharedContext(any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void publishFilteredState_sendsToPlayersAndSpectatorsAcrossTrackedSessions() {
        Game game = new Game();
        game.setId("g-1");
        game.setOrderedPlayerIds(java.util.Arrays.asList(1L, 2L, null));

        GameStateBroadcastMapper.SharedBroadcastContext sharedContext = new GameStateBroadcastMapper.SharedBroadcastContext();
        sharedContext.setSpectatorIds(java.util.Arrays.asList(2L, 3L, null));

        when(gameStateBroadcastMapper.buildSharedContext(game)).thenReturn(sharedContext);
        when(gameStateBroadcastMapper.toBroadcastForViewer(eq(game), any(Long.class), eq(sharedContext)))
                .thenAnswer(invocation -> {
                    GameStateBroadcastDTO dto = new GameStateBroadcastDTO();
                    dto.setGameId("g-1");
                    return dto;
                });

        when(webSocketSessionTracker.getTrackedSessionIds(1L)).thenReturn(List.of());
        when(webSocketSessionTracker.getTrackedSessionIds(2L)).thenReturn(List.of("s2a", "", "s2b"));
        when(webSocketSessionTracker.getTrackedSessionIds(3L)).thenReturn(List.of("s3"));

        gameEventPublisher.publishFilteredState(game);

        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(gameStateBroadcastMapper, times(3))
                .toBroadcastForViewer(eq(game), userIdCaptor.capture(), eq(sharedContext));
        Set<Long> recipients = userIdCaptor.getAllValues().stream().collect(Collectors.toSet());
        assertEquals(Set.of(1L, 2L, 3L), recipients);

        verify(messagingTemplate, times(1))
                .convertAndSendToUser(eq("1"), eq(GameEventPublisher.USER_QUEUE_GAME_STATE), any(GameStateBroadcastDTO.class));
        verify(messagingTemplate, times(3))
                .convertAndSendToUser(any(String.class), eq(GameEventPublisher.USER_QUEUE_GAME_STATE),
                        any(GameStateBroadcastDTO.class), any(MessageHeaders.class));

        ArgumentCaptor<MessageHeaders> headersCaptor = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messagingTemplate, times(2))
                .convertAndSendToUser(eq("2"), eq(GameEventPublisher.USER_QUEUE_GAME_STATE),
                        any(GameStateBroadcastDTO.class), headersCaptor.capture());
        verify(messagingTemplate, times(1))
                .convertAndSendToUser(eq("3"), eq(GameEventPublisher.USER_QUEUE_GAME_STATE),
                        any(GameStateBroadcastDTO.class), headersCaptor.capture());

        List<String> sessionIds = headersCaptor.getAllValues().stream()
                .map(headers -> (String) headers.get(SimpMessageHeaderAccessor.SESSION_ID_HEADER))
                .toList();
        assertTrue(sessionIds.contains("s2a"));
        assertTrue(sessionIds.contains("s2b"));
        assertTrue(sessionIds.contains("s3"));
    }
}
