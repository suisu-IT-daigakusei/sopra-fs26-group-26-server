package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.constant.CaboInviteStatus;
import ch.uzh.ifi.hase.soprafs26.entity.CaboInvite;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.CaboInviteRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteCreateDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInvitePendingDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteRespondDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteSentDTO;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Transactional
public class CaboInviteService {
    private static final long INVITE_CLEANUP_MIN_INTERVAL_MS = 30_000L;
    private static final long RESOLVED_INVITE_RETENTION_HOURS = 12L;

    private final CaboInviteRepository caboInviteRepository;
    private final LobbyRepository lobbyRepository;
    private final UserRepository userRepository;
    private final CaboInviteEventPublisher caboInviteEventPublisher;
    private final LobbyService lobbyService;
    private final AtomicLong lastInviteCleanupMs = new AtomicLong(0L);

    public CaboInviteService(CaboInviteRepository caboInviteRepository,
                             LobbyRepository lobbyRepository,
                             UserRepository userRepository,
                             CaboInviteEventPublisher caboInviteEventPublisher,
                             LobbyService lobbyService) {
        this.caboInviteRepository = caboInviteRepository;
        this.lobbyRepository = lobbyRepository;
        this.userRepository = userRepository;
        this.caboInviteEventPublisher = caboInviteEventPublisher;
        this.lobbyService = lobbyService;
    }

    private User getUserByToken(String token) {
        User user = userRepository.findByToken(token);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
        return user;
    }

    private CaboInvitePendingDTO toPendingDto(CaboInvite invite, User fromUser) {
        CaboInvitePendingDTO dto = new CaboInvitePendingDTO();
        dto.setId(invite.getId());
        dto.setFromUserId(fromUser.getId());
        dto.setFromUsername(fromUser.getUsername());
        dto.setFromName(fromUser.getName());
        if (invite.getCreatedAt() != null) {
            dto.setInviteCreationDate(invite.getCreatedAt().toString());
        }
        lobbyService.findLobbyById(invite.getLobbyId()).ifPresent(lobby -> {
            dto.setSessionId(lobby.getSessionId());
            dto.setSessionHostUserId(lobby.getSessionHostUserId());
        });
        return dto;
    }

    private CaboInvitePendingDTO createInviteInternal(String token, CaboInviteCreateDTO body) {
        maybeCleanupStaleInvites();
        if (body == null || body.getToUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "toUserId is required");
        }
        User from = getUserByToken(token);
        Long toUserId = body.getToUserId();
        if (from.getId().equals(toUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot invite yourself");
        }
        User to = userRepository.findById(toUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Lobby waiting = lobbyService.requireWaitingLobbyForHost(from.getId());
        Long lobbyId = waiting.getId();

        boolean targetInAnyPlayingLobby = lobbyRepository.existsByStatusAndPlayerId("PLAYING", toUserId);
        if (targetInAnyPlayingLobby) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is currently playing");
        }

        Map<Long, CaboInvite> latestPerTo = latestInvitePerToUserForLobby(from.getId(), lobbyId);
        long activeSlots = latestPerTo.values().stream()
                .filter(inv -> inv.getStatus() == CaboInviteStatus.PENDING
                        || inv.getStatus() == CaboInviteStatus.ACCEPTED)
                .count();
        CaboInvite latestToTarget = latestPerTo.get(toUserId);
        boolean targetHasActiveSlot = latestToTarget != null
                && (latestToTarget.getStatus() == CaboInviteStatus.PENDING
                || latestToTarget.getStatus() == CaboInviteStatus.ACCEPTED);
        if (activeSlots >= 3 && !targetHasActiveSlot) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Maximum of 3 active invites");
        }

        caboInviteRepository
                .findByFromUserIdAndToUserIdAndLobbyIdAndStatus(
                        from.getId(), toUserId, lobbyId, CaboInviteStatus.PENDING)
                .ifPresent(inv -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Pending invite already exists");
                });

        CaboInvite invite = new CaboInvite();
        invite.setFromUserId(from.getId());
        invite.setToUserId(to.getId());
        invite.setLobbyId(lobbyId);
        invite.setStatus(CaboInviteStatus.PENDING);
        invite = caboInviteRepository.save(invite);

        CaboInvitePendingDTO dto = toPendingDto(invite, from);
        caboInviteEventPublisher.publishToInviteeAfterCommit(to.getId(), dto);
        return dto;
    }

    private List<CaboInviteSentDTO> listSentForHost(User host) {
        return lobbyService.findWaitingLobbyForHost(host.getId())
                .map(waiting -> latestInvitePerToUserForLobby(host.getId(), waiting.getId()).values().stream()
                        .map(this::toSentDto)
                        .toList())
                .orElse(List.of());
    }

    public List<CaboInviteSentDTO> getSentInvitesForUser(String token, Long userId) {
        maybeCleanupStaleInvites();
        User host = getUserByToken(token);
        if (!host.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized access!");
        }
        return listSentForHost(host);
    }

    private Map<Long, CaboInvite> latestInvitePerToUserForLobby(Long fromUserId, Long lobbyId) {
        List<CaboInvite> all = caboInviteRepository.findByFromUserIdAndLobbyIdOrderByCreatedAtDesc(fromUserId, lobbyId);
        Map<Long, CaboInvite> latestPerTo = new LinkedHashMap<>();
        for (CaboInvite inv : all) {
            latestPerTo.putIfAbsent(inv.getToUserId(), inv);
        }
        return latestPerTo;
    }

    private CaboInviteSentDTO toSentDto(CaboInvite inv) {
        CaboInviteSentDTO dto = new CaboInviteSentDTO();
        dto.setToUserId(inv.getToUserId());
        String status = inv.getStatus().name();
        if (inv.getStatus() == CaboInviteStatus.ACCEPTED) {
            String stillInWaitingLobby = lobbyService.getWaitingSessionIdIfPlayerInLobby(inv.getLobbyId(), inv.getToUserId());
            if (stillInWaitingLobby == null || stillInWaitingLobby.isBlank()) {
                status = CaboInviteStatus.DECLINED.name();
            }
        }
        dto.setStatus(status);
        userRepository.findById(inv.getToUserId()).ifPresent(u -> dto.setToUsername(u.getUsername()));
        return dto;
    }

    private List<CaboInvitePendingDTO> listPendingForInvitee(User invitee) {
        return caboInviteRepository
                .findByToUserIdAndStatusOrderByCreatedAtAsc(invitee.getId(), CaboInviteStatus.PENDING)
                .stream()
                .filter(inv -> lobbyService.isLobbyWaiting(inv.getLobbyId()))
                .flatMap(inv -> userRepository.findById(inv.getFromUserId()).stream()
                        .map(from -> toPendingDto(inv, from)))
                .toList();
    }

    public List<CaboInvitePendingDTO> getPendingInvitesForUser(String token, Long userId) {
        maybeCleanupStaleInvites();
        User invitee = getUserByToken(token);
        if (!invitee.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized access!");
        }
        return listPendingForInvitee(invitee);
    }

    public CaboInvitePendingDTO createInviteAsUser(String token, Long userId, CaboInviteCreateDTO body) {
        User from = getUserByToken(token);
        if (!from.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized access!");
        }
        return createInviteInternal(token, body);
    }

    public CaboInviteRespondDTO respondForUser(String token, Long userId, Long inviteId,
                                               CaboInviteDecisionDTO body) {
        User invitee = getUserByToken(token);
        if (!invitee.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized access!");
        }
        return respondInternal(invitee, inviteId, body);
    }

    private CaboInviteRespondDTO respondInternal(User invitee, Long inviteId, CaboInviteDecisionDTO body) {
        if (body == null || body.getDecision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision is required");
        }
        CaboInvite invite = caboInviteRepository.findById(inviteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
        if (!invite.getToUserId().equals(invitee.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your invite");
        }
        if (invite.getStatus() != CaboInviteStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invite already resolved");
        }
        if (!lobbyService.isLobbyWaiting(invite.getLobbyId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lobby is no longer available");
        }
        String d = body.getDecision().trim().toUpperCase();
        if ("ACCEPT".equals(d)) {
            if (lobbyService.isUserInActiveGame(invitee.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot join a lobby during an active game");
            }
            invite.setStatus(CaboInviteStatus.ACCEPTED);
        } else if ("DECLINE".equals(d)) {
            invite.setStatus(CaboInviteStatus.DECLINED);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be ACCEPT or DECLINE");
        }
        caboInviteRepository.save(invite);

        CaboInviteSentDTO sent = toSentDto(invite);
        caboInviteEventPublisher.publishToInviterAfterCommit(invite.getFromUserId(), sent);

        CaboInviteRespondDTO out = new CaboInviteRespondDTO();
        if (invite.getStatus() == CaboInviteStatus.ACCEPTED) {
            Long lobbyId = invite.getLobbyId();
            lobbyService.addPlayerToLobby(lobbyId, invite.getFromUserId(), invite.getToUserId());
            out.setWaitingLobbySessionId(
                    lobbyService.getWaitingSessionIdIfPlayerInLobby(lobbyId, invite.getToUserId()));
        }
        return out;
    }

    public void deleteInviteForUser(String token, Long userId, Long inviteId) {
        maybeCleanupStaleInvites();
        User user = getUserByToken(token);
        if (!user.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized access!");
        }
        CaboInvite invite = caboInviteRepository.findById(inviteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
        if (!invite.getFromUserId().equals(user.getId()) && !invite.getToUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden!");
        }
        if (invite.getStatus() != CaboInviteStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invite already resolved");
        }
        caboInviteRepository.delete(invite);
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupStaleInvitesJob() {
        maybeCleanupStaleInvites();
    }

    private void maybeCleanupStaleInvites() {
        long nowMs = System.currentTimeMillis();
        long previousCleanupMs = lastInviteCleanupMs.get();
        if (nowMs - previousCleanupMs < INVITE_CLEANUP_MIN_INTERVAL_MS) {
            return;
        }
        if (!lastInviteCleanupMs.compareAndSet(previousCleanupMs, nowMs)) {
            return;
        }

        List<CaboInvite> pendingInvites = caboInviteRepository.findByStatus(CaboInviteStatus.PENDING);
        List<CaboInvite> stalePendingInvites = new ArrayList<>();
        Set<Long> lobbyIds = new HashSet<>();
        for (CaboInvite invite : pendingInvites) {
            if (invite != null && invite.getLobbyId() != null) {
                lobbyIds.add(invite.getLobbyId());
            }
        }
        Set<Long> waitingLobbyIds = new HashSet<>();
        for (Lobby lobby : lobbyRepository.findAllById(lobbyIds)) {
            if (lobby != null && lobby.getId() != null && "WAITING".equals(lobby.getStatus())) {
                waitingLobbyIds.add(lobby.getId());
            }
        }
        for (CaboInvite invite : pendingInvites) {
            if (invite == null || invite.getLobbyId() == null) {
                continue;
            }
            if (!waitingLobbyIds.contains(invite.getLobbyId())) {
                invite.setStatus(CaboInviteStatus.DECLINED);
                stalePendingInvites.add(invite);
            }
        }
        if (!stalePendingInvites.isEmpty()) {
            caboInviteRepository.saveAll(stalePendingInvites);
        }

        LocalDateTime cutoff = LocalDateTime.now().minusHours(RESOLVED_INVITE_RETENTION_HOURS);
        caboInviteRepository.deleteByStatusInAndCreatedAtBefore(
                List.of(CaboInviteStatus.ACCEPTED, CaboInviteStatus.DECLINED),
                cutoff);
    }
}
