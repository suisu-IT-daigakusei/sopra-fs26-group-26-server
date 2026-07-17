package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.entity.Move;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface MoveRepository extends JpaRepository<Move, Long> {
    // finds all moves of a session in chronological order
    List<Move> findBySessionIdOrderByTimestampAsc(String sessionId);

    // fast path for UI logs: only the newest N moves are needed.
    List<Move> findTop500BySessionIdOrderByTimestampDesc(String sessionId);
    
    // finds all moves of a specific user in a session
    List<Move> findBySessionIdAndUserId(String sessionId, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Move m where m.sessionId = :sessionId")
    int deleteAllBySessionIdBulk(@Param("sessionId") String sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Move m where m.sessionId in :sessionIds")
    int deleteAllBySessionIdsBulk(@Param("sessionIds") List<String> sessionIds);

    /** Deletes a real page of stale moves, so cleanup always advances. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM moves WHERE id IN ("
            + "SELECT m.id FROM moves m "
            + "JOIN sessions s ON s.session_id = m.session_id "
            + "WHERE s.is_ended = true AND s.start_time < :cutoff "
            + "ORDER BY s.start_time ASC, m.id ASC LIMIT :batchSize)",
            nativeQuery = true)
    int deleteStaleEndedSessionMovesBatch(@Param("cutoff") Instant cutoff,
                                          @Param("batchSize") int batchSize);
}
