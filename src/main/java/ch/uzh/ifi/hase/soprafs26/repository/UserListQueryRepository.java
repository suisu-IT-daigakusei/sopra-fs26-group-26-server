package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded native queries for user directory pages. Only IDs and the computed
 * global rank are selected here; UserService loads the corresponding entities
 * in one JPA query so entity/collection mapping remains centralized.
 */
@Repository
public class UserListQueryRepository {

    private static final String VISIBLE_USERS_CTE = """
            WITH membership_candidates AS (
                SELECT player.player_ids AS user_id,
                       lobby.id AS lobby_id,
                       lobby.session_id,
                       lobby.status AS lobby_status,
                       lobby.is_public,
                       false AS is_spectator
                FROM lobbies lobby
                JOIN lobby_player_ids player ON player.lobby_id = lobby.id
                WHERE lobby.status IN ('PLAYING', 'WAITING')
                  AND player.player_ids IS NOT NULL
                UNION ALL
                SELECT spectator.spectator_ids AS user_id,
                       lobby.id AS lobby_id,
                       lobby.session_id,
                       lobby.status AS lobby_status,
                       lobby.is_public,
                       true AS is_spectator
                FROM lobbies lobby
                JOIN lobby_spectator_ids spectator ON spectator.lobby_id = lobby.id
                WHERE lobby.status IN ('PLAYING', 'WAITING')
                  AND spectator.spectator_ids IS NOT NULL
            ),
            ranked_presence AS (
                SELECT candidate.*,
                       row_number() OVER (
                           PARTITION BY candidate.user_id
                           ORDER BY CASE candidate.lobby_status
                                        WHEN 'PLAYING' THEN 0
                                        ELSE 1
                                    END,
                                    candidate.lobby_id DESC,
                                    candidate.is_spectator DESC
                       ) AS presence_rank
                FROM membership_candidates candidate
            ),
            current_presence AS (
                SELECT user_id, session_id, lobby_status, is_public, is_spectator
                FROM ranked_presence
                WHERE presence_rank = 1
            ),
            visible_users AS (
                SELECT stored_user.*,
                       CASE
                           WHEN presence.lobby_status = 'PLAYING' AND presence.is_spectator
                               THEN 'SPECTATING'
                           WHEN presence.lobby_status = 'PLAYING'
                               THEN 'PLAYING'
                           WHEN presence.lobby_status = 'WAITING' AND presence.is_spectator
                               THEN 'SPECTATING'
                           WHEN presence.lobby_status = 'WAITING'
                               THEN 'LOBBY'
                           WHEN stored_user.status IN ('LOBBY', 'PLAYING', 'SPECTATING')
                               THEN 'ONLINE'
                           ELSE stored_user.status
                       END AS visible_status,
                       CASE
                           WHEN presence.is_public IS TRUE THEN presence.session_id
                           ELSE NULL
                       END AS joinable_session_id
                FROM users stored_user
                LEFT JOIN current_presence presence ON presence.user_id = stored_user.id
            )
            """;

    public enum View {
        DIRECTORY,
        LEADERBOARD
    }

    public enum Sort {
        USERNAME,
        ROUNDS_PLAYED,
        AVERAGE_SCORE,
        ROUND_WIN_RATE,
        STATUS,
        RANK,
        GAMES_WIN_RATE
    }

    public enum Direction {
        ASC,
        DESC
    }

    public record UserListQuery(
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

        public UserListQuery {
            view = view == null ? View.DIRECTORY : view;
            search = search == null ? "" : search.trim();
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
            excludeIds = sanitizeIds(excludeIds);
            includeIds = sanitizeIds(includeIds);
            sort = sort == null
                    ? (view == View.LEADERBOARD ? Sort.RANK : Sort.USERNAME)
                    : sort;
            direction = direction == null ? Direction.ASC : direction;
        }

        private static Set<Long> sanitizeIds(Set<Long> ids) {
            if (ids == null || ids.isEmpty()) {
                return Set.of();
            }
            LinkedHashSet<Long> sanitized = new LinkedHashSet<>();
            for (Long id : ids) {
                if (id != null && id > 0) {
                    sanitized.add(id);
                }
            }
            return Set.copyOf(sanitized);
        }
    }

    public record UserListHit(
            Long userId,
            Integer globalRank,
            UserStatus visibleStatus,
            String joinableSessionId) {

        public UserListHit(Long userId, Integer globalRank) {
            this(userId, globalRank, null, null);
        }
    }

    public record UserListPage(List<UserListHit> hits, long totalElements) {
        public UserListPage {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UserListQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UserListPage findPage(UserListQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("User list query is required");
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource();
        String whereClause = buildWhereClause(query, parameters);
        long totalElements = queryForCount(whereClause, parameters);
        if (totalElements == 0L || (long) query.page() * query.size() >= totalElements) {
            return new UserListPage(List.of(), totalElements);
        }

        StringBuilder sql = new StringBuilder(VISIBLE_USERS_CTE);
        if (query.view() == View.LEADERBOARD) {
            sql.append("""
                    , ranked_users AS (
                        SELECT u.id,
                               row_number() OVER (
                                   ORDER BY u.games_won DESC,
                                            u.games_played DESC,
                                            u.average_score_per_session ASC,
                                            u.id ASC
                               ) AS global_rank
                        FROM visible_users u
                    )
                    SELECT u.id AS user_id,
                           r.global_rank::integer AS global_rank,
                           u.visible_status,
                           u.joinable_session_id
                    FROM visible_users u
                    JOIN ranked_users r ON r.id = u.id
                    """);
        } else {
            sql.append("""
                    SELECT u.id AS user_id,
                           NULL::integer AS global_rank,
                           u.visible_status,
                           u.joinable_session_id
                    FROM visible_users u
                    """);
        }
        sql.append(whereClause)
                .append(" ORDER BY ")
                .append(resolveOrderBy(query))
                .append(" LIMIT :pageSize OFFSET :pageOffset");

        parameters.addValue("pageSize", query.size());
        parameters.addValue("pageOffset", (long) query.page() * query.size());

        List<UserListHit> hits = jdbcTemplate.query(
                sql.toString(),
                parameters,
                (resultSet, rowNumber) -> new UserListHit(
                        resultSet.getLong("user_id"),
                        resultSet.getObject("global_rank", Integer.class),
                        UserStatus.valueOf(resultSet.getString("visible_status")),
                        resultSet.getString("joinable_session_id")));
        return new UserListPage(hits, totalElements);
    }

    private long queryForCount(String whereClause, MapSqlParameterSource parameters) {
        Long count = jdbcTemplate.queryForObject(
                VISIBLE_USERS_CTE + " SELECT count(*) FROM visible_users u " + whereClause,
                parameters,
                Long.class);
        return count == null ? 0L : count;
    }

    private String buildWhereClause(UserListQuery query, MapSqlParameterSource parameters) {
        List<String> predicates = new ArrayList<>();
        if (!query.search().isBlank()) {
            parameters.addValue("search", "%" + query.search().toLowerCase(Locale.ROOT) + "%");
            predicates.add("""
                    (lower(u.username) LIKE :search
                     OR lower(coalesce(u.name, '')) LIKE :search
                     OR lower(u.visible_status) LIKE :search)
                    """.trim());
        }
        if (!query.statuses().isEmpty()) {
            parameters.addValue(
                    "statuses",
                    query.statuses().stream().map(UserStatus::name).toList());
            predicates.add("u.visible_status IN (:statuses)");
        }
        if (!query.includeIds().isEmpty()) {
            parameters.addValue("includeIds", query.includeIds());
            predicates.add("u.id IN (:includeIds)");
        }
        if (!query.excludeIds().isEmpty()) {
            parameters.addValue("excludeIds", query.excludeIds());
            predicates.add("u.id NOT IN (:excludeIds)");
        }
        if (query.friendsOnly()) {
            if (query.viewerId() == null) {
                throw new IllegalArgumentException("Viewer id is required for friends-only queries");
            }
            parameters.addValue("viewerId", query.viewerId());
            String friendsPredicate = """
                    (EXISTS (
                        SELECT 1
                        FROM user_friend_ids viewer_selection
                        WHERE viewer_selection.user_id = :viewerId
                          AND viewer_selection.friend_user_id = u.id
                    )
                    AND EXISTS (
                        SELECT 1
                        FROM user_friend_ids target_selection
                        WHERE target_selection.user_id = u.id
                          AND target_selection.friend_user_id = :viewerId
                    ))
                    """.trim();
            if (query.view() == View.LEADERBOARD) {
                friendsPredicate = "(" + friendsPredicate + " OR u.id = :viewerId)";
            }
            predicates.add(friendsPredicate);
        }
        return predicates.isEmpty() ? "" : "WHERE " + String.join(" AND ", predicates);
    }

    private String resolveOrderBy(UserListQuery query) {
        if (query.sort() == Sort.RANK && query.view() != View.LEADERBOARD) {
            throw new IllegalArgumentException("Rank sorting is only available for leaderboard pages");
        }

        String expression = switch (query.sort()) {
            case USERNAME -> "lower(u.username)";
            case ROUNDS_PLAYED -> "u.rounds_played";
            case AVERAGE_SCORE -> "u.average_score_per_round";
            case ROUND_WIN_RATE -> "CASE WHEN u.rounds_played > 0 "
                    + "THEN u.rounds_won::numeric / u.rounds_played ELSE NULL END";
            case STATUS -> "u.visible_status";
            case RANK -> "r.global_rank";
            case GAMES_WIN_RATE -> "CASE WHEN u.games_played > 0 "
                    + "THEN u.games_won::numeric / u.games_played ELSE NULL END";
        };
        String direction = query.direction() == Direction.DESC ? "DESC" : "ASC";
        String nullOrdering = (query.sort() == Sort.ROUND_WIN_RATE || query.sort() == Sort.GAMES_WIN_RATE)
                ? (query.direction() == Direction.DESC ? " NULLS LAST" : " NULLS FIRST")
                : "";
        return expression + " " + direction + nullOrdering + ", u.id ASC";
    }
}
