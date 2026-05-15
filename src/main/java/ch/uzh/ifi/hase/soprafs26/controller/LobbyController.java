package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatMessageDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyChatSendDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyReadyPatchDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbySettingsPatchDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.WaitingLobbyViewDTO;
import ch.uzh.ifi.hase.soprafs26.service.LobbyChatService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyGetDTO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;

import java.util.List;
import java.util.Map;

@RestController
public class LobbyController {

    private final LobbyService lobbyService;
    private final LobbyChatService lobbyChatService;

    public LobbyController(LobbyService lobbyService, LobbyChatService lobbyChatService) {
        this.lobbyService = lobbyService;
        this.lobbyChatService = lobbyChatService;
    }

    // POST /lobbies — create a new lobby
    @PostMapping("/lobbies")
    @ResponseStatus(HttpStatus.CREATED)
    public LobbyGetDTO createLobby(@RequestHeader("Authorization") String token,
                             @RequestBody Map<String, Boolean> body) {
        Boolean isPublic = body.get("isPublic");
        Lobby lobby = lobbyService.createLobby(token, isPublic);
        return DTOMapper.INSTANCE.convertEntityToLobbyGetDTO(lobby); 
    }

    // GET /lobbies — get all public lobbies
    @GetMapping("/lobbies")
    @ResponseStatus(HttpStatus.OK)
    public List<LobbyGetDTO> getPublicLobbies(@RequestHeader("Authorization") String token) {
        List<Lobby> lobbies = lobbyService.getPublicLobbies(token);
        return DTOMapper.INSTANCE.convertEntityListToLobbyGetDTOList(lobbies);
    }

    // POST /lobbies/{sessionId}/players — join a lobby
    @PostMapping("/lobbies/{sessionId}/players")
    @ResponseStatus(HttpStatus.OK)
    public LobbyGetDTO joinLobby(@PathVariable String sessionId,
                           @RequestHeader("Authorization") String token) {
        Lobby lobby = lobbyService.joinLobby(sessionId, token);
        return DTOMapper.INSTANCE.convertEntityToLobbyGetDTO(lobby);
    }

    @PostMapping("/lobbies/{sessionId}/spectators")
    @ResponseStatus(HttpStatus.OK)
    public LobbyGetDTO joinLobbyAsSpectator(@PathVariable String sessionId,
                                            @RequestHeader("Authorization") String token) {
        Lobby lobby = lobbyService.joinLobbyAsSpectator(sessionId, token);
        return DTOMapper.INSTANCE.convertEntityToLobbyGetDTO(lobby);
    }

    @PatchMapping("/lobbies/{sessionId}/ready")
    @ResponseStatus(HttpStatus.OK)
    public WaitingLobbyViewDTO updateReadyState(@PathVariable String sessionId,
                                                @RequestHeader("Authorization") String token,
                                                @RequestBody LobbyReadyPatchDTO body) {
        Boolean nextReady = body == null ? null : body.getReady();
        return lobbyService.setPlayerReady(sessionId, token, nextReady);
    }

    @PatchMapping("/lobbies/{sessionId}/settings")
    @ResponseStatus(HttpStatus.OK)
    public LobbyGetDTO updateLobbySettings(@PathVariable String sessionId,
                                     @RequestHeader("Authorization") String token,
                                     @RequestBody LobbySettingsPatchDTO body) {
        Lobby lobby = lobbyService.updateLobbySettings(token, sessionId, body);
        return DTOMapper.INSTANCE.convertEntityToLobbyGetDTO(lobby);
    }

    @GetMapping("/lobbies/waiting/{sessionId}")
    @ResponseStatus(HttpStatus.OK)
    public WaitingLobbyViewDTO getWaitingLobby(@PathVariable String sessionId,
                                               @RequestHeader("Authorization") String token) {

        return lobbyService.getWaitingLobbyView(token, sessionId);
    }

    @GetMapping("/lobbies/{sessionId}/chat/messages")
    @ResponseStatus(HttpStatus.OK)
    public List<LobbyChatMessageDTO> getLobbyChatMessages(@PathVariable String sessionId,
                                                          @RequestHeader("Authorization") String token) {
        return lobbyChatService.getMessages(token, sessionId);
    }

    @PostMapping("/lobbies/{sessionId}/chat/messages")
    @ResponseStatus(HttpStatus.OK)
    public LobbyChatMessageDTO sendLobbyChatMessage(@PathVariable String sessionId,
                                                    @RequestHeader("Authorization") String token,
                                                    @RequestBody LobbyChatSendDTO body) {
        return lobbyChatService.sendMessage(token, sessionId, body);
    }

    @GetMapping("/lobbies/my/waiting")
    @ResponseStatus(HttpStatus.OK)
    public LobbyGetDTO getMyWaitingLobby(@RequestHeader("Authorization") String token) {
        Lobby lobby = lobbyService.getMyWaitingLobbyAsHost(token);
        return DTOMapper.INSTANCE.convertEntityToLobbyGetDTO(lobby);
    }

    // DELETE /lobbies/{sessionId}/players/{userId} — self leave or host kick
    @DeleteMapping("/lobbies/{sessionId}/players/{userId}")
    @ResponseStatus(HttpStatus.OK)
    public LobbyGetDTO removePlayerFromLobby(@PathVariable String sessionId,
                                         @RequestHeader("Authorization") String token,
                                         @PathVariable Long userId) {
    
        Lobby updatedLobby = lobbyService.removePlayerFromLobby(sessionId, token, userId);

        // If the lobby was deleted (last person left), return null 
        // (Spring will return an empty body)
        if (updatedLobby == null) {
            return null; 
        }

        // Map the updated entity to your existing DTO
        return DTOMapper.INSTANCE.convertEntityToLobbyGetDTO(updatedLobby);
    }
}
