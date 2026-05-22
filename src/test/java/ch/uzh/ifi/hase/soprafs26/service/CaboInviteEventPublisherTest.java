package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInvitePendingDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteSentDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CaboInviteEventPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private CaboInviteEventPublisher publisher;

    @Test
    void publishAfterCommit_withActiveSynchronization_defersSendUntilCommit() {
        CaboInvitePendingDTO pending = new CaboInvitePendingDTO();
        CaboInviteSentDTO sent = new CaboInviteSentDTO();

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishToInviteeAfterCommit(2L, pending);
            publisher.publishToInviterAfterCommit(1L, sent);

            verify(messagingTemplate, never()).convertAndSend("/topic/users/2/invites", pending);
            verify(messagingTemplate, never()).convertAndSend("/topic/users/1/invites/sent", sent);

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(messagingTemplate, times(1)).convertAndSend("/topic/users/2/invites", pending);
            verify(messagingTemplate, times(1)).convertAndSend("/topic/users/1/invites/sent", sent);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishAfterCommit_withoutSynchronization_sendsImmediately() {
        CaboInvitePendingDTO pending = new CaboInvitePendingDTO();
        CaboInviteSentDTO sent = new CaboInviteSentDTO();

        publisher.publishToInviteeAfterCommit(2L, pending);
        publisher.publishToInviterAfterCommit(1L, sent);

        verify(messagingTemplate, times(1)).convertAndSend("/topic/users/2/invites", pending);
        verify(messagingTemplate, times(1)).convertAndSend("/topic/users/1/invites/sent", sent);
    }
}
