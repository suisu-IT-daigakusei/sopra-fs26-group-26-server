package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.GameRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.SessionRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:18.4-alpine:///cabo-start-lock",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.task.scheduling.enabled=false"
})
class LobbyGameStartConcurrencyIntegrationTest {

    @Autowired
    private LobbyService lobbyService;

    @Autowired
    private LobbyRepository lobbyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private SessionRepository sessionRepository;

    private record StartOutcome(Game game, HttpStatus errorStatus) {
        static StartOutcome success(Game game) {
            return new StartOutcome(game, null);
        }

        static StartOutcome failure(ResponseStatusException exception) {
            return new StartOutcome(null, HttpStatus.valueOf(exception.getStatusCode().value()));
        }
    }

    @Test
    void concurrentHostStarts_commitExactlyOneGameAndOneSession() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User host = persistUser("h" + suffix, "host-token-" + suffix);
        User guest = persistUser("g" + suffix, "guest-token-" + suffix);

        Lobby lobby = new Lobby();
        lobby.setSessionId("S" + suffix.toUpperCase());
        lobby.setSessionHostUserId(host.getId());
        lobby.setStatus("WAITING");
        lobby.setPlayerIds(new ArrayList<>(List.of(host.getId(), guest.getId())));
        lobby.setPlayerReadyByUserId(new HashMap<>(Map.of(
                host.getId(), true,
                guest.getId(), true)));
        lobby = lobbyRepository.saveAndFlush(lobby);

        long gamesBefore = gameRepository.count();
        long sessionsBefore = sessionRepository.count();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            String sessionId = lobby.getSessionId();
            Future<StartOutcome> first = executor.submit(() -> attemptStart(startGate, host.getToken(), sessionId));
            Future<StartOutcome> second = executor.submit(() -> attemptStart(startGate, host.getToken(), sessionId));
            startGate.countDown();

            List<StartOutcome> outcomes = List.of(first.get(), second.get());
            assertEquals(1L, outcomes.stream().filter(outcome -> outcome.game() != null).count());
            assertEquals(1L, outcomes.stream()
                    .filter(outcome -> outcome.errorStatus() == HttpStatus.CONFLICT)
                    .count());

            assertEquals(gamesBefore + 1L, gameRepository.count());
            assertEquals(sessionsBefore + 1L, sessionRepository.count());
            Lobby reloaded = lobbyRepository.findBySessionId(sessionId);
            assertNotNull(reloaded);
            assertEquals("PLAYING", reloaded.getStatus());
        } finally {
            executor.shutdownNow();
        }
    }

    private StartOutcome attemptStart(CountDownLatch startGate, String token, String sessionId) throws Exception {
        startGate.await();
        try {
            return StartOutcome.success(lobbyService.startGameAtomically(token, sessionId));
        } catch (ResponseStatusException exception) {
            return StartOutcome.failure(exception);
        }
    }

    private User persistUser(String username, String token) {
        User user = new User();
        user.setName(username);
        user.setUsername(username);
        user.setPassword("ValidPass#1");
        user.setToken(token);
        user.setStatus(UserStatus.LOBBY);
        user.setCreationDate(LocalDate.now());
        return userRepository.saveAndFlush(user);
    }
}
