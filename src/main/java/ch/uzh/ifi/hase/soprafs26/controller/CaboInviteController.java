package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteCreateDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteDecisionDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInvitePendingDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteRespondDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CaboInviteSentDTO;
import ch.uzh.ifi.hase.soprafs26.service.CaboInviteService;
import ch.uzh.ifi.hase.soprafs26.service.HotEndpointRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class CaboInviteController {

    private final CaboInviteService caboInviteService;
    private final HotEndpointRateLimiter hotEndpointRateLimiter;

    public CaboInviteController(CaboInviteService caboInviteService) {
        this(caboInviteService, null);
    }

    @Autowired
    public CaboInviteController(CaboInviteService caboInviteService, HotEndpointRateLimiter hotEndpointRateLimiter) {
        this.caboInviteService = caboInviteService;
        this.hotEndpointRateLimiter = hotEndpointRateLimiter;
    }

    private void enforceHotReadLimit(String endpointKey, String token, HttpServletRequest request) {
        if (hotEndpointRateLimiter == null) {
            return;
        }
        String forwardedForHeader = request == null ? null : request.getHeader("X-Forwarded-For");
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        hotEndpointRateLimiter.enforceHotReadLimit(endpointKey, token, forwardedForHeader, remoteAddress);
    }

    /** Check pending invites */
    @GetMapping("/users/{userId}/invites")
    @ResponseStatus(HttpStatus.OK)
    public List<CaboInvitePendingDTO> getPending(@PathVariable Long userId,
                                                 @RequestHeader("Authorization") String token,
                                                 HttpServletRequest request) {
        enforceHotReadLimit("invites-pending", token, request);
        return caboInviteService.getPendingInvitesForUser(token, userId);
    }

    /** Host: invites for the current waiting lobby */
    @GetMapping("/users/{userId}/invites/sent")
    @ResponseStatus(HttpStatus.OK)
    public List<CaboInviteSentDTO> getSentForHost(@PathVariable Long userId,
                                                  @RequestHeader("Authorization") String token,
                                                  HttpServletRequest request) {
        enforceHotReadLimit("invites-sent", token, request);
        return caboInviteService.getSentInvitesForUser(token, userId);
    }

    /** Invite players */
    @PostMapping("/users/{userId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public CaboInvitePendingDTO createInvite(@PathVariable Long userId,
                                             @RequestHeader("Authorization") String token,
                                             @RequestBody CaboInviteCreateDTO body) {
        return caboInviteService.createInviteAsUser(token, userId, body);
    }

    /** Invitee accepts or declines */
    @PatchMapping("/users/{userId}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.OK)
    public CaboInviteRespondDTO respond(@PathVariable Long userId,
                                        @PathVariable Long inviteId,
                                        @RequestHeader("Authorization") String token,
                                        @RequestBody CaboInviteDecisionDTO body) {
        return caboInviteService.respondForUser(token, userId, inviteId, body);
    }

    /** Delete invite */
    @DeleteMapping("/users/{userId}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteInvite(@PathVariable Long userId,
                             @PathVariable Long inviteId,
                             @RequestHeader("Authorization") String token) {
        caboInviteService.deleteInviteForUser(token, userId, inviteId);
    }
}
