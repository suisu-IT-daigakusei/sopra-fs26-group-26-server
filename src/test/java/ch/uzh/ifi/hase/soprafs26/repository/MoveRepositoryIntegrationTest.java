package ch.uzh.ifi.hase.soprafs26.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import ch.uzh.ifi.hase.soprafs26.entity.Move;
import ch.uzh.ifi.hase.soprafs26.support.PostgresDataJpaTest;

import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@PostgresDataJpaTest
public class MoveRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private MoveRepository moveRepository;

    // integration test with real database persistence
    @Test
    public void isPublic_defaultFalse_correctPersistence() {
        Move defaultMove = new Move();
        defaultMove.setSessionId("S-DEF");
        defaultMove.setUserId(1L);
        defaultMove.setActionType("DRAW");
        entityManager.persist(defaultMove);
        entityManager.flush();
        entityManager.clear();

        Move reloadedDefault = moveRepository.findById(defaultMove.getId()).orElseThrow();
        assertNotNull(reloadedDefault.getIsPublic());
        assertEquals(false, reloadedDefault.getIsPublic());

        Move publicMove = new Move();
        publicMove.setSessionId("S-PUB");
        publicMove.setUserId(2L);
        publicMove.setActionType("PEEK");
        publicMove.setIsPublic(true);
        entityManager.persist(publicMove);
        entityManager.flush();
        entityManager.clear();

        Move reloadedPublicMove = moveRepository.findById(publicMove.getId()).orElseThrow();
        assertEquals(true, reloadedPublicMove.getIsPublic());
    }

    @Test
    public void testSaveMoveAndFindChronologicallyOrByUser() {
        // 1. Setup: Zwei Spielzüge (Moves) für dieselbe Session erstellen
        String testSessionId = "session-123-abc";
        Long testUserId = 99L;

        Move move1 = new Move();
        move1.setSessionId(testSessionId);
        move1.setUserId(testUserId);
        move1.setActionType("DRAW");
        move1.setTimestamp(Instant.now().minusSeconds(10)); // Vor 10 Sekunden

        Move move2 = new Move();
        move2.setSessionId(testSessionId);
        move2.setUserId(testUserId);
        move2.setActionType("SWAP");
        move2.setTimestamp(Instant.now()); // Jetzt

        // In die In-Memory-Datenbank flushen
        entityManager.persist(move1);
        entityManager.persist(move2);
        entityManager.flush();

        // 2. Ausführung & Test von Methode 1: Chronologische Sortierung
        List<Move> orderedMoves = moveRepository.findBySessionIdOrderByTimestampAsc(testSessionId);
        
        assertEquals(2, orderedMoves.size(), "Es sollten 2 Moves in der Session gefunden werden.");
        assertEquals("DRAW", orderedMoves.get(0).getActionType(), "Der ältere Move (DRAW) muss an erster Stelle stehen.");
        assertEquals("SWAP", orderedMoves.get(1).getActionType(), "Der neuere Move (SWAP) muss an zweiter Stelle stehen.");

        // 3. Ausführung & Test von Methode 2: Filterung nach Session und User
        List<Move> userMoves = moveRepository.findBySessionIdAndUserId(testSessionId, testUserId);
        
        assertNotNull(userMoves, "Die Liste der User-Moves darf nicht null sein.");
        assertEquals(2, userMoves.size(), "Der User sollte genau 2 Moves in dieser Session haben.");
    }

}
