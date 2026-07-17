package ch.uzh.ifi.hase.soprafs26.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.support.PostgresDataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.persistence.PersistenceException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@PostgresDataJpaTest
public class UserRepositoryIntegrationTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private UserRepository userRepository;

	@Test
	public void findByName_success() {
		// given
		User user = new User();
		user.setName("Firstname Lastname");
		user.setUsername("first@last");
		user.setStatus(UserStatus.OFFLINE);
		user.setToken("1");
		user.setPassword("testPassword");
		user.setCreationDate(java.time.LocalDate.now());

		entityManager.persist(user);
		entityManager.flush();

		// when
		User found = userRepository.findByName(user.getName());

		// then
		assertNotNull(found.getId());
		assertEquals(found.getName(), user.getName());
		assertEquals(found.getUsername(), user.getUsername());
		assertEquals(found.getToken(), user.getToken());
		assertEquals(found.getStatus(), user.getStatus());
	}

	@Test
	public void saveUser_duplicateUsername_throwsException() {
    	// given
    	User user1 = new User();
    	user1.setName("User One");
    	user1.setUsername("duplicate");
    	user1.setToken("token1");
    	user1.setPassword("password");
    	user1.setCreationDate(java.time.LocalDate.now());
    	user1.setStatus(UserStatus.OFFLINE);

    	entityManager.persist(user1);
    	entityManager.flush();

    	// when
    	User user2 = new User();
    	user2.setName("User Two");
    	user2.setUsername("duplicate"); 
    	user2.setToken("token2");
    	user2.setPassword("password");
    	user2.setCreationDate(java.time.LocalDate.now());
    	user2.setStatus(UserStatus.OFFLINE);

    	// then
		assertThrows(PersistenceException.class, () -> {
        	entityManager.persist(user2);
        	entityManager.flush();
    	});
	}

	// #124: database persistence of game stats field values works
	@Test
	public void saveUser_gameStatsFields_databasePersistenceWorks() {
		User user = new User();
		user.setName("Stats User");
		user.setUsername("statsuser");
		user.setToken("stats-token");
		user.setPassword("pw");
		user.setCreationDate(java.time.LocalDate.now());
		user.setStatus(UserStatus.OFFLINE);
		user.setGamesPlayed(12);
		user.setGamesWon(7);
		user.setGamesLost(5);
		user.setTotalPointsAccumulated(340);

		entityManager.persist(user);
		entityManager.flush();
		entityManager.clear();

		User loaded = userRepository.findById(user.getId()).orElseThrow();

		assertEquals(12, loaded.getGamesPlayed());
		assertEquals(7, loaded.getGamesWon());
		assertEquals(5, loaded.getGamesLost());
		assertEquals(340, loaded.getTotalPointsAccumulated());
	}

	@Test
	public void findIdleCandidates_excludesOfflineAndSpectating_andHonorsPageBound() {
		Instant now = Instant.now();
		User oldestOnline = persistHeartbeatUser("idleone", UserStatus.ONLINE, now.minusSeconds(900));
		persistHeartbeatUser("idletwo", UserStatus.ONLINE, now.minusSeconds(800));
		persistHeartbeatUser("spectator", UserStatus.SPECTATING, now.minusSeconds(1000));
		persistHeartbeatUser("offline", UserStatus.OFFLINE, now.minusSeconds(1100));
		persistHeartbeatUser("recent", UserStatus.ONLINE, now.minusSeconds(10));
		entityManager.flush();

		List<User> candidates = userRepository.findIdleCandidates(
				now.minusSeconds(300),
				Set.of(UserStatus.OFFLINE, UserStatus.SPECTATING),
				PageRequest.of(0, 1));

		assertEquals(1, candidates.size());
		assertEquals(oldestOnline.getId(), candidates.get(0).getId());
	}

	private User persistHeartbeatUser(String username, UserStatus status, Instant heartbeat) {
		User user = new User();
		user.setName(username);
		user.setUsername(username);
		user.setStatus(status);
		user.setToken("token-" + username);
		user.setPassword("password");
		user.setCreationDate(java.time.LocalDate.now());
		user.setLastHeartbeat(heartbeat);
		entityManager.persist(user);
		return user;
	}
}
