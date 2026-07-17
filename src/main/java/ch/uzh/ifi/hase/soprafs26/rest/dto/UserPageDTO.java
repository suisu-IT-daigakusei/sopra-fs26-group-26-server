package ch.uzh.ifi.hase.soprafs26.rest.dto;

import java.util.List;

/** Stable API envelope for the paged public user directory and leaderboard. */
public record UserPageDTO(
        List<UserGetDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public UserPageDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
