package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.rest.dto.DeckResponseDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardDTO;

import org.springframework.web.client.RestTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DeckOfCardsAPIService {

    private final String BASE_URL = "https://deckofcardsapi.com/api/deck";
    private final RestTemplate restTemplate;

    public DeckOfCardsAPIService() {
        this(new RestTemplate());
    }

    DeckOfCardsAPIService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate == null ? new RestTemplate() : restTemplate;
    }

    // create a deck and get its id
    public String createNewDeckId() {
        DeckResponseDTO created = restTemplate.getForObject(BASE_URL + "/new/", DeckResponseDTO.class);
        if (created == null || created.getDeckId() == null) {
            throw new IllegalStateException("Deck API /new/ returned no deck_id");
        }
        return created.getDeckId();
    }

    // shuffle a deck based on its id
    public void shuffleDeck(String deckId) {
        DeckResponseDTO response =
                restTemplate.getForObject(BASE_URL + "/" + deckId + "/shuffle/", DeckResponseDTO.class);
        if (response == null) {
            throw new IllegalStateException("Deck API shuffle returned no body");
        }
    }

    // draw n = count cards from the api based on the provided deckid
    public List<CardDTO> drawFromDeck(String deckId, int count) {
        DeckResponseDTO response =
                restTemplate.getForObject(BASE_URL + "/" + deckId + "/draw/?count=" + count, DeckResponseDTO.class);
        if (response == null || response.getCards() == null) {
            throw new IllegalStateException("Deck API draw returned no cards");
        }
        // map standard cards KH and KD to cabo cards X1 and X2
        for (CardDTO card : response.getCards()) {
            String code = card.getCode();
            if ("KH".equals(code)) {
                card.setCode("X1");
            } else if ("KD".equals(code)) {
                card.setCode("X2");
            }
        }
        return response.getCards();
    }

    // return cards to api deck
    public void returnDrawnCardsToDeck(String deckId, List<Card> cards) {
        if (deckId == null || deckId.isBlank() || cards == null || cards.isEmpty()) {
            return;
        } 
        String csv = cards.stream() // process each card
                .map(Card::getCode) // get the code string (AS, X1 etc)
                .filter(Objects::nonNull) // leave out cards with null code
                .filter(s -> !s.isBlank()) // leave out cards with empty string codes
                .map(DeckOfCardsAPIService::toDeckApiCardCode) // map X1 to KH, X2 to KD
                .collect(Collectors.joining(",")); // create comma separated values
        if (csv.isEmpty()) {
            return;
        }
        String url = BASE_URL + "/" + deckId + "/return/?cards=" + csv;
        DeckResponseDTO response = restTemplate.getForObject(url, DeckResponseDTO.class);
        if (response == null) {
            throw new IllegalStateException("Deck API return returned no body");
        }
    }

    // before sending cards back to the api, map back X1 and X2 to KH and KD
    private static String toDeckApiCardCode(String gameCode) {
        if ("X1".equals(gameCode)) {
            return "KH";
        }
        if ("X2".equals(gameCode)) {
            return "KD";
        }
        return gameCode;
    }
}
