package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.Direction;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.Sort;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.UserListPage;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.UserListQuery;
import ch.uzh.ifi.hase.soprafs26.repository.UserListQueryRepository.View;
import ch.uzh.ifi.hase.soprafs26.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@PostgresDataJpaTest
@Import(UserListQueryRepository.class)
class UserListQueryRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserListQueryRepository queryRepository;

    @Test
    void leaderboardRanksAreGlobalAcrossPagesAndFilters() {
        User alpha = persistUser("alpha", UserStatus.ONLINE, 5, 10, 20, 8, 4);
        User bravo = persistUser("bravo", UserStatus.ONLINE, 5, 8, 10, 5, 2);
        User charlie = persistUser("charlie", UserStatus.OFFLINE, 4, 20, 5, 12, 6);
        User delta = persistUser("delta", UserStatus.OFFLINE, 0, 0, 0, 0, 0);
        entityManager.flush();

        UserListPage secondPage = queryRepository.findPage(query(
                View.LEADERBOARD,
                1,
                2,
                "",
                Set.of(),
                false,
                null,
                Set.of(),
                Set.of(),
                Sort.RANK,
                Direction.ASC));

        assertEquals(4L, secondPage.totalElements());
        assertEquals(List.of(charlie.getId(), delta.getId()), userIds(secondPage));
        assertEquals(List.of(3, 4), ranks(secondPage));

        UserListPage filtered = queryRepository.findPage(query(
                View.LEADERBOARD,
                0,
                10,
                "char",
                Set.of(),
                false,
                null,
                Set.of(),
                Set.of(),
                Sort.RANK,
                Direction.ASC));

        assertEquals(1L, filtered.totalElements());
        assertEquals(List.of(charlie.getId()), userIds(filtered));
        assertEquals(List.of(3), ranks(filtered));
    }

    @Test
    void friendsOnlyRequiresReciprocalSelectionAndExcludesPendingOutgoing() {
        User viewer = persistUser("viewer", UserStatus.ONLINE, 0, 0, 0, 0, 0);
        User accepted = persistUser("accepted", UserStatus.ONLINE, 0, 0, 0, 0, 0);
        User pending = persistUser("pending", UserStatus.ONLINE, 0, 0, 0, 0, 0);
        entityManager.flush();

        viewer.setFriendUserIds(new ArrayList<>(List.of(accepted.getId(), pending.getId())));
        accepted.setFriendUserIds(new ArrayList<>(List.of(viewer.getId())));
        pending.setFriendUserIds(new ArrayList<>());
        entityManager.flush();

        UserListPage directory = queryRepository.findPage(query(
                View.DIRECTORY,
                0,
                10,
                "",
                Set.of(),
                true,
                viewer.getId(),
                Set.of(),
                Set.of(),
                Sort.USERNAME,
                Direction.ASC));
        assertEquals(1L, directory.totalElements());
        assertEquals(List.of(accepted.getId()), userIds(directory));

        UserListPage leaderboard = queryRepository.findPage(query(
                View.LEADERBOARD,
                0,
                10,
                "",
                Set.of(),
                true,
                viewer.getId(),
                Set.of(),
                Set.of(),
                Sort.RANK,
                Direction.ASC));
        assertEquals(2L, leaderboard.totalElements());
        assertEquals(Set.of(viewer.getId(), accepted.getId()), Set.copyOf(userIds(leaderboard)));
    }

    @Test
    void directoryAppliesBoundedFiltersAndRatioSortDeterministically() {
        User zeroRounds = persistUser("zero", UserStatus.ONLINE, 0, 0, 0, 0, 0);
        User lowerRate = persistUser("lower", UserStatus.ONLINE, 0, 1, 0, 10, 2);
        User higherRate = persistUser("higher", UserStatus.ONLINE, 0, 1, 0, 10, 8);
        User offline = persistUser("offline", UserStatus.OFFLINE, 0, 0, 0, 10, 10);
        entityManager.flush();

        UserListPage page = queryRepository.findPage(query(
                View.DIRECTORY,
                0,
                10,
                "",
                Set.of(UserStatus.ONLINE),
                false,
                null,
                Set.of(offline.getId()),
                Set.of(zeroRounds.getId(), lowerRate.getId(), higherRate.getId(), offline.getId()),
                Sort.ROUND_WIN_RATE,
                Direction.ASC));

        assertEquals(3L, page.totalElements());
        assertEquals(
                List.of(zeroRounds.getId(), lowerRate.getId(), higherRate.getId()),
                userIds(page));
    }

    @Test
    void visibleStatusSnapshotDrivesFilteringSortingAndReturnedPresence() {
        User stalePlaying = persistUser("stale", UserStatus.PLAYING, 0, 0, 0, 0, 0);
        User waitingPlayer = persistUser("waiting", UserStatus.ONLINE, 0, 0, 0, 0, 0);
        User playingPlayer = persistUser("playing", UserStatus.ONLINE, 0, 0, 0, 0, 0);
        User spectator = persistUser("spectator", UserStatus.ONLINE, 0, 0, 0, 0, 0);
        entityManager.flush();

        persistLobby(
                "WAITVIS",
                "WAITING",
                waitingPlayer.getId(),
                List.of(waitingPlayer.getId()),
                List.of());
        persistLobby(
                "PLAYVIS",
                "PLAYING",
                playingPlayer.getId(),
                List.of(playingPlayer.getId()),
                List.of(spectator.getId()));
        entityManager.flush();

        UserListPage sorted = queryRepository.findPage(query(
                View.DIRECTORY,
                0,
                10,
                "",
                Set.of(),
                false,
                null,
                Set.of(),
                Set.of(),
                Sort.STATUS,
                Direction.ASC));

        assertEquals(
                List.of(waitingPlayer.getId(), stalePlaying.getId(), playingPlayer.getId(), spectator.getId()),
                userIds(sorted));
        assertEquals(
                List.of(UserStatus.LOBBY, UserStatus.ONLINE, UserStatus.PLAYING, UserStatus.SPECTATING),
                sorted.hits().stream()
                        .map(UserListQueryRepository.UserListHit::visibleStatus)
                        .toList());
        assertEquals("WAITVIS", sorted.hits().get(0).joinableSessionId());
        assertNull(sorted.hits().get(1).joinableSessionId());
        assertEquals("PLAYVIS", sorted.hits().get(2).joinableSessionId());
        assertEquals("PLAYVIS", sorted.hits().get(3).joinableSessionId());

        assertEquals(List.of(stalePlaying.getId()), userIds(filterByStatus(UserStatus.ONLINE)));
        assertEquals(List.of(waitingPlayer.getId()), userIds(filterByStatus(UserStatus.LOBBY)));
        assertEquals(List.of(playingPlayer.getId()), userIds(filterByStatus(UserStatus.PLAYING)));
        assertEquals(List.of(spectator.getId()), userIds(filterByStatus(UserStatus.SPECTATING)));
    }

    @Test
    void privateLobbyHidesSessionIdButKeepsVisiblePresenceStatus() {
        User privateWaitingPlayer = persistUser("private-waiting", UserStatus.ONLINE, 0, 0, 0, 0, 0);
        entityManager.flush();

        persistLobby(
                "PRIVATE-SESSION",
                "WAITING",
                privateWaitingPlayer.getId(),
                List.of(privateWaitingPlayer.getId()),
                List.of(),
                false);
        entityManager.flush();

        UserListPage page = queryRepository.findPage(query(
                View.DIRECTORY,
                0,
                10,
                "",
                Set.of(),
                false,
                null,
                Set.of(),
                Set.of(),
                Sort.USERNAME,
                Direction.ASC));

        assertEquals(1L, page.totalElements());
        assertEquals(UserStatus.LOBBY, page.hits().get(0).visibleStatus());
        assertNull(page.hits().get(0).joinableSessionId());
    }

    private User persistUser(
            String username,
            UserStatus status,
            int gamesWon,
            int gamesPlayed,
            int averageSession,
            int roundsPlayed,
            int roundsWon) {
        User user = new User();
        user.setName(username);
        user.setUsername(username);
        user.setToken("token-" + username);
        user.setPassword("password");
        user.setStatus(status);
        user.setCreationDate(LocalDate.now());
        user.setGamesWon(gamesWon);
        user.setGamesPlayed(gamesPlayed);
        user.setAverageScorePerSession(averageSession);
        user.setRoundsPlayed(roundsPlayed);
        user.setRoundsWon(roundsWon);
        return entityManager.persist(user);
    }

    private void persistLobby(
            String sessionId,
            String status,
            Long hostId,
            List<Long> playerIds,
            List<Long> spectatorIds) {
        persistLobby(sessionId, status, hostId, playerIds, spectatorIds, true);
    }

    private void persistLobby(
            String sessionId,
            String status,
            Long hostId,
            List<Long> playerIds,
            List<Long> spectatorIds,
            boolean isPublic) {
        Lobby lobby = new Lobby();
        lobby.setSessionId(sessionId);
        lobby.setStatus(status);
        lobby.setSessionHostUserId(hostId);
        lobby.setIsPublic(isPublic);
        lobby.setPlayerIds(new ArrayList<>(playerIds));
        lobby.setSpectatorIds(new ArrayList<>(spectatorIds));
        entityManager.persist(lobby);
    }

    private UserListPage filterByStatus(UserStatus status) {
        return queryRepository.findPage(query(
                View.DIRECTORY,
                0,
                10,
                "",
                Set.of(status),
                false,
                null,
                Set.of(),
                Set.of(),
                Sort.STATUS,
                Direction.ASC));
    }

    private UserListQuery query(
            View view,
            int page,
            int size,
            String search,
            Set<UserStatus> statuses,
            boolean friendsOnly,
            Long viewerId,
            Set<Long> excludeIds,
            Set<Long> includeIds,
            Sort sort,
            Direction direction) {
        return new UserListQuery(
                view,
                page,
                size,
                search,
                statuses,
                friendsOnly,
                viewerId,
                excludeIds,
                includeIds,
                sort,
                direction);
    }

    private List<Long> userIds(UserListPage page) {
        return page.hits().stream().map(UserListQueryRepository.UserListHit::userId).toList();
    }

    private List<Integer> ranks(UserListPage page) {
        return page.hits().stream().map(UserListQueryRepository.UserListHit::globalRank).toList();
    }
}
