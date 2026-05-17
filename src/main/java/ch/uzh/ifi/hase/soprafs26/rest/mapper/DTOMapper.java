package ch.uzh.ifi.hase.soprafs26.rest.mapper;

import org.mapstruct.*;
import org.mapstruct.factory.Mappers;
import java.util.List;

import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO;
import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.entity.Session; // Added Session
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.SessionHistoryDTO;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyGetDTO;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameStateBroadcastDTO;

@Mapper
public interface DTOMapper {

	DTOMapper INSTANCE = Mappers.getMapper(DTOMapper.class);

	@BeanMapping(ignoreByDefault = true)
	@Mapping(source = "name", target = "name")
	@Mapping(source = "username", target = "username")
    @Mapping(source = "password", target = "password")
    @Mapping(source = "bio", target = "bio")
    @Mapping(source = "creationDate", target = "creationDate")
	User convertUserPostDTOtoEntity(UserPostDTO userPostDTO);

    @Mapping(source = "id", target = "id")
	@Mapping(source = "name", target = "name")
	@Mapping(source = "username", target = "username")
	@Mapping(source = "token", target = "token")
	@Mapping(source = "status", target = "status")
    @Mapping(source = "bio", target = "bio")
    @Mapping(source = "creationDate", target = "creationDate")
    @Mapping(source = "gamesWon", target = "gamesWon")
    @Mapping(source = "roundsWon", target = "roundsWon")
    @Mapping(source = "averageScorePerSession", target = "averageScorePerSession")
    @Mapping(source = "averageScorePerRound", target = "averageScorePerRound")
    @Mapping(source = "overallRank", target = "overallRank")
    @Mapping(source = "isPublicLog", target = "isPublicLog")
    @Mapping(source = "profileCharacterId", target = "profileCharacterId")
    @Mapping(source = "preferredColorPriority", target = "preferredColorPriority")
    @Mapping(source = "menuBackgroundId", target = "menuBackgroundId")
    @Mapping(source = "gameBackgroundId", target = "gameBackgroundId")
    @Mapping(source = "primaryColorId", target = "primaryColorId")
    @Mapping(source = "appearanceMode", target = "appearanceMode")
    @Mapping(source = "tutorialsEnabled", target = "tutorialsEnabled")
    @Mapping(source = "musicVolume", target = "musicVolume")
    @Mapping(source = "soundEffectsVolume", target = "soundEffectsVolume")
    @Mapping(source = "musicBlacklist", target = "musicBlacklist")
    @Mapping(target = "joinableSessionId", ignore = true)
	UserGetDTO convertEntityToUserGetDTO(User user);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "password", target = "password")
    @Mapping(source = "status", target = "status")
    @Mapping(source = "isPublicLog", target = "isPublicLog")
    @Mapping(source = "bio", target = "bio")
    @Mapping(source = "profileCharacterId", target = "profileCharacterId")
    @Mapping(source = "preferredColorPriority", target = "preferredColorPriority")
    @Mapping(source = "menuBackgroundId", target = "menuBackgroundId")
    @Mapping(source = "gameBackgroundId", target = "gameBackgroundId")
    @Mapping(source = "primaryColorId", target = "primaryColorId")
    @Mapping(source = "appearanceMode", target = "appearanceMode")
    @Mapping(source = "tutorialsEnabled", target = "tutorialsEnabled")
    @Mapping(source = "musicVolume", target = "musicVolume")
    @Mapping(source = "soundEffectsVolume", target = "soundEffectsVolume")
    @Mapping(source = "musicBlacklist", target = "musicBlacklist")
    User convertUserPutDTOtoEntity(UserPutDTO userPutDTO);

    // Updated Map for Session -> SessionHistoryDTO
    @Mapping(source="id", target="id")
    @Mapping(source="sessionId", target="sessionId")
    @Mapping(source="startTime", target="startTime")
    @Mapping(source="ended", target="ended") // MapStruct maps isEnded() / setEnded() automatically to "ended"
    @Mapping(source="absentRoundPoints", target="absentRoundPoints")
    @Mapping(source="userScoresPerRound", target="userScoresPerRound")
    @Mapping(source="totalScoreByUserId", target="totalScoreByUserId")
    @Mapping(source="hundredReductionAppliedByUserId", target="hundredReductionAppliedByUserId")
    SessionHistoryDTO convertEntityToSessionHistoryDTO(Session session);

    default Card convertCardDTOtoEntity(CardDTO cardDTO) {
        Card card = new Card();
        String code = cardDTO.getCode();
        card.setCode(code);
        card.setVisibility(false);
        char firstChar = code.charAt(0);
        int cardValue = 0;

        switch (firstChar) {
            case 'X': cardValue = 0; break;
            case 'A': cardValue = 1; break;
            case '0': cardValue = 10; break;
            case 'J': cardValue = 11; break;
            case 'Q': cardValue = 12; break;
            case 'K': cardValue = 13; break;
            default:
                cardValue = Character.getNumericValue(firstChar);
                break;
        }
        card.setValue(cardValue);
        return card;
    }

    List<Card> convertCardDTOListtoEntityList(List<CardDTO> cardDTOs);

    LobbyGetDTO convertEntityToLobbyGetDTO(Lobby lobby);
    List<LobbyGetDTO> convertEntityListToLobbyGetDTOList(List<Lobby> lobbies);

    // Updated List mapping method name
    List<SessionHistoryDTO> convertEntityListToSessionHistoryDTOList(List<Session> sessionHistory);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "resumedFromSessionId", target = "resumedFromSessionId")
    GameStateBroadcastDTO convertEntityToGameStateBroadcastDTO(Game game);
}
