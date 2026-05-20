package ch.uzh.ifi.hase.soprafs26.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for RematchRequestDTO
 * Achieves 100% line coverage for constructors, getters, and setters
 * Note: Jackson serialization tests removed due to test scope dependency issues
 */
class RematchRequestDTOTest {

    private RematchRequestDTO rematchRequestDTO;

    @BeforeEach
    void setUp() {
        // Tests will create their own instances as needed
    }

    // ==================== Constructor Tests ====================

    @Test
    void testDefaultConstructor() {
        // When
        rematchRequestDTO = new RematchRequestDTO();

        // Then
        assertNotNull(rematchRequestDTO);
        assertNull(rematchRequestDTO.getSessionId());
        assertNull(rematchRequestDTO.getRequesterId());
        assertNull(rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testParameterizedConstructor() {
        // Given
        String sessionId = "session123";
        Long requesterId = 42L;
        String requesterUsername = "player1";

        // When
        rematchRequestDTO = new RematchRequestDTO(sessionId, requesterId, requesterUsername);

        // Then
        assertNotNull(rematchRequestDTO);
        assertEquals(sessionId, rematchRequestDTO.getSessionId());
        assertEquals(requesterId, rematchRequestDTO.getRequesterId());
        assertEquals(requesterUsername, rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testParameterizedConstructor_WithNullValues() {
        // When
        rematchRequestDTO = new RematchRequestDTO(null, null, null);

        // Then
        assertNotNull(rematchRequestDTO);
        assertNull(rematchRequestDTO.getSessionId());
        assertNull(rematchRequestDTO.getRequesterId());
        assertNull(rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testParameterizedConstructor_WithMixedValues() {
        // Given
        String sessionId = "valid-session";
        Long requesterId = null;
        String requesterUsername = "validUser";

        // When
        rematchRequestDTO = new RematchRequestDTO(sessionId, requesterId, requesterUsername);

        // Then
        assertEquals(sessionId, rematchRequestDTO.getSessionId());
        assertNull(rematchRequestDTO.getRequesterId());
        assertEquals(requesterUsername, rematchRequestDTO.getRequesterUsername());
    }

    // ==================== SessionId Tests ====================

    @Test
    void testSetAndGetSessionId() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();
        String sessionId = "test-session-456";

        // When
        rematchRequestDTO.setSessionId(sessionId);

        // Then
        assertEquals(sessionId, rematchRequestDTO.getSessionId());
    }

    @Test
    void testSetSessionId_WithNull() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When
        rematchRequestDTO.setSessionId(null);

        // Then
        assertNull(rematchRequestDTO.getSessionId());
    }

    @Test
    void testSetSessionId_WithEmptyString() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When
        rematchRequestDTO.setSessionId("");

        // Then
        assertEquals("", rematchRequestDTO.getSessionId());
    }

    @Test
    void testSetSessionId_Overwrite() {
        // Given
        rematchRequestDTO = new RematchRequestDTO("old-session", 1L, "olduser");

        // When
        rematchRequestDTO.setSessionId("new-session");

        // Then
        assertEquals("new-session", rematchRequestDTO.getSessionId());
    }

    @Test
    void testSetSessionId_WithSpecialCharacters() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();
        String sessionWithSpecialChars = "session_123-xyz@456";

        // When
        rematchRequestDTO.setSessionId(sessionWithSpecialChars);

        // Then
        assertEquals(sessionWithSpecialChars, rematchRequestDTO.getSessionId());
    }

    // ==================== RequesterId Tests ====================

    @Test
    void testSetAndGetRequesterId() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();
        Long requesterId = 999L;

        // When
        rematchRequestDTO.setRequesterId(requesterId);

        // Then
        assertEquals(requesterId, rematchRequestDTO.getRequesterId());
    }

    @Test
    void testSetRequesterId_WithNull() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When
        rematchRequestDTO.setRequesterId(null);

        // Then
        assertNull(rematchRequestDTO.getRequesterId());
    }

    @Test
    void testSetRequesterId_WithZero() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When
        rematchRequestDTO.setRequesterId(0L);

        // Then
        assertEquals(0L, rematchRequestDTO.getRequesterId());
    }

    @Test
    void testSetRequesterId_WithNegativeValue() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When
        rematchRequestDTO.setRequesterId(-1L);

        // Then
        assertEquals(-1L, rematchRequestDTO.getRequesterId());
    }

    @Test
    void testSetRequesterId_WithMaxValue() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When
        rematchRequestDTO.setRequesterId(Long.MAX_VALUE);

        // Then
        assertEquals(Long.MAX_VALUE, rematchRequestDTO.getRequesterId());
    }

    @Test
    void testSetRequesterId_Overwrite() {
        // Given
        rematchRequestDTO = new RematchRequestDTO("session", 100L, "user");

        // When
        rematchRequestDTO.setRequesterId(200L);

        // Then
        assertEquals(200L, rematchRequestDTO.getRequesterId());
    }

    // ==================== RequesterUsername Tests ====================

    @Test
    void testSetAndGetRequesterUsername() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();
        String username = "testPlayer";

        // When
        rematchRequestDTO.setRequesterUsername(username);

        // Then
        assertEquals(username, rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testSetRequesterUsername_WithNull() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When
        rematchRequestDTO.setRequesterUsername(null);

        // Then
        assertNull(rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testSetRequesterUsername_WithEmptyString() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When
        rematchRequestDTO.setRequesterUsername("");

        // Then
        assertEquals("", rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testSetRequesterUsername_WithWhitespace() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();
        String usernameWithSpaces = "user name";

        // When
        rematchRequestDTO.setRequesterUsername(usernameWithSpaces);

        // Then
        assertEquals(usernameWithSpaces, rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testSetRequesterUsername_Overwrite() {
        // Given
        rematchRequestDTO = new RematchRequestDTO("session", 1L, "oldUsername");

        // When
        rematchRequestDTO.setRequesterUsername("newUsername");

        // Then
        assertEquals("newUsername", rematchRequestDTO.getRequesterUsername());
    }

    // ==================== Field Independence Tests ====================

    @Test
    void testAllFieldsIndependent() {
        // Given
        rematchRequestDTO = new RematchRequestDTO("session1", 1L, "user1");

        // When - modify each field independently
        rematchRequestDTO.setSessionId("session2");
        assertEquals("session2", rematchRequestDTO.getSessionId());
        assertEquals(1L, rematchRequestDTO.getRequesterId()); // unchanged
        assertEquals("user1", rematchRequestDTO.getRequesterUsername()); // unchanged

        rematchRequestDTO.setRequesterId(2L);
        assertEquals("session2", rematchRequestDTO.getSessionId()); // unchanged
        assertEquals(2L, rematchRequestDTO.getRequesterId());
        assertEquals("user1", rematchRequestDTO.getRequesterUsername()); // unchanged

        rematchRequestDTO.setRequesterUsername("user2");
        assertEquals("session2", rematchRequestDTO.getSessionId()); // unchanged
        assertEquals(2L, rematchRequestDTO.getRequesterId()); // unchanged
        assertEquals("user2", rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testSetAllFieldsToNull() {
        // Given
        rematchRequestDTO = new RematchRequestDTO("session", 100L, "username");

        // When
        rematchRequestDTO.setSessionId(null);
        rematchRequestDTO.setRequesterId(null);
        rematchRequestDTO.setRequesterUsername(null);

        // Then
        assertNull(rematchRequestDTO.getSessionId());
        assertNull(rematchRequestDTO.getRequesterId());
        assertNull(rematchRequestDTO.getRequesterUsername());
    }

    // ==================== Integration/Workflow Tests ====================

    @Test
    void testCompleteWorkflow_CreateAndModify() {
        // Given - Create DTO with constructor
        rematchRequestDTO = new RematchRequestDTO("initial-session", 10L, "initialUser");

        // When - Modify all fields
        rematchRequestDTO.setSessionId("updated-session");
        rematchRequestDTO.setRequesterId(20L);
        rematchRequestDTO.setRequesterUsername("updatedUser");

        // Then
        assertEquals("updated-session", rematchRequestDTO.getSessionId());
        assertEquals(20L, rematchRequestDTO.getRequesterId());
        assertEquals("updatedUser", rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testBuildWithDefaultConstructor() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When - build up the object
        rematchRequestDTO.setSessionId("ws-session-789");
        rematchRequestDTO.setRequesterId(555L);
        rematchRequestDTO.setRequesterUsername("alice");

        // Then
        assertEquals("ws-session-789", rematchRequestDTO.getSessionId());
        assertEquals(555L, rematchRequestDTO.getRequesterId());
        assertEquals("alice", rematchRequestDTO.getRequesterUsername());
    }

    @Test
    void testMultipleGettersConsistency() {
        // Given
        rematchRequestDTO = new RematchRequestDTO("stable-session", 99L, "stableUser");

        // When - call getters multiple times
        String session1 = rematchRequestDTO.getSessionId();
        String session2 = rematchRequestDTO.getSessionId();
        Long id1 = rematchRequestDTO.getRequesterId();
        Long id2 = rematchRequestDTO.getRequesterId();
        String user1 = rematchRequestDTO.getRequesterUsername();
        String user2 = rematchRequestDTO.getRequesterUsername();

        // Then - all calls return consistent values
        assertEquals(session1, session2);
        assertEquals(id1, id2);
        assertEquals(user1, user2);
    }

    @Test
    void testPartialInitializationViaConstructor() {
        // Given - using constructor with some null values
        rematchRequestDTO = new RematchRequestDTO("partial-session", null, "partialUser");

        // Then
        assertEquals("partial-session", rematchRequestDTO.getSessionId());
        assertNull(rematchRequestDTO.getRequesterId());
        assertEquals("partialUser", rematchRequestDTO.getRequesterUsername());

        // When - fill in missing field
        rematchRequestDTO.setRequesterId(42L);

        // Then
        assertEquals(42L, rematchRequestDTO.getRequesterId());
    }

    @Test
    void testSettersDoNotThrowExceptions() {
        // Given
        rematchRequestDTO = new RematchRequestDTO();

        // When & Then - all setters should work without exceptions
        assertDoesNotThrow(() -> rematchRequestDTO.setSessionId("test"));
        assertDoesNotThrow(() -> rematchRequestDTO.setRequesterId(1L));
        assertDoesNotThrow(() -> rematchRequestDTO.setRequesterUsername("test"));
        assertDoesNotThrow(() -> rematchRequestDTO.setSessionId(null));
        assertDoesNotThrow(() -> rematchRequestDTO.setRequesterId(null));
        assertDoesNotThrow(() -> rematchRequestDTO.setRequesterUsername(null));
    }
}