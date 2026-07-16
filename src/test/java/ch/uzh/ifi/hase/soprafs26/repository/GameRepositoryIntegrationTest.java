package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@PostgresDataJpaTest
public class GameRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private GameRepository gameRepository;

    @Test
    public void findGamesByPlayerId_excludesRoundEnded_andAvoidsPartialIdMatches() {
        Game activeForPlayerOne = new Game();
        activeForPlayerOne.setStatus(GameStatus.ROUND_ACTIVE);
        activeForPlayerOne.setOrderedPlayerIds(List.of(1L, 2L));
        entityManager.persist(activeForPlayerOne);

        Game endedForPlayerOne = new Game();
        endedForPlayerOne.setStatus(GameStatus.ROUND_ENDED);
        endedForPlayerOne.setOrderedPlayerIds(List.of(1L, 3L));
        entityManager.persist(endedForPlayerOne);

        Game activeForDifferentPlayer = new Game();
        activeForDifferentPlayer.setStatus(GameStatus.ROUND_ACTIVE);
        activeForDifferentPlayer.setOrderedPlayerIds(List.of(10L, 11L));
        entityManager.persist(activeForDifferentPlayer);

        entityManager.flush();

        List<Game> result = gameRepository.findGamesByPlayerId(1L);

        assertEquals(1, result.size());
        assertTrue(result.stream().anyMatch(game -> game.getId().equals(activeForPlayerOne.getId())));
    }
}
