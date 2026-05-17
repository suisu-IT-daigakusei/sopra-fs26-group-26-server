package ch.uzh.ifi.hase.soprafs26.rest.dto;

import ch.uzh.ifi.hase.soprafs26.entity.UserProfileResponseBase;

public class UserGetDTO extends UserProfileResponseBase {
    private String joinableSessionId;

    public String getJoinableSessionId() {
        return joinableSessionId;
    }

    public void setJoinableSessionId(String joinableSessionId) {
        this.joinableSessionId = joinableSessionId;
    }
}
