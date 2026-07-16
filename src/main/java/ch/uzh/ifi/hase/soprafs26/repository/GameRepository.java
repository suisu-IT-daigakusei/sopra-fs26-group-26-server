package ch.uzh.ifi.hase.soprafs26.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import java.time.Instant;
import java.util.List;

@Repository("gameRepository")
public interface GameRepository extends JpaRepository<Game, String> {
    @Query(value = "SELECT * FROM games "
            + "WHERE status <> :excludedStatusName "
            + "AND status <> :excludedStatusOrdinal "
            + "AND ordered_player_ids @> jsonb_build_array(CAST(:playerId AS bigint))",
            nativeQuery = true)
    List<Game> findGamesByPlayerIdExcludingStatus(@Param("playerId") Long playerId,
                                                   @Param("excludedStatusName") String excludedStatusName,
                                                   @Param("excludedStatusOrdinal") String excludedStatusOrdinal);

    List<Game> findTop200ByStatusAndRoundEndedAtBeforeOrderByRoundEndedAtAsc(GameStatus status, Instant cutoff);

    default List<Game> findGamesByPlayerId(Long playerId) {
        if (playerId == null) {
            return List.of();
        }
        return findGamesByPlayerIdExcludingStatus(
                playerId,
                GameStatus.ROUND_ENDED.name(),
                String.valueOf(GameStatus.ROUND_ENDED.ordinal())
        );
    }
    
}
