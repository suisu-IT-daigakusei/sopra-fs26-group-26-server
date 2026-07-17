package ch.uzh.ifi.hase.soprafs26.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class RoundStatBackfillMigrationIntegrationTest {

    @Test
    void v5BackfillsNormalAndTiedRoundWinnersAndPreservesUsersWithoutHistory()
            throws Exception {
        try (PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            postgres.start();

            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())
                    .target(MigrationVersion.fromVersion("4"))
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO users (
                            id, username, token, status, creation_date, password,
                            rounds_won, average_score_per_round
                        ) VALUES
                            (101, 'winner-a', 'token-101', 'OFFLINE',
                             DATE '2026-01-01', 'hash', 99, 99),
                            (102, 'winner-b', 'token-102', 'OFFLINE',
                             DATE '2026-01-01', 'hash', 99, 99),
                            (103, 'winner-c', 'token-103', 'OFFLINE',
                             DATE '2026-01-01', 'hash', 99, 99),
                            (104, 'no-history', 'token-104', 'OFFLINE',
                             DATE '2026-01-01', 'hash', 7, 88)
                        """);

                statement.executeUpdate("""
                        INSERT INTO sessions (
                            session_id, start_time, user_scores_per_round
                        ) VALUES
                            (
                                'MIGRATION-HISTORY-1',
                                CURRENT_TIMESTAMP,
                                '[
                                  {"101": 5, "102": 9, "103": 5},
                                  {"101": 12, "102": 3},
                                  {},
                                  null,
                                  {"101": null},
                                  {"101": 7}
                                ]'::jsonb
                            ),
                            (
                                'MIGRATION-HISTORY-2',
                                CURRENT_TIMESTAMP,
                                '[{"101": 4, "102": 4, "103": 8}]'::jsonb
                            )
                        """);
            }

            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())
                    .load()
                    .migrate();

            Map<Long, RoundStats> statsByUser = new HashMap<>();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword());
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("""
                         SELECT id, rounds_played,
                                total_round_points_accumulated,
                                average_score_per_round, rounds_won
                         FROM users
                         ORDER BY id
                         """)) {
                while (resultSet.next()) {
                    statsByUser.put(
                            resultSet.getLong("id"),
                            new RoundStats(
                                    resultSet.getInt("rounds_played"),
                                    resultSet.getInt("total_round_points_accumulated"),
                                    resultSet.getInt("average_score_per_round"),
                                    resultSet.getInt("rounds_won")));
                }
            }

            assertEquals(new RoundStats(4, 28, 7, 3), statsByUser.get(101L));
            assertEquals(new RoundStats(3, 16, 5, 2), statsByUser.get(102L));
            assertEquals(new RoundStats(2, 13, 7, 1), statsByUser.get(103L));
            assertEquals(new RoundStats(0, 0, 88, 7), statsByUser.get(104L));
        }
    }

    private record RoundStats(
            int roundsPlayed,
            int totalRoundPoints,
            int averageRoundScore,
            int roundsWon) {
    }
}
