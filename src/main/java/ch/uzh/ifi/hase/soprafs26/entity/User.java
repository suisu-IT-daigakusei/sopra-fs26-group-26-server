package ch.uzh.ifi.hase.soprafs26.entity;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.DynamicUpdate;

import java.util.ArrayList;
import java.util.List;

@Entity
@DynamicUpdate
@Table(name = "users")
public class User extends UserProfileResponseBase {

    private static final long serialVersionUID = 1L;

    @Column(nullable = false)
    private String password;

    @ElementCollection(fetch = FetchType.LAZY)
    @jakarta.persistence.CollectionTable(name = "user_friend_ids", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "friend_user_id")
    private List<Long> friendUserIds = new ArrayList<>();

    @Column(nullable = true)
    private java.time.Instant lastHeartbeat;

    @Column(nullable = false)
    private Integer gamesPlayed = 0;

    @Column(nullable = false)
    private Integer gamesLost = 0;

    @Column(nullable = false)
    private Integer totalPointsAccumulated = 0;

    /** Internal counters used to maintain averageScorePerRound incrementally. */
    @Column(nullable = false)
    private Integer roundsPlayed = 0;

    @Column(nullable = false)
    private Integer totalRoundPointsAccumulated = 0;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public List<Long> getFriendUserIds() {
        return friendUserIds;
    }

    public void setFriendUserIds(List<Long> friendUserIds) {
        this.friendUserIds = friendUserIds;
    }

    public java.time.Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(java.time.Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public Integer getGamesPlayed() {
        return gamesPlayed;
    }

    public void setGamesPlayed(Integer gamesPlayed) {
        this.gamesPlayed = gamesPlayed;
    }

    public Integer getGamesLost() {
        return gamesLost;
    }

    public void setGamesLost(Integer gamesLost) {
        this.gamesLost = gamesLost;
    }

    public Integer getTotalPointsAccumulated() {
        return totalPointsAccumulated;
    }

    public void setTotalPointsAccumulated(Integer totalPointsAccumulated) {
        this.totalPointsAccumulated = totalPointsAccumulated;
    }

    @JsonIgnore
    public Integer getRoundsPlayed() {
        return roundsPlayed;
    }

    public void setRoundsPlayed(Integer roundsPlayed) {
        this.roundsPlayed = roundsPlayed;
    }

    @JsonIgnore
    public Integer getTotalRoundPointsAccumulated() {
        return totalRoundPointsAccumulated;
    }

    public void setTotalRoundPointsAccumulated(Integer totalRoundPointsAccumulated) {
        this.totalRoundPointsAccumulated = totalRoundPointsAccumulated;
    }
}
