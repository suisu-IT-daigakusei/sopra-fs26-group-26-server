package ch.uzh.ifi.hase.soprafs26.rest.dto;

import ch.uzh.ifi.hase.soprafs26.entity.Card;

import java.util.List;

/**
 * Compact reconnect payload used by clients to resync core game UI state in one request.
 */
public class GameSyncStateDTO {

    private String status;
    private Long currentTurnUserId;
    private List<Card> myHand;
    private Card discardTop;
    private Card drawnCard;
    private String postRoundSessionId;
    private Long rematchDecisionSeconds;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCurrentTurnUserId() {
        return currentTurnUserId;
    }

    public void setCurrentTurnUserId(Long currentTurnUserId) {
        this.currentTurnUserId = currentTurnUserId;
    }

    public List<Card> getMyHand() {
        return myHand;
    }

    public void setMyHand(List<Card> myHand) {
        this.myHand = myHand;
    }

    public Card getDiscardTop() {
        return discardTop;
    }

    public void setDiscardTop(Card discardTop) {
        this.discardTop = discardTop;
    }

    public Card getDrawnCard() {
        return drawnCard;
    }

    public void setDrawnCard(Card drawnCard) {
        this.drawnCard = drawnCard;
    }

    public String getPostRoundSessionId() {
        return postRoundSessionId;
    }

    public void setPostRoundSessionId(String postRoundSessionId) {
        this.postRoundSessionId = postRoundSessionId;
    }

    public Long getRematchDecisionSeconds() {
        return rematchDecisionSeconds;
    }

    public void setRematchDecisionSeconds(Long rematchDecisionSeconds) {
        this.rematchDecisionSeconds = rematchDecisionSeconds;
    }
}
