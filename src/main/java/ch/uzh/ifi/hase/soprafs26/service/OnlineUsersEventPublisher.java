package ch.uzh.ifi.hase.soprafs26.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class OnlineUsersEventPublisher {

    private static final String TOPIC = "/topic/users/online";
    private static final Logger log = LoggerFactory.getLogger(OnlineUsersEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final AtomicLong revision = new AtomicLong(0L);

    public OnlineUsersEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Sends a tiny cache-invalidation signal. Broadcasting every active User made
     * one presence change O(total users) in database work and network payload.
     * Consumers obtain their bounded/paginated view through GET /users.
     */
    public void broadcastOnlineUsers() {
        Runnable send = () -> messagingTemplate.convertAndSend(
                TOPIC,
                new PresenceChangedEvent("presence-changed", revision.incrementAndGet()));
        PostCommitActionExecutor.execute(log, "online-user presence update", send);
    }

    public record PresenceChangedEvent(String type, long revision) {
    }
}
