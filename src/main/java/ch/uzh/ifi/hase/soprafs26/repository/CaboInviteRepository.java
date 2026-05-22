package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.constant.CaboInviteStatus;
import ch.uzh.ifi.hase.soprafs26.entity.CaboInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface CaboInviteRepository extends JpaRepository<CaboInvite, Long> {

    List<CaboInvite> findByToUserIdAndStatusOrderByCreatedAtAsc(Long toUserId, CaboInviteStatus status);

    Optional<CaboInvite> findByFromUserIdAndToUserIdAndLobbyIdAndStatus(
            Long fromUserId, Long toUserId, Long lobbyId, CaboInviteStatus status);

    List<CaboInvite> findByFromUserIdAndLobbyIdOrderByCreatedAtDesc(Long fromUserId, Long lobbyId);

    List<CaboInvite> findByStatus(CaboInviteStatus status);

    long deleteByStatusInAndCreatedAtBefore(List<CaboInviteStatus> statuses, LocalDateTime cutoff);
}
