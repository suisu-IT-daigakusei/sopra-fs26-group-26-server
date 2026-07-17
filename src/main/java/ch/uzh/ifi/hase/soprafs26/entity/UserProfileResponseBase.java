package ch.uzh.ifi.hase.soprafs26.entity;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OrderColumn;
import org.hibernate.annotations.BatchSize;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;


@MappedSuperclass
public abstract class UserProfileResponseBase implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private String name;

    @Column(nullable = false, unique = true, length = 16)
    private String username;

    @Column(nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;

    @Column(nullable = false, length = 180)
    private String bio = "";

    @Column(nullable = false)
    private LocalDate creationDate;

    @Column(nullable = false)
    private String profileCharacterId = "char01";

    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @jakarta.persistence.CollectionTable(name = "user_preferred_color_priority", joinColumns = @JoinColumn(name = "user_id"))
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

    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @jakarta.persistence.CollectionTable(name = "user_music_blacklist", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "track_tag", nullable = false)
    @OrderColumn(name = "tag_index")
    private List<String> musicBlacklist = new ArrayList<>();

    @Column(nullable = false)
    private Integer gamesWon = 0;

    @Column(nullable = false)
    private Integer roundsWon = 0;

    @Column(nullable = false)
    private Integer averageScorePerSession = 0;

    @Column(nullable = false)
    private Integer averageScorePerRound = 0;

    @Column(nullable = false)
    private Integer overallRank = 0;

    @Column(nullable = false)
    private Boolean isPublicLog = true;

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
        if (this.preferredColorPriority == null) {
            this.preferredColorPriority = new ArrayList<>();
        } else {
            this.preferredColorPriority.clear();
        }
        if (preferredColorPriority != null) {
            this.preferredColorPriority.addAll(preferredColorPriority);
        }
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
        if (this.musicBlacklist == null) {
            this.musicBlacklist = new ArrayList<>();
        } else {
            this.musicBlacklist.clear();
        }
        if (musicBlacklist != null) {
            this.musicBlacklist.addAll(musicBlacklist);
        }
    }

    public Integer getGamesWon() {
        return gamesWon;
    }

    public void setGamesWon(Integer gamesWon) {
        this.gamesWon = gamesWon;
    }

    public Integer getRoundsWon() {
        return roundsWon;
    }

    public void setRoundsWon(Integer roundsWon) {
        this.roundsWon = roundsWon;
    }

    public Integer getAverageScorePerSession() {
        return averageScorePerSession;
    }

    public void setAverageScorePerSession(Integer averageScorePerSession) {
        this.averageScorePerSession = averageScorePerSession;
    }

    public Integer getAverageScorePerRound() {
        return averageScorePerRound;
    }

    public void setAverageScorePerRound(Integer averageScorePerRound) {
        this.averageScorePerRound = averageScorePerRound;
    }

    public Integer getOverallRank() {
        return overallRank;
    }

    public void setOverallRank(Integer overallRank) {
        this.overallRank = overallRank;
    }

    public Boolean getIsPublicLog() {
        return isPublicLog;
    }

    public void setIsPublicLog(Boolean isPublicLog) {
        this.isPublicLog = isPublicLog;
    }
}
