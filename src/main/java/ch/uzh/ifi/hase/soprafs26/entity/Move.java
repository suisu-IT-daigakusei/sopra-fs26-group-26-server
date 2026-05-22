package ch.uzh.ifi.hase.soprafs26.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "moves", indexes = {
        @Index(name = "idx_moves_session_timestamp", columnList = "session_id,timestamp"),
        @Index(name = "idx_moves_user_id", columnList = "user_id")
})
public class Move {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sessionId; // connection to the session

    @Column(nullable = false)
    private Long userId; // who's turn was it

    @Column(nullable = false)
    private String actionType; // e.g "DRAW", "SWAP", "PEEK", "DISCARD", "CABO"

    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    // Optional: if we save details of move (e.g which card)
    @Column(nullable = true)
    private String details;

    // #109 depends on player's privacy choice
    @Column(nullable = false)
    private Boolean isPublic = false;

    // Getter & Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }
}
