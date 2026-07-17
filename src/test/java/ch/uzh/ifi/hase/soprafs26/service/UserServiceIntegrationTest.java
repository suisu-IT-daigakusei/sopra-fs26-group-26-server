package ch.uzh.ifi.hase.soprafs26.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.server.ResponseStatusException;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendOnlineSummaryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.FriendRequestIncomingDTO;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Test class for the UserResource REST resource.
 *
 * @see UserService
 */
@WebAppConfiguration
@SpringBootTest
public class UserServiceIntegrationTest {

	@Qualifier("userRepository")
	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserService userService;

	@BeforeEach
	public void setup() {
		userRepository.deleteAll();
	}

    private User createUser(String username) {
        User user = new User();
        user.setName(username);
        user.setUsername(username);
        user.setPassword("ValidPass#1");
        user.setCreationDate(LocalDate.now());
        return userService.createUser(user);
    }

	@Test
	public void createUser_validInputs_success() {
		// given
		assertNull(userRepository.findByUsername("testUsername"));

		User testUser = new User();
		testUser.setName("testName");
		testUser.setUsername("testUsername");
		testUser.setPassword("TestPass#1");
		testUser.setCreationDate(java.time.LocalDate.now());

		// when
		User createdUser = userService.createUser(testUser);

		// then
		assertEquals(testUser.getId(), createdUser.getId());
		assertEquals(testUser.getName(), createdUser.getName());
		assertEquals(testUser.getUsername(), createdUser.getUsername());
		assertNotNull(createdUser.getToken());
		//assertEquals(UserStatus.OFFLINE, createdUser.getStatus());
	}

	@Test
	public void createUser_duplicateUsername_throwsException() {
		assertNull(userRepository.findByUsername("testUsername"));

		User testUser = new User();
		testUser.setName("testName");
		testUser.setUsername("testUsername");
		testUser.setPassword("TestPass#1");
		testUser.setCreationDate(java.time.LocalDate.now());
		userService.createUser(testUser);

		// attempt to create second user with same username
		User testUser2 = new User();

		// change the name but forget about the username
		testUser2.setName("testName2");
		testUser2.setUsername("testUsername");
		testUser2.setPassword("TestPass#2");
		testUser2.setCreationDate(java.time.LocalDate.now());

		// check that an error is thrown
		assertThrows(ResponseStatusException.class, () -> userService.createUser(testUser2));
	}

    @Test
    public void friends_requestAcceptSummaryFlow_persistsAndResolvesMutualState() {
        User alice = createUser("alice");
        User bob = createUser("bob");
        User charlie = createUser("charlie");

        userService.sendFriendRequest(alice.getToken(), bob.getId());

        List<FriendRequestIncomingDTO> bobIncomingBeforeAccept = userService.getIncomingFriendRequests(bob.getToken());
        assertEquals(1, bobIncomingBeforeAccept.size());
        assertEquals(alice.getId(), bobIncomingBeforeAccept.get(0).getRequesterUserId());
        assertEquals("alice", bobIncomingBeforeAccept.get(0).getRequesterUsername());

        // not mutual yet, so no accepted friends on either side
        assertEquals(List.of(), userService.getAcceptedFriendIds(alice.getToken()));
        assertEquals(List.of(), userService.getAcceptedFriendIds(bob.getToken()));

        userService.acceptFriendRequest(bob.getToken(), alice.getId());

        assertEquals(List.of(), userService.getIncomingFriendRequests(bob.getToken()));

        List<Long> aliceFriends = userService.getAcceptedFriendIds(alice.getToken());
        List<Long> bobFriends = userService.getAcceptedFriendIds(bob.getToken());
        assertEquals(List.of(bob.getId()), aliceFriends);
        assertEquals(List.of(alice.getId()), bobFriends);

        // only accepted friends are counted in summary
        User persistedBob = userService.getUserById(bob.getId());
        persistedBob.setStatus(UserStatus.PLAYING);
        userRepository.saveAndFlush(persistedBob);

        User persistedCharlie = userService.getUserById(charlie.getId());
        persistedCharlie.setStatus(UserStatus.LOBBY);
        userRepository.saveAndFlush(persistedCharlie);

        FriendOnlineSummaryDTO summary = userService.getFriendOnlineSummary(alice.getToken());
        assertEquals(1, summary.getFriendsOnline());
        assertEquals(1, summary.getPlaying());
        assertEquals(0, summary.getLobby());
        assertEquals(0, summary.getSpectating());
    }

    @Test
    public void friends_removeFriend_removesRelationFromBothUsers() {
        User alice = createUser("alice2");
        User bob = createUser("bob2");

        userService.sendFriendRequest(alice.getToken(), bob.getId());
        userService.acceptFriendRequest(bob.getToken(), alice.getId());
        assertEquals(List.of(bob.getId()), userService.getAcceptedFriendIds(alice.getToken()));

        userService.removeFriendOrRequest(alice.getToken(), bob.getId());

        assertEquals(List.of(), userService.getAcceptedFriendIds(alice.getToken()));
        assertEquals(List.of(), userService.getAcceptedFriendIds(bob.getToken()));
    }

    @Test
    public void updateUser_preferredColorPriority_consecutiveUpdates_doNotCreateDuplicatePriorityRows() {
        User user = createUser("prefcoloruser");
        List<String> nextPriority = List.of("orange", "red", "yellow", "pink");

        User firstUpdate = new User();
        firstUpdate.setPreferredColorPriority(nextPriority);
        assertDoesNotThrow(() -> userService.updateUser(user.getId(), firstUpdate));

        User secondUpdate = new User();
        secondUpdate.setPreferredColorPriority(nextPriority);
        assertDoesNotThrow(() -> userService.updateUser(user.getId(), secondUpdate));

        User persisted = userService.getUserProfileById(user.getId());
        assertEquals(nextPriority, persisted.getPreferredColorPriority());
    }
}
