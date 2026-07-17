package ch.uzh.ifi.hase.soprafs26.rest.mapper;

import org.junit.jupiter.api.Test;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DTOMapperTest
 * Tests if the mapping between the internal and the external/API representation
 * works.
 */
public class DTOMapperTest {
	@Test
	public void testCreateUser_fromUserPostDTO_toUser_success() {
		// create UserPostDTO
		UserPostDTO userPostDTO = new UserPostDTO();
		userPostDTO.setName("name");
		userPostDTO.setUsername("username");

		// MAP -> Create user
		User user = DTOMapper.INSTANCE.convertUserPostDTOtoEntity(userPostDTO);

		// check content
		assertEquals(userPostDTO.getName(), user.getName());
		assertEquals(userPostDTO.getUsername(), user.getUsername());
	}

	@Test
	public void testGetUser_fromUser_toUserGetDTO_success() {
		// create User
		User user = new User();
		user.setName("Firstname Lastname");
		user.setUsername("firstname@lastname");
		user.setStatus(UserStatus.OFFLINE);
		user.setToken("1");
		user.setGamesPlayed(12);
		user.setRoundsPlayed(34);

		// MAP -> Create UserGetDTO
		UserGetDTO userGetDTO = DTOMapper.INSTANCE.convertEntityToUserGetDTO(user);

		// check content
		assertEquals(user.getId(), userGetDTO.getId());
		assertEquals(user.getName(), userGetDTO.getName());
		assertEquals(user.getUsername(), userGetDTO.getUsername());
		assertEquals(user.getStatus(), userGetDTO.getStatus());
		assertEquals(user.getToken(), userGetDTO.getToken());
		assertEquals(user.getGamesPlayed(), userGetDTO.getGamesPlayed());
		assertEquals(user.getRoundsPlayed(), userGetDTO.getRoundsPlayed());

		UserGetDTO publicUserGetDTO = DTOMapper.INSTANCE.convertEntityToPublicUserGetDTO(user);
		assertEquals(user.getGamesPlayed(), publicUserGetDTO.getGamesPlayed());
		assertEquals(user.getRoundsPlayed(), publicUserGetDTO.getRoundsPlayed());
	}

	// #109: isPublicLog handled correctly during entity - dto conversion
	@Test
	public void isPublicLog_correctConversionBetweenEntityAndDTO() {
		UserPutDTO putDTO = new UserPutDTO();
		putDTO.setIsPublicLog(true);
		User mapped = DTOMapper.INSTANCE.convertUserPutDTOtoEntity(putDTO);
		assertEquals(true, mapped.getIsPublicLog());

		User entity = new User();
		entity.setUsername("publicLogUser");
		entity.setIsPublicLog(true);
		UserGetDTO getDTO = DTOMapper.INSTANCE.convertEntityToUserGetDTO(entity);
		assertEquals(true, getDTO.getIsPublicLog());
	}
}
