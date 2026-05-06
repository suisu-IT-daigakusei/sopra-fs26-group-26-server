package ch.uzh.ifi.hase.soprafs26.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import ch.uzh.ifi.hase.soprafs26.entity.Game;
import ch.uzh.ifi.hase.soprafs26.entity.Session;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.SessionHistoryDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPutDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;
import ch.uzh.ifi.hase.soprafs26.service.UserService;
import ch.uzh.ifi.hase.soprafs26.service.HistoryService;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import jakarta.validation.Valid;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;

/**
 * User Controller
 * This class is responsible for handling all REST request that are related to
 * the user.
 * The controller will receive the request and delegate the execution to the
 * UserService and finally return the result.
 */

// basically schnittstelle zwischen front und backend,
// empfängt alle http requests vom frontend
@RestController // sagt Spring dass diese Klasse REST Endpoints hat
public class UserController {

	private final UserService userService; // zugriff auf den userService für Logik
    private final HistoryService historyService;
    private final UserRepository userRepository;

	UserController(UserService userService, HistoryService historyService, UserRepository userRepository) {
		this.userService = userService;
        this.historyService = historyService;
        this.userRepository = userRepository;
	} // UserService wird automatisch von Spring injiziert

    // GET /users - alle User laden (zb auf Userliste) von frontend aufgerufen
	@GetMapping("/users")
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public List<UserGetDTO> getAllUsers() { // holt alle user aus der datenbank via service
		// fetch all users in the internal representation
		List<User> users = userService.getUsers(); // users ist nun liste mit allen usern aufgelistet
		List<UserGetDTO> userGetDTOs = new ArrayList<>(); // erstellen neuer leerer liste für die dtos (version der user die ans frontend geschickt werden ohne passwort)

		// convert each user to the API representation
		for (User user : users) { // geht durch alle user und wandelt in dto um und addet sie zur liste
			UserGetDTO dto = DTOMapper.INSTANCE.convertEntityToUserGetDTO(user);
			dto.setToken(null);
			userGetDTOs.add(dto); // siehe dto skripte im rest ordner
		}
		return userGetDTOs; // fertige liste wird dann ans frontend gesendet
	}
// POST /users - frontend ruft das auf wenn ein neuer User sich registriert
	@PostMapping("/users")
	@ResponseStatus(HttpStatus.CREATED)
	@ResponseBody
    // @Valid is used to trigger validation of the UserPostDTO based on the annotations in that class (e.g., @Size for username)
	public UserGetDTO createUser(@Valid @RequestBody UserPostDTO userPostDTO) {
		// convert API user to internal representation
        // @RequestBody holt die userdaten (username, password, bio) aus dem request body des frontends
		User userInput = DTOMapper.INSTANCE.convertUserPostDTOtoEntity(userPostDTO); // DTO zu Entity umwandeln

		// create user
		User createdUser = userService.createUser(userInput); // user in datenbank speichern via service
		// convert internal representation of user back to API
		return DTOMapper.INSTANCE.convertEntityToUserGetDTO(createdUser); // fertigen user ans frontend schicken
	}
    // GET/users - für das Abrufen eines einzelnen Profils des frontends
    @GetMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public UserGetDTO getUserById(@PathVariable Long userId) {User user = userService.getUserById(userId);
        // @PathVariable holt die userid aus url und return schikt user ans Fromtend
    UserGetDTO dto = DTOMapper.INSTANCE.convertEntityToUserGetDTO(user);
    dto.setToken(null);
    return dto;
    }

    // PUT /users/{userId}- für Ändern des Passworts
    @PutMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    // user id aus der URL wird als Variable geholt und die Daten im Body des Requests ebenfalls
    public void updateUser(@PathVariable Long userId, @RequestBody UserPutDTO userPutDTO) {
        User userInput = DTOMapper.INSTANCE.convertUserPutDTOtoEntity(userPutDTO);
        userService.updateUser(userId, userInput);
    }
        // @PathVariable holt userId aus URL, @RequestBody holt neues passwort aus request body


    // POST /login - wenn user sich einloggen will
    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public UserGetDTO loginUser(@RequestBody UserPostDTO userPostDTO) {
        // @RequestBody holt username und passwort aus dem request body
        User user = userService.loginUser(userPostDTO.getUsername(), userPostDTO.getPassword()); // credentials werden geprüft
        return DTOMapper.INSTANCE.convertEntityToUserGetDTO(user); // eingeloggten user ans frontend senden
    }

	// endpoint according to REST interface table
    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.OK)
    // needs to be authenticated so we request token
    public void logoutUser(@RequestHeader("Authorization") String token) {
        userService.logoutUser(token);
    }

    // beacon logout — called when tab closes
    @PostMapping("/auth/logout/beacon")
    @ResponseStatus(HttpStatus.OK)
    public void logoutUserBeacon(@RequestBody(required = false) String body) {
        if (body == null || body.isBlank()) return;
        String cleaned = body.trim();
        String token;
        if (cleaned.startsWith("{")) {
            token = cleaned.replace("{", "").replace("}", "")
                    .replace("\"token\":", "").replace("\"", "").trim();
        } else if (cleaned.startsWith("token=")) {
            token = URLDecoder.decode(cleaned.substring("token=".length()), StandardCharsets.UTF_8);
        } else {
            token = cleaned;
        }
        if (!token.isBlank()) {
            userService.logoutUser(token); // use existing userService method
        }
    }

    // heartbeat — frontend activity tracking (active/focused tab + user input)
    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleHeartbeat(@RequestHeader("Authorization") String token) {
        userService.heartbeat(token);
    }

    @GetMapping("/users/{id}/history")
    public List<SessionHistoryDTO> getUserHistory(@PathVariable Long id, @RequestHeader("Authorization") String token) {
        
        User user = userRepository.findByToken(token);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token!");
        }

        List<Session> sessionHistory = historyService.getUserSessionHistory(id);
        List<SessionHistoryDTO> sessionHistoryDTOs = DTOMapper.INSTANCE.convertEntityListToSessionHistoryDTOList(sessionHistory);
        return sessionHistoryDTOs;
    }

}
