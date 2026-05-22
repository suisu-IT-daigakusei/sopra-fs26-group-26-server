package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Card;
import ch.uzh.ifi.hase.soprafs26.rest.dto.CardDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeckOfCardsAPIServiceTest {

    @Test
    void createNewDeckId_successAndMissingIdBehaviors() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        DeckOfCardsAPIService service = new DeckOfCardsAPIService(template);

        server.expect(requestTo("https://deckofcardsapi.com/api/deck/new/"))
                .andRespond(withSuccess("{\"deck_id\":\"deck-123\"}", MediaType.APPLICATION_JSON));
        assertEquals("deck-123", service.createNewDeckId());
        server.verify();

        MockRestServiceServer failingServer = MockRestServiceServer.bindTo(template).build();
        failingServer.expect(requestTo("https://deckofcardsapi.com/api/deck/new/"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, service::createNewDeckId);
        failingServer.verify();
    }

    @Test
    void shuffleDeck_nullBodyThrows() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        DeckOfCardsAPIService service = new DeckOfCardsAPIService(template);

        server.expect(requestTo("https://deckofcardsapi.com/api/deck/d1/shuffle/"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> service.shuffleDeck("d1"));
        server.verify();
    }

    @Test
    void drawFromDeck_mapsFaceCardAliasesAndValidatesBody() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        DeckOfCardsAPIService service = new DeckOfCardsAPIService(template);

        server.expect(requestTo("https://deckofcardsapi.com/api/deck/d2/draw/?count=3"))
                .andRespond(withSuccess(
                        "{\"cards\":[{\"code\":\"KH\"},{\"code\":\"KD\"},{\"code\":\"AS\"}]}",
                        MediaType.APPLICATION_JSON));

        List<CardDTO> cards = service.drawFromDeck("d2", 3);
        assertEquals(List.of("X1", "X2", "AS"), cards.stream().map(CardDTO::getCode).toList());
        server.verify();

        MockRestServiceServer failingServer = MockRestServiceServer.bindTo(template).build();
        failingServer.expect(requestTo("https://deckofcardsapi.com/api/deck/d2/draw/?count=1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> service.drawFromDeck("d2", 1));
        failingServer.verify();
    }

    @Test
    void returnDrawnCardsToDeck_skipsInvalidInputAndMapsCodesForApi() {
        RestTemplate template = new RestTemplate();
        DeckOfCardsAPIService service = new DeckOfCardsAPIService(template);

        service.returnDrawnCardsToDeck(null, List.of(card("AS")));
        service.returnDrawnCardsToDeck("  ", List.of(card("AS")));
        service.returnDrawnCardsToDeck("deck", null);
        service.returnDrawnCardsToDeck("deck", List.of());
        service.returnDrawnCardsToDeck("deck", List.of(card(null), card(" "), card("\t")));

        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("https://deckofcardsapi.com/api/deck/deck/return/?cards=KH,KD,AS"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        service.returnDrawnCardsToDeck("deck", List.of(card("X1"), card("X2"), card("AS")));
        server.verify();
    }

    @Test
    void returnDrawnCardsToDeck_nullApiBodyThrows() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        DeckOfCardsAPIService service = new DeckOfCardsAPIService(template);

        server.expect(requestTo("https://deckofcardsapi.com/api/deck/deck-2/return/?cards=AS"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.returnDrawnCardsToDeck("deck-2", List.of(card("AS"))));
        assertTrue(exception.getMessage().contains("return returned no body"));
        server.verify();
    }

    private static Card card(String code) {
        Card card = new Card();
        card.setCode(code);
        return card;
    }
}
