package ch.uzh.ifi.hase.soprafs26.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for StompPrincipal
 * Achieves 100% line coverage
 * 
 * SECURITY CRITICAL: This class is used for WebSocket authentication.
 * Proper testing prevents potential authentication bypass vulnerabilities.
 */
class StompPrincipalTest {

    @Test
    @DisplayName("Constructor should create principal with valid name")
    void testConstructor_WithValidName() {
        // Given
        String name = "user123";

        // When
        StompPrincipal principal = new StompPrincipal(name);

        // Then
        assertNotNull(principal);
        assertEquals(name, principal.getName());
    }

    @Test
    @DisplayName("getName() should return the exact name provided in constructor")
    void testGetName_ReturnsCorrectValue() {
        // Given
        String expectedName = "testUser456";
        StompPrincipal principal = new StompPrincipal(expectedName);

        // When
        String actualName = principal.getName();

        // Then
        assertEquals(expectedName, actualName);
    }

    @Test
    @DisplayName("Constructor should handle numeric string names")
    void testConstructor_WithNumericName() {
        // Given
        String numericName = "789";

        // When
        StompPrincipal principal = new StompPrincipal(numericName);

        // Then
        assertEquals(numericName, principal.getName());
    }

    @Test
    @DisplayName("Constructor should handle empty string name")
    void testConstructor_WithEmptyString() {
        // Given
        String emptyName = "";

        // When
        StompPrincipal principal = new StompPrincipal(emptyName);

        // Then
        assertNotNull(principal);
        assertEquals("", principal.getName());
    }

    /**
     * SECURITY TEST: Validates null handling
     * Important: This tests current behavior. If null should be rejected,
     * add validation in the constructor.
     */
    @Test
    @DisplayName("Constructor should handle null name (current behavior)")
    void testConstructor_WithNullName() {
        // Given
        String nullName = null;

        // When
        StompPrincipal principal = new StompPrincipal(nullName);

        // Then
        assertNotNull(principal);
        assertNull(principal.getName());
        
        // NOTE: If null names should be rejected, modify this test to expect an exception:
        // assertThrows(IllegalArgumentException.class, () -> new StompPrincipal(null));
    }

    @Test
    @DisplayName("Should implement Principal interface correctly")
    void testPrincipalInterface() {
        // Given
        StompPrincipal principal = new StompPrincipal("interfaceTest");

        // When & Then
        assertTrue(principal instanceof Principal);
        assertDoesNotThrow(() -> {
            Principal p = principal;
            String name = p.getName();
        });
    }

    @Test
    @DisplayName("getName() should be immutable - multiple calls return same value")
    void testGetName_IsImmutable() {
        // Given
        String name = "immutableTest";
        StompPrincipal principal = new StompPrincipal(name);

        // When
        String firstCall = principal.getName();
        String secondCall = principal.getName();
        String thirdCall = principal.getName();

        // Then
        assertEquals(firstCall, secondCall);
        assertEquals(secondCall, thirdCall);
        assertEquals(name, firstCall);
    }

    @Test
    @DisplayName("Different principals with same name should have same getName() result")
    void testGetName_Equality() {
        // Given
        String name = "duplicateUser";
        StompPrincipal principal1 = new StompPrincipal(name);
        StompPrincipal principal2 = new StompPrincipal(name);

        // When & Then
        assertEquals(principal1.getName(), principal2.getName());
    }

    @Test
    @DisplayName("Different principals with different names should have different getName() results")
    void testGetName_Inequality() {
        // Given
        StompPrincipal principal1 = new StompPrincipal("user1");
        StompPrincipal principal2 = new StompPrincipal("user2");

        // When & Then
        assertNotEquals(principal1.getName(), principal2.getName());
    }

    @Test
    @DisplayName("Constructor should handle special characters in name")
    void testConstructor_WithSpecialCharacters() {
        // Given
        String specialName = "user@email.com";

        // When
        StompPrincipal principal = new StompPrincipal(specialName);

        // Then
        assertEquals(specialName, principal.getName());
    }

    @Test
    @DisplayName("Constructor should handle whitespace in name")
    void testConstructor_WithWhitespace() {
        // Given
        String nameWithSpaces = "user with spaces";

        // When
        StompPrincipal principal = new StompPrincipal(nameWithSpaces);

        // Then
        assertEquals(nameWithSpaces, principal.getName());
    }

    @Test
    @DisplayName("Constructor should handle very long names")
    void testConstructor_WithLongName() {
        // Given
        String longName = "a".repeat(1000);

        // When
        StompPrincipal principal = new StompPrincipal(longName);

        // Then
        assertEquals(longName, principal.getName());
        assertEquals(1000, principal.getName().length());
    }

    // ==================== Integration Tests ====================

    @Test
    @DisplayName("Principal can be used in authentication context")
    void testUsageAsAuthenticationPrincipal() {
        // Given
        String userId = "authenticatedUser789";
        StompPrincipal principal = new StompPrincipal(userId);

        // When - Simulate authentication check
        Principal authPrincipal = principal;
        String authenticatedName = authPrincipal.getName();

        // Then
        assertEquals(userId, authenticatedName);
    }

    @Test
    @DisplayName("Multiple principals can coexist independently")
    void testMultiplePrincipals() {
        // Given
        StompPrincipal alice = new StompPrincipal("alice");
        StompPrincipal bob = new StompPrincipal("bob");
        StompPrincipal charlie = new StompPrincipal("charlie");

        // When & Then
        assertEquals("alice", alice.getName());
        assertEquals("bob", bob.getName());
        assertEquals("charlie", charlie.getName());
        
        // All are independent
        assertNotSame(alice, bob);
        assertNotSame(bob, charlie);
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("getName() should handle name with only whitespace")
    void testGetName_WithOnlyWhitespace() {
        // Given
        String whitespaceOnly = "   ";
        StompPrincipal principal = new StompPrincipal(whitespaceOnly);

        // When
        String result = principal.getName();

        // Then
        assertEquals(whitespaceOnly, result);
    }

    @Test
    @DisplayName("getName() should handle unicode characters")
    void testGetName_WithUnicode() {
        // Given
        String unicodeName = "用户123";
        StompPrincipal principal = new StompPrincipal(unicodeName);

        // When
        String result = principal.getName();

        // Then
        assertEquals(unicodeName, result);
    }
}