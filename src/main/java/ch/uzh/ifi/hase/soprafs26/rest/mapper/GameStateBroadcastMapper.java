package ch.uzh.ifi.hase.soprafs26.rest.mapper;

import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.GameMoveEvent;
import ch.uzh.ifi.hase.soprafs26.entity.GameMoveStep;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardViewDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.DiscardTopDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameStateBroadcastDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameMoveEventDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameMoveStepDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.PlayerHandViewDTO;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// builds a game state representation with filtered data for a given player
@Component
public class GameStateBroadcastMapper {
    private final LobbyService lobbyService;
    private final UserRepository userRepository;

    public GameStateBroadcastMapper(@Lazy LobbyService lobbyService, UserRepository userRepository) {
        this.lobbyService = lobbyService;
        this.userRepository = userRepository;
    }

    public GameStateBroadcastDTO toBroadcastForViewer(Game game, Long viewerUserId) {
        return toBroadcastForViewer(game, viewerUserId, buildSharedContext(game));
    }

    public GameStateBroadcastDTO toBroadcastForViewer(
            Game game,
            Long viewerUserId,
            SharedBroadcastContext sharedContext) {
        GameStateBroadcastDTO dto = new GameStateBroadcastDTO();

        dto.setGameId(game.getId());
        dto.setStatus(game.getStatus());
        dto.setCurrentTurnUserId(game.getCurrentPlayerId());
        dto.setCaboCalled(game.isCaboCalled());
        dto.setCaboForcedByTimeout(game.isCaboForcedByTimeout());
        dto.setTurnSeconds(game.getTurnSeconds());
        dto.setInitialPeekSeconds(game.getInitialPeekSeconds());
        dto.setAbilityRevealSeconds(game.getAbilityRevealSeconds());
        dto.setAbilitySwapSeconds(game.getAbilitySwapSeconds());
        dto.setCaboRevealSeconds(game.getCaboRevealSeconds());
        dto.setRematchDecisionSeconds(game.getRematchDecisionSeconds());
        dto.setAfkTimeoutSeconds(game.getAfkTimeoutSeconds());
        dto.setLastMoveEvent(toMoveEventDTO(game.getLastMoveEvent()));

        List<Card> draw = game.getDrawPile();
        dto.setDrawPileCount(draw == null ? 0 : draw.size());

        List<Card> discard = game.getDiscardPile();
        if (discard != null && !discard.isEmpty()) {
            Card top = discard.get(discard.size() - 1);
            DiscardTopDTO topDto = new DiscardTopDTO();
            topDto.setValue(top.getValue());
            topDto.setCode(top.getCode());
            dto.setDiscardPileTop(topDto);
        }

        Card drawnCard = game.getDrawnCard();
        if (drawnCard != null) {
            CardViewDTO drawnCardDTO = new CardViewDTO();
            GameStatus status = game.getStatus();
            boolean roundEndReveal = status == GameStatus.CABO_REVEAL
                    || status == GameStatus.ROUND_AWAITING_REMATCH
                    || status == GameStatus.ROUND_ENDED;
            if (roundEndReveal || viewerUserId.equals(game.getCurrentPlayerId())) {
                drawnCardDTO.setValue(drawnCard.getValue());
                drawnCardDTO.setCode(drawnCard.getCode());
                drawnCardDTO.setFaceDown(false);
            } else {
                drawnCardDTO.setValue(null);
                drawnCardDTO.setCode(null);
                drawnCardDTO.setFaceDown(true);
            }
            dto.setDrawnCard(drawnCardDTO);
        }

        Map<Long, List<Card>> hands = game.getPlayerHands();
        // if hands is null -> empty map
        if (hands == null) {
            hands = Map.of();
        }
        List<Long> ordered = game.getOrderedPlayerIds();
        // if null -> no hands filled 
        if (ordered == null) {
            ordered = List.of();
        }
        SharedBroadcastContext context = sharedContext == null ? new SharedBroadcastContext() : sharedContext;
        dto.setSessionId(context.getSessionId());
        dto.setTimedOutPlayerIds(context.getTimedOutPlayerIds());
        Map<Long, String> assignedCharacterColorByUserId = context.getAssignedCharacterColorByUserId();
        Map<Long, User> usersById = context.getUsersById();

        List<PlayerHandViewDTO> playerHands = new ArrayList<>();
        for (Long ownerId : ordered) {
            PlayerHandViewDTO handView = new PlayerHandViewDTO();
            handView.setUserId(ownerId);
            User owner = usersById.get(ownerId);
            handView.setUsername(owner == null ? null : owner.getUsername());
            handView.setProfileCharacterId(owner == null ? null : owner.getProfileCharacterId());
            handView.setCharacterColorId(assignedCharacterColorByUserId.get(ownerId));
            // if hands is empty map or no key in map for this player -> empty list of cards
            List<Card> hand = hands.getOrDefault(ownerId, List.of());
            List<CardViewDTO> views = new ArrayList<>();
            for (int i = 0; i < hand.size(); i++) {
                views.add(toCardView(i, hand.get(i), ownerId, viewerUserId, game.getCurrentPlayerId(), game.getStatus()));
            }
            handView.setCards(views);
            playerHands.add(handView);
        }
        dto.setPlayers(playerHands);
        return dto;
    }

    public SharedBroadcastContext buildSharedContext(Game game) {
        SharedBroadcastContext context = new SharedBroadcastContext();
        if (game == null) {
            return context;
        }

        List<Long> ordered = game.getOrderedPlayerIds();
        if (ordered == null) {
            ordered = List.of();
        }

        if (lobbyService != null) {
            context.setSessionId(lobbyService.findPlayingSessionIdForPlayers(ordered));
            context.setTimedOutPlayerIds(
                    ordered.stream()
                            .filter(id -> id != null && lobbyService.isPlayerTimedOutInPlaying(id))
                            .toList()
            );

            Map<Long, String> resolvedAssignedColors = lobbyService.resolvePlayingAssignedCharacterColorsForPlayers(ordered);
            context.setAssignedCharacterColorByUserId(resolvedAssignedColors == null ? Map.of() : resolvedAssignedColors);
        }

        if (userRepository != null) {
            Map<Long, User> usersById = new HashMap<>();
            Iterable<User> resolvedUsers = userRepository.findAllById(ordered);
            if (resolvedUsers != null) {
                for (User user : resolvedUsers) {
                    if (user != null && user.getId() != null) {
                        usersById.put(user.getId(), user);
                    }
                }
            }
            context.setUsersById(usersById);
        }
        return context;
    }

    public static class SharedBroadcastContext {
        private String sessionId;
        private List<Long> timedOutPlayerIds = List.of();
        private Map<Long, String> assignedCharacterColorByUserId = Map.of();
        private Map<Long, User> usersById = Map.of();

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public List<Long> getTimedOutPlayerIds() {
            return timedOutPlayerIds;
        }

        public void setTimedOutPlayerIds(List<Long> timedOutPlayerIds) {
            this.timedOutPlayerIds = timedOutPlayerIds == null ? List.of() : timedOutPlayerIds;
        }

        public Map<Long, String> getAssignedCharacterColorByUserId() {
            return assignedCharacterColorByUserId;
        }

        public void setAssignedCharacterColorByUserId(Map<Long, String> assignedCharacterColorByUserId) {
            this.assignedCharacterColorByUserId =
                    assignedCharacterColorByUserId == null ? Map.of() : assignedCharacterColorByUserId;
        }

        public Map<Long, User> getUsersById() {
            return usersById;
        }

        public void setUsersById(Map<Long, User> usersById) {
            this.usersById = usersById == null ? Map.of() : usersById;
        }
    }

    private GameMoveEventDTO toMoveEventDTO(GameMoveEvent event) {
        if (event == null) {
            return null;
        }
        GameMoveEventDTO dto = new GameMoveEventDTO();
        BeanUtils.copyProperties(event, dto, "primary", "secondary");
        dto.setPrimary(toMoveStepDTO(event.getPrimary()));
        dto.setSecondary(toMoveStepDTO(event.getSecondary()));
        return dto;
    }

    private GameMoveStepDTO toMoveStepDTO(GameMoveStep step) {
        if (step == null) {
            return null;
        }
        GameMoveStepDTO dto = new GameMoveStepDTO();
        BeanUtils.copyProperties(step, dto);
        return dto;
    }

    private CardViewDTO toCardView(int position, Card card, Long handOwnerId, Long viewerUserId, Long currentPlayerId,
            GameStatus gameStatus) {
        CardViewDTO v = new CardViewDTO();
        v.setPosition(position);
        boolean isOwner = handOwnerId.equals(viewerUserId);
        boolean isViewerCurrentPlayer = viewerUserId.equals(currentPlayerId);
        boolean revealAll = gameStatus == GameStatus.CABO_REVEAL
                || gameStatus == GameStatus.ROUND_AWAITING_REMATCH
                || gameStatus == GameStatus.ROUND_ENDED;

        // Default (initial peek, 7/8 self-peek, round play): owner sees own cards marked visible
        // ABILITY_PEEK_OPPONENT (9/10): only the current player (spy) may see a visible card on another hand
        // the hand owner must not see that card face-up here, or they would learn what the spy saw
        boolean canSee;
        if (revealAll) {
            canSee = true;
        } else if (gameStatus == GameStatus.ABILITY_PEEK_OPPONENT) {
            canSee = !isOwner && isViewerCurrentPlayer && card.getVisibility();
        } else {
            canSee = isOwner && card.getVisibility();
        }

        v.setFaceDown(!canSee);
        if (canSee) {
            v.setValue(card.getValue());
            v.setCode(card.getCode());
        } else {
            v.setValue(null);
            v.setCode(null);
        }
        return v;
    }
}
