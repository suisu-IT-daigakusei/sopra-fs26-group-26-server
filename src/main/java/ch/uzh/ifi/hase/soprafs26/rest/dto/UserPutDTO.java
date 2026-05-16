package ch.uzh.ifi.hase.soprafs26.rest.dto; // sagt java dass diese Klasse zum DTO paket gehört

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.util.AuthValidationRules;
import java.util.List;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// dto = Data Transfer Object = Daten die durchs Netzwerk geschickt werden
public class UserPutDTO {

    @Size(
            min = AuthValidationRules.PASSWORD_MIN_LENGTH,
            max = AuthValidationRules.PASSWORD_MAX_LENGTH,
            message = "Password must be between 8 and 32 characters long"
    )
    @Pattern(
            regexp = AuthValidationRules.CREDENTIAL_FORMAT_REGEX,
            message = "Password must include 1 uppercase, 1 special symbol, and only ASCII characters (no spaces)"
    )
    private String password; // für wenn der User ein neues Passwort machen möchte
    // private weil es nur innerhalb dieser Klasse direkt zugänglich ist
    private UserStatus status; // damit das passwort auf offline gesetzt werden kann bei logout

    private Boolean isPublicLog;
    @Size(max = 180, message = "Bio must be at most 180 characters long")
    private String bio;
    private String profileCharacterId;
    private List<String> preferredColorPriority;
    private String menuBackgroundId;
    private String gameBackgroundId;
    private String primaryColorId;
    private String appearanceMode;
    private Boolean tutorialsEnabled;
    private Integer musicVolume;
    private Integer soundEffectsVolume;
    private List<String> musicBlacklist;


    public String getPassword() { return password; }
    // Getter der Uns das Passwort im Forntend zurück gibt
    public UserStatus getStatus() {return status;}

    public Boolean getIsPublicLog() { return isPublicLog; }
    public String getBio() { return bio; }
    public String getProfileCharacterId() { return profileCharacterId; }
    public List<String> getPreferredColorPriority() { return preferredColorPriority; }
    public String getMenuBackgroundId() { return menuBackgroundId; }
    public String getGameBackgroundId() { return gameBackgroundId; }
    public String getPrimaryColorId() { return primaryColorId; }
    public String getAppearanceMode() { return appearanceMode; }
    public Boolean getTutorialsEnabled() { return tutorialsEnabled; }
    public Integer getMusicVolume() { return musicVolume; }
    public Integer getSoundEffectsVolume() { return soundEffectsVolume; }
    public List<String> getMusicBlacklist() { return musicBlacklist; }


    // aufgerufen vom DTOMapper um ps zu lesen
    public void setPassword(String password) { this.password = password; }
    // setter der das pw setzt
    // wird aufgerufen wenn frontend den Request schickt
    public void setStatus(UserStatus status) { this.status = status; }

    public void setIsPublicLog(Boolean isPublicLog) { this.isPublicLog = isPublicLog; }
    public void setBio(String bio) { this.bio = bio; }
    public void setProfileCharacterId(String profileCharacterId) { this.profileCharacterId = profileCharacterId; }
    public void setPreferredColorPriority(List<String> preferredColorPriority) { this.preferredColorPriority = preferredColorPriority; }
    public void setMenuBackgroundId(String menuBackgroundId) { this.menuBackgroundId = menuBackgroundId; }
    public void setGameBackgroundId(String gameBackgroundId) { this.gameBackgroundId = gameBackgroundId; }
    public void setPrimaryColorId(String primaryColorId) { this.primaryColorId = primaryColorId; }
    public void setAppearanceMode(String appearanceMode) { this.appearanceMode = appearanceMode; }
    public void setTutorialsEnabled(Boolean tutorialsEnabled) { this.tutorialsEnabled = tutorialsEnabled; }
    public void setMusicVolume(Integer musicVolume) { this.musicVolume = musicVolume; }
    public void setSoundEffectsVolume(Integer soundEffectsVolume) { this.soundEffectsVolume = soundEffectsVolume; }
    public void setMusicBlacklist(List<String> musicBlacklist) { this.musicBlacklist = musicBlacklist; }

}
