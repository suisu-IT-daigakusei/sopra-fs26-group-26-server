package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.rest.dto.MoveLogEntryDTO;
import ch.uzh.ifi.hase.soprafs26.service.MoveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class MoveControllerTest {

    private MockMvc mockMvc;
    private MoveService moveService;

    @BeforeEach
    void setUp() {
        moveService = Mockito.mock(MoveService.class);
        MoveController moveController = new MoveController(moveService);
        mockMvc = MockMvcBuilders.standaloneSetup(moveController).build();
    }

    @Test
    void getSessionLog_validRequest_returnsMoveEntries() throws Exception {
        MoveLogEntryDTO entry = new MoveLogEntryDTO();
        entry.setUserId(1L);
        entry.setUsername("alice");
        entry.setActionType("DRAW");
        entry.setDetails("draw pile");
        entry.setOwnMove(true);
        entry.setTimestamp(Instant.parse("2026-05-22T00:00:00Z"));

        Mockito.when(moveService.getSessionLog("S1", "token-1")).thenReturn(List.of(entry));

        mockMvc.perform(get("/sessions/S1/log").header("Authorization", "token-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(1))
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].actionType").value("DRAW"))
                .andExpect(jsonPath("$[0].details").value("draw pile"))
                .andExpect(jsonPath("$[0].ownMove").value(true));
    }

    @Test
    void getSessionLog_invalidToken_propagatesUnauthorized() throws Exception {
        Mockito.when(moveService.getSessionLog("S1", "bad-token"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        mockMvc.perform(get("/sessions/S1/log").header("Authorization", "bad-token"))
                .andExpect(status().isUnauthorized());
    }
}

