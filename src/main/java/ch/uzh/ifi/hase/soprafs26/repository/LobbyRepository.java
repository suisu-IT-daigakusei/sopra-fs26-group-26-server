package ch.uzh.ifi.hase.soprafs26.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;

import java.util.List;

@Repository("lobbyRepository")
public interface LobbyRepository extends JpaRepository<Lobby, Long> {
    Lobby findBySessionId(String sessionId); // automatically implemented by spring

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

    @Query("select l from Lobby l where l.status = :status and "
            + "(:userId member of l.playerIds or :userId member of l.spectatorIds)")
    List<Lobby> findByStatusAndParticipantId(@Param("status") String status, @Param("userId") Long userId);

    @Query("select case when count(l) > 0 then true else false end from Lobby l where l.sessionId = :sessionId and "
            + "(:userId member of l.playerIds or :userId member of l.spectatorIds)")
    boolean existsBySessionIdAndParticipantId(@Param("sessionId") String sessionId, @Param("userId") Long userId);
}
