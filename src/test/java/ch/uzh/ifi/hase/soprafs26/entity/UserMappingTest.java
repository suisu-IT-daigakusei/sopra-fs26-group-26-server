package ch.uzh.ifi.hase.soprafs26.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hibernate.annotations.DynamicUpdate;
import org.junit.jupiter.api.Test;

class UserMappingTest {

    @Test
    void userUsesDynamicUpdatesToAvoidCrossFieldLostUpdates() {
        assertTrue(User.class.isAnnotationPresent(DynamicUpdate.class));
    }
}
