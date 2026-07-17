package ch.uzh.ifi.hase.soprafs26.rest.dto;

import ch.uzh.ifi.hase.soprafs26.entity.UserProfileResponseBase;

public class UserGetDTO extends UserProfileResponseBase {
    private String joinableSessionId;
    private Integer gamesPlayed;
    private Integer roundsPlayed;

    public String getJoinableSessionId() {
        return joinableSessionId;
    }

    public void setJoinableSessionId(String joinableSessionId) {
        this.joinableSessionId = joinableSessionId;
    }

    public Integer getGamesPlayed() {
        return gamesPlayed;
    }

    public void setGamesPlayed(Integer gamesPlayed) {
        this.gamesPlayed = gamesPlayed;
    }

    public Integer getRoundsPlayed() {
        return roundsPlayed;
    }

    public void setRoundsPlayed(Integer roundsPlayed) {
        this.roundsPlayed = roundsPlayed;
    }
}
