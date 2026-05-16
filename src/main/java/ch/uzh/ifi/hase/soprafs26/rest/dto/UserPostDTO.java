package ch.uzh.ifi.hase.soprafs26.rest.dto;
import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ch.uzh.ifi.hase.soprafs26.util.AuthValidationRules;

// definiert was das frontend ans backend senset, also daten die der user eingegeben hat,
// enthält alle felder die für registrierung und Login benötigt werden

// unterschied zu GETDTO: POST = Frontend schickt ans Backend, GET = Backend schickt ans Frontend.
public class UserPostDTO {

	private String name;

	@NotBlank(message = "Username is required")
	@Size(
            min = AuthValidationRules.USERNAME_MIN_LENGTH,
            max = AuthValidationRules.USERNAME_MAX_LENGTH,
            message = "Username must be between 1 and 16 characters long"
    )
    @Pattern(
            regexp = AuthValidationRules.USERNAME_REGEX,
            message = "Username can only contain ASCII letters and numbers"
    )
	private String username;

    @NotBlank(message = "Password is required")
    @Size(
            min = AuthValidationRules.PASSWORD_MIN_LENGTH,
            max = AuthValidationRules.PASSWORD_MAX_LENGTH,
            message = "Password must be between 8 and 32 characters long"
    )
    @Pattern(
            regexp = AuthValidationRules.CREDENTIAL_FORMAT_REGEX,
            message = "Password must include 1 uppercase, 1 special symbol, and only ASCII characters (no spaces)"
    )
    private String password;

    @Size(max = 180, message = "Bio must be at most 180 characters long")
    private String bio;

    private LocalDate creationDate;

	public String getName() {
		return name;
	}
    // public damit dto mapper und service auf diese felder zugriff haben.


	public void setName(String name) {
		this.name = name;
	}
// void weil setter nichts zurück geben
	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

    public String getPassword() {return password;}

    public void setPassword(String password) {this.password = password;}

    public String getBio() { return bio; }

    public void setBio(String bio) { this.bio = bio; }

    public LocalDate getCreationDate() {return creationDate; }

    public void setCreationDate(LocalDate creationDate) { this.creationDate = creationDate; }
}
