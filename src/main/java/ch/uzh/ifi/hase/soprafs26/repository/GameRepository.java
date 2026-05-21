package ch.uzh.ifi.hase.soprafs26.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ch.uzh.ifi.hase.soprafs26.entity.Game;
import java.util.List;

@Repository("gameRepository")
public interface GameRepository extends JpaRepository<Game, String> {
    
    // find all active games a player is part of
    @Query(value = "SELECT * FROM games WHERE status <> 'ROUND_ENDED' "
            + "AND CAST(ordered_player_ids AS VARCHAR) LIKE CONCAT('%', :playerId, '%')",
            nativeQuery = true)
    List<Game> findGamesByPlayerId(@Param("playerId") Long playerId);
    
}
