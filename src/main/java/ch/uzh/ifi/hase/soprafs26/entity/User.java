package ch.uzh.ifi.hase.soprafs26.entity;

import jakarta.persistence.*;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal User Representation
 * This class composes the internal representation of the user and defines how
 * the user is stored in the database.
 * Every variable will be mapped into a database field with the @Column
 * annotation
 * - nullable = false -> this cannot be left empty
 * - unique = true -> this value must be unqiue across the database -> composes
 * the primary key
 */
// Das ist die User Entity also eine Datenbankstruktur für einen User - S1
// S1: erweitern um paswort, bio und creation_date
@Entity  // sagt dass es eine Datenbank ist
@Table(name = "users") // gibt der Tabelle in der Datenbank Namen Useres
public class User implements Serializable { // public: Klasse kann von überall verwendet werden, implements Serializable: User kann in bestimmte Formate umgewandelt werden

    private static final long serialVersionUID = 1L; //

    // basically alle felder in der datenbank also die einzelnen spalten
    @Id  // eindeutiger Schlüssel jedes Users in der Datenbank
    @GeneratedValue // Datenbank erstellt quasi eine Personennummer
    private Long id; // private weil von aussen braucht man Get und Set
    // automatisch generierte eindeutige Nummer, wie eine Personalnummer

    @Column
    private String name;

    // restrict username length to 16 characters, make sure it's unique and not null
    @Column(nullable = false, unique = true, length = 16)
    private String username;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private UserStatus status;

    @Column(nullable = false) // added by me for password S1
    private String password;

    @Column(nullable = false, length = 180) // added by me for bio S1
    private String bio = "";

    @Column(nullable = false) // added by me for creation date S1
    private LocalDate creationDate;

    @Column(nullable = false)
    private String profileCharacterId = "char01";

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_preferred_color_priority", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "color", nullable = false)
    @OrderColumn(name = "priority_index")
    private List<String> preferredColorPriority = new ArrayList<>(List.of("navy_blue", "light_blue", "dark_green", "light_green"));

    @Column(nullable = false)
    private String menuBackgroundId = "menu-bg-1";

    @Column(nullable = false)
    private String gameBackgroundId = "game-bg-1";

    @Column(nullable = false)
    private String primaryColorId = "orange";

    @Column(name = "appearance_mode", nullable = false)
    private String appearanceMode = "system";

    @Column(nullable = true)
    private Boolean tutorialsEnabled = true;

    @Column(nullable = false)
    private Integer musicVolume = 10;

    @Column(nullable = false)
    private Integer soundEffectsVolume = 30;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_music_blacklist", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "track_tag", nullable = false)
    @OrderColumn(name = "tag_index")
    private List<String> musicBlacklist = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_friend_ids", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "friend_user_id")
    private List<Long> friendUserIds = new ArrayList<>();

    @Column(nullable = false) // session wins (tied to best totalScore in ended sessions)
    private Integer gamesWon = 0;

    @Column(nullable = false) // rounds won (tied to lowest score in round maps)
    private Integer roundsWon = 0;

    @Column(nullable = false) // mean of totalScore across ended sessions
    private Integer averageScorePerSession = 0;

    @Column(nullable = false) // mean of round scores (only rounds user appears in)
    private Integer averageScorePerRound = 0;

    @Column(nullable = false) // overall rank compared to all other users - global leaderborad
    private Integer overallRank = 0;

    // #109 move privacy preference during game start; copied into Move.isPublic during move creation
    @Column(nullable = false)
    private Boolean isPublicLog = true;

    @Column(nullable = true) // for websocket timeout
    private java.time.Instant lastHeartbeat;


    // implemented it as a test so task 125 works, but please check when doing task 124 and correct!
    @Column(nullable = false)
    private Integer gamesPlayed = 0;
    @Column(nullable = false)
    private Integer gamesLost = 0;
    @Column(nullable = false)
    private Integer totalPointsAccumulated = 0;


    // GET  liest die werte und SET setzt die werte,
    // weil alle felder private sind brauch man GET und Set um von aussen zugriff zu haben
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public String getPassword() {
        return password;
    } // get und set um von aussen darauf zuzugreiffen, da private sonst

    public void setPassword(String password) {
        this.password = password;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public LocalDate getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(LocalDate creationDate) {
        this.creationDate = creationDate;
    }

    public String getProfileCharacterId() {
        return profileCharacterId;
    }

    public void setProfileCharacterId(String profileCharacterId) {
        this.profileCharacterId = profileCharacterId;
    }

    public List<String> getPreferredColorPriority() {
        return preferredColorPriority;
    }

    public void setPreferredColorPriority(List<String> preferredColorPriority) {
        this.preferredColorPriority = preferredColorPriority;
    }

    public String getMenuBackgroundId() {
        return menuBackgroundId;
    }

    public void setMenuBackgroundId(String menuBackgroundId) {
        this.menuBackgroundId = menuBackgroundId;
    }

    public String getGameBackgroundId() {
        return gameBackgroundId;
    }

    public void setGameBackgroundId(String gameBackgroundId) {
        this.gameBackgroundId = gameBackgroundId;
    }

    public String getPrimaryColorId() {
        return primaryColorId;
    }

    public void setPrimaryColorId(String primaryColorId) {
        this.primaryColorId = primaryColorId;
    }

    public String getAppearanceMode() {
        return appearanceMode;
    }

    public void setAppearanceMode(String appearanceMode) {
        this.appearanceMode = appearanceMode;
    }

    public Boolean getTutorialsEnabled() {
        return tutorialsEnabled;
    }

    public void setTutorialsEnabled(Boolean tutorialsEnabled) {
        this.tutorialsEnabled = tutorialsEnabled;
    }

    public Integer getMusicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(Integer musicVolume) {
        this.musicVolume = musicVolume;
    }

    public Integer getSoundEffectsVolume() {
        return soundEffectsVolume;
    }

    public void setSoundEffectsVolume(Integer soundEffectsVolume) {
        this.soundEffectsVolume = soundEffectsVolume;
    }

    public List<String> getMusicBlacklist() {
        return musicBlacklist;
    }

    public void setMusicBlacklist(List<String> musicBlacklist) {
        this.musicBlacklist = musicBlacklist;
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

    public Integer getGamesWon() {return gamesWon;}

    public void setGamesWon(Integer gamesWon) {this.gamesWon = gamesWon;}

    public Integer getRoundsWon() { return roundsWon; }

    public void setRoundsWon(Integer roundsWon) { this.roundsWon = roundsWon; }

    public Integer getAverageScorePerSession() { return averageScorePerSession;}

    public void setAverageScorePerSession(Integer averageScorePerSession) {this.averageScorePerSession = averageScorePerSession;}

    public Integer getAverageScorePerRound() { return averageScorePerRound;}

    public void setAverageScorePerRound(Integer averageScorePerRound) {this.averageScorePerRound = averageScorePerRound;}

    public Integer getOverallRank() {return overallRank;}

    public void setOverallRank(Integer overallRank) {this.overallRank = overallRank;}

    public Boolean getIsPublicLog() { return isPublicLog; }

    public void setIsPublicLog(Boolean isPublicLog) { this.isPublicLog = isPublicLog; }

    // implemented it as a test so task 125 works, but please check when doing task 124 and correct!
    public Integer getGamesPlayed() { return gamesPlayed; }
    public void setGamesPlayed(Integer gamesPlayed) { this.gamesPlayed = gamesPlayed; }

    public Integer getGamesLost() { return gamesLost; }
    public void setGamesLost(Integer gamesLost) { this.gamesLost = gamesLost; }

    public Integer getTotalPointsAccumulated() { return totalPointsAccumulated; }
    public void setTotalPointsAccumulated(Integer totalPointsAccumulated) { this.totalPointsAccumulated = totalPointsAccumulated; }


}
