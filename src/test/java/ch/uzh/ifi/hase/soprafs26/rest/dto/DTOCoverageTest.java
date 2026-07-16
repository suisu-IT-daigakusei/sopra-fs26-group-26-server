package ch.uzh.ifi.hase.soprafs26.rest.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DTOCoverageTest {

    @Test
    void invitationDtosExposeAssignedValues() {
        CaboInviteDecisionDTO decision = new CaboInviteDecisionDTO();
        decision.setDecision("accept");
        assertEquals("accept", decision.getDecision());

        CaboInviteRespondDTO response = new CaboInviteRespondDTO();
        response.setWaitingLobbySessionId("session-1");
        assertEquals("session-1", response.getWaitingLobbySessionId());

        CaboInviteSentDTO sent = new CaboInviteSentDTO();
        sent.setToUserId(42L);
        sent.setToUsername("alice");
        sent.setStatus("pending");
        assertEquals(42L, sent.getToUserId());
        assertEquals("alice", sent.getToUsername());
        assertEquals("pending", sent.getStatus());
    }

    @Test
    void pendingInvitationFormatsItsPublicId() {
        CaboInvitePendingDTO pending = new CaboInvitePendingDTO();
        assertNull(pending.getInviteId());

        pending.setId(7L);
        pending.setFromUserId(8L);
        pending.setFromUsername("bob");
        pending.setFromName("Bob");
        pending.setSessionId("session-2");
        pending.setSessionHostUserId(9L);
        pending.setInviteCreationDate("2026-07-16T00:00:00Z");

        assertEquals(7L, pending.getId());
        assertEquals("7", pending.getInviteId());
        assertEquals(8L, pending.getFromUserId());
        assertEquals("bob", pending.getFromUsername());
        assertEquals("Bob", pending.getFromName());
        assertEquals("session-2", pending.getSessionId());
        assertEquals(9L, pending.getSessionHostUserId());
        assertEquals("2026-07-16T00:00:00Z", pending.getInviteCreationDate());
    }

    @Test
    void lobbyReadyPatchExposesAssignedValue() {
        LobbyReadyPatchDTO patch = new LobbyReadyPatchDTO();
        patch.setReady(Boolean.TRUE);
        assertEquals(Boolean.TRUE, patch.getReady());
    }
}
