package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@PostgresDataJpaTest
public class SessionRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void saveSession_appliesDefaultValuesAndRequiredFields() {
        Session session = new Session();
        session.setSessionId("ABCD1234");

        entityManager.persist(session);
        entityManager.flush();
        entityManager.clear();

        Session stored = sessionRepository.findBySessionId("ABCD1234");
        assertNotNull(stored);
        assertNotNull(stored.getId());
        assertEquals("ABCD1234", stored.getSessionId());
        assertNotNull(stored.getStartTime());
        assertTrue(stored.getStartTime().isBefore(Instant.now().plusSeconds(1)));
        assertFalse(stored.isEnded());
        assertNotNull(stored.getUserScoresPerRound());
        assertTrue(stored.getUserScoresPerRound().isEmpty());
    }

    @Test
    void findBySessionId_roundScoresPerUserPersistedPerRound() {
        Session session = new Session();
        session.setSessionId("SCORES");
        session.setEnded(true);

        List<Map<Long, Integer>> perRoundScores = new ArrayList<>();
        Map<Long, Integer> round1 = new HashMap<>();
        int round1user1score = 32;
        int round1user2score = 44;
        round1.put(1L, round1user1score);
        round1.put(2L, round1user2score);
        perRoundScores.add(round1);

        Map<Long, Integer> round2 = new HashMap<>();
        int round2user1score = 12;
        int round2user2score = 8;
        round2.put(1L, round2user1score);
        round2.put(2L, round2user2score);
        perRoundScores.add(round2);

        session.setUserScoresPerRound(perRoundScores);

        entityManager.persist(session);
        entityManager.flush();
        entityManager.clear();

        Session found = sessionRepository.findBySessionId("SCORES");
        assertNotNull(found);
        assertTrue(found.isEnded());
        assertEquals(2, found.getUserScoresPerRound().size());
        assertEquals(round1user1score, found.getUserScoresPerRound().get(0).get(1L));
        assertEquals(round1user2score, found.getUserScoresPerRound().get(0).get(2L));
        assertEquals(round2user1score, found.getUserScoresPerRound().get(1).get(1L));
        assertEquals(round2user2score, found.getUserScoresPerRound().get(1).get(2L));
    }
}
