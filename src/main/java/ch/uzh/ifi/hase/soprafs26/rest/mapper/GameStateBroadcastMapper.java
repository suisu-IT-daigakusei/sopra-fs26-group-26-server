package ch.uzh.ifi.hase.soprafs26.rest.mapper;

import ch.uzh.ifi.hase.soprafs26.constant.GameStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.GameMoveEvent;
import ch.uzh.ifi.hase.soprafs26.entity.GameMoveStep;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardViewDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.DiscardTopDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameStateBroadcastDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameMoveEventDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.GameMoveStepDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.PlayerHandViewDTO;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// builds a game state representation with filtered data for a given player
@Component
public class GameStateBroadcastMapper {
    private final LobbyService lobbyService;

    public GameStateBroadcastMapper(@Lazy LobbyService lobbyService) {
        this.lobbyService = lobbyService;
    }

    public GameStateBroadcastDTO toBroadcastForViewer(Game game, Long viewerUserId) {
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
        if (lobbyService != null) {
            dto.setSessionId(lobbyService.findPlayingSessionIdForPlayers(ordered));
        }
        dto.setTimedOutPlayerIds(
                ordered.stream()
                        .filter(id -> id != null && lobbyService != null && lobbyService.isPlayerTimedOutInPlaying(id))
                        .toList()
        );

        List<PlayerHandViewDTO> playerHands = new ArrayList<>();
        for (Long ownerId : ordered) {
            PlayerHandViewDTO handView = new PlayerHandViewDTO();
            handView.setUserId(ownerId);
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

    private GameMoveEventDTO toMoveEventDTO(GameMoveEvent event) {
        if (event == null) {
            return null;
        }
        GameMoveEventDTO dto = new GameMoveEventDTO();
        dto.setSequence(event.getSequence());
        dto.setActorUserId(event.getActorUserId());
        dto.setPrimary(toMoveStepDTO(event.getPrimary()));
        dto.setSecondary(toMoveStepDTO(event.getSecondary()));
        return dto;
    }

    private GameMoveStepDTO toMoveStepDTO(GameMoveStep step) {
        if (step == null) {
            return null;
        }
        GameMoveStepDTO dto = new GameMoveStepDTO();
        dto.setSourceZone(step.getSourceZone());
        dto.setSourceUserId(step.getSourceUserId());
        dto.setSourceCardIndex(step.getSourceCardIndex());
        dto.setTargetZone(step.getTargetZone());
        dto.setTargetUserId(step.getTargetUserId());
        dto.setTargetCardIndex(step.getTargetCardIndex());
        dto.setHidden(step.isHidden());
        dto.setValue(step.getValue());
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
