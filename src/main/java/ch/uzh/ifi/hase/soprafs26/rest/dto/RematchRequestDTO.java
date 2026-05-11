package ch.uzh.ifi.hase.soprafs26.dto;

public class RematchRequestDTO {
    private String sessionId;
    private Long requesterId;
    private String requesterUsername;

    // standard constructor
    public RematchRequestDTO() {}

    public RematchRequestDTO(String sessionId, Long requesterId, String requesterUsername) {
        this.sessionId = sessionId;
        this.requesterId = requesterId;
        this.requesterUsername = requesterUsername;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getRequesterId() {
        return requesterId;
    }

    public void setRequesterId(Long requesterId) {
        this.requesterId = requesterId;
    }

    public String getRequesterUsername() {
        return requesterUsername;
    }

    public void setRequesterUsername(String requesterUsername) {
        this.requesterUsername = requesterUsername;
    }
}