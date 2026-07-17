package ch.uzh.ifi.hase.soprafs26.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionHistoryQueryRepository {
    private static final String RECENT_SESSION_IDS_SQL = """
            SELECT s.id
            FROM sessions s
            WHERE s.total_score_by_user_id ?? CAST(? AS text)
            ORDER BY s.start_time DESC NULLS LAST, s.id DESC
            LIMIT ?
            OFFSET ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public SessionHistoryQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Long> findRecentSessionIdsForUser(Long userId, int limit, int offset) {
        return jdbcTemplate.query(
                RECENT_SESSION_IDS_SQL,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                userId.toString(),
                limit,
                offset);
    }
}
