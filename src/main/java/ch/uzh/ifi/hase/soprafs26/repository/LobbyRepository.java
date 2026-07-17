package ch.uzh.ifi.hase.soprafs26.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;

import java.util.List;
import java.util.Optional;

@Repository("lobbyRepository")
public interface LobbyRepository extends JpaRepository<Lobby, Long> {
    Lobby findBySessionId(String sessionId); // automatically implemented by spring

    /** Serializes game start attempts for one lobby inside the caller's transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lobby l where l.sessionId = :sessionId")
    Optional<Lobby> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lobby l where l.id = :lobbyId")
    Optional<Lobby> findByIdForUpdate(@Param("lobbyId") Long lobbyId);

    List<Lobby> findBySessionHostUserId(Long sessionHostUserId);

    List<Lobby> findByStatus(String status);

    List<Lobby> findByStatusAndPlayerSetKey(String status, String playerSetKey);

    List<Lobby> findByIsPublicTrueAndStatus(String status);

    @Query("select case when count(l) > 0 then true else false end from Lobby l where l.status = :status and :userId member of l.playerIds")
    boolean existsByStatusAndPlayerId(@Param("status") String status, @Param("userId") Long userId);

    @Query("select l from Lobby l where l.status = :status and :userId member of l.playerIds")
    List<Lobby> findByStatusAndPlayerId(@Param("status") String status, @Param("userId") Long userId);

    // #116 lobby queries by lobby status and participant id (participant: player or spectator)
    @Query("select case when count(l) > 0 then true else false end from Lobby l where l.status = :status and "
            + "(:userId member of l.playerIds or :userId member of l.spectatorIds)")
    boolean existsByStatusAndParticipantId(@Param("status") String status, @Param("userId") Long userId);

    /** Resolves PLAYING before WAITING and player before spectator in one indexed query. */
    @Query(value = """
            SELECT membership.lobby_status, membership.is_player
            FROM (
                SELECT l.id AS lobby_id, l.status AS lobby_status, TRUE AS is_player
                FROM lobbies l
                JOIN lobby_player_ids players ON players.lobby_id = l.id
                WHERE players.player_ids = :userId AND l.status IN ('PLAYING', 'WAITING')
                UNION ALL
                SELECT l.id AS lobby_id, l.status AS lobby_status, FALSE AS is_player
                FROM lobbies l
                JOIN lobby_spectator_ids spectators ON spectators.lobby_id = l.id
                WHERE spectators.spectator_ids = :userId AND l.status IN ('PLAYING', 'WAITING')
            ) membership
            ORDER BY CASE membership.lobby_status WHEN 'PLAYING' THEN 0 ELSE 1 END,
                     CASE WHEN membership.is_player THEN 0 ELSE 1 END,
                     membership.lobby_id
            LIMIT 1
            """, nativeQuery = true)
    List<Object[]> findHighestPriorityPresenceForUser(@Param("userId") Long userId);

    @Query("select l from Lobby l where l.status = :status and "
            + "(:userId member of l.playerIds or :userId member of l.spectatorIds)")
    List<Lobby> findByStatusAndParticipantId(@Param("status") String status, @Param("userId") Long userId);

    @Query("select case when count(l) > 0 then true else false end from Lobby l where l.sessionId = :sessionId and "
            + "(:userId member of l.playerIds or :userId member of l.spectatorIds)")
    boolean existsBySessionIdAndParticipantId(@Param("sessionId") String sessionId, @Param("userId") Long userId);
}
