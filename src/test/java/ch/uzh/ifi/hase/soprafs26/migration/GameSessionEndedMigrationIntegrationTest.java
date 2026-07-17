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

class GameSessionEndedMigrationIntegrationTest {

    @Test
    void v6BackfillsOnlyEndedSessionsInPostScoreGamePhases() throws Exception {
        try (PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            postgres.start();

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target(MigrationVersion.fromVersion("5"))
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO sessions (session_id, start_time, is_ended) VALUES
                            ('ENDED-SESSION', CURRENT_TIMESTAMP, true),
                            ('LIVE-SESSION', CURRENT_TIMESTAMP, false)
                        """);
                statement.executeUpdate("""
                        INSERT INTO lobbies (
                            session_id, session_host_user_id, status, player_set_key
                        ) VALUES
                            ('ENDED-SESSION', 1, 'PLAYING', '1,2'),
                            ('LIVE-SESSION', 5, 'PLAYING', '5,6')
                        """);
                statement.executeUpdate("""
                        INSERT INTO games (id, ordered_player_ids, status) VALUES
                            ('ended-rematch', '[2,1]'::jsonb, 'ROUND_AWAITING_REMATCH'),
                            ('ended-reveal', '[1,2]'::jsonb, 'CABO_REVEAL'),
                            ('ended-active', '[1,2]'::jsonb, 'ROUND_ACTIVE'),
                            ('different-players', '[3,4]'::jsonb, 'CABO_REVEAL'),
                            ('live-reveal', '[5,6]'::jsonb, 'CABO_REVEAL')
                        """);
            }

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .load()
                    .migrate();

            Map<String, Boolean> endedByGameId = new HashMap<>();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT id, session_ended FROM games ORDER BY id")) {
                while (resultSet.next()) {
                    endedByGameId.put(
                            resultSet.getString("id"), resultSet.getBoolean("session_ended"));
                }
            }

            assertEquals(true, endedByGameId.get("ended-rematch"));
            assertEquals(true, endedByGameId.get("ended-reveal"));
            assertEquals(false, endedByGameId.get("ended-active"));
            assertEquals(false, endedByGameId.get("different-players"));
            assertEquals(false, endedByGameId.get("live-reveal"));
        }
    }
}
