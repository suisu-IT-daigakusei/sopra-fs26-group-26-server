package ch.uzh.ifi.hase.soprafs26.exceptions;

import ch.uzh.ifi.hase.soprafs26.service.HotEndpointRateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionAdviceTest {

    private final GlobalExceptionAdvice advice = new GlobalExceptionAdvice();

    @Test
    void handleConflict_mapsIllegalStateTo409() {
        WebRequest request = mock(WebRequest.class);
        IllegalStateException exception = new IllegalStateException("illegal-state");

        ResponseEntity<Object> response = advice.handleConflict(exception, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("This should be application specific", response.getBody());
    }

    @Test
    void handleHotEndpointRateLimit_setsRetryAfterAndBody() {
        HotEndpointRateLimitException exception = new HotEndpointRateLimitException("slow down", 4L);

        ResponseEntity<Map<String, Object>> response = advice.handleHotEndpointRateLimit(exception);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("4", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(429, response.getBody().get("status"));
        assertEquals("Too Many Requests", response.getBody().get("error"));
        assertEquals("slow down", response.getBody().get("message"));
    }

    @Test
    void handleTransactionSystemException_returnsConflictResponseStatusException() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURL()).thenReturn(new StringBuffer("https://host/api/path"));
        TransactionSystemException root = new TransactionSystemException("tx-failed");

        ResponseStatusException response = advice.handleTransactionSystemException(root, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("tx-failed", response.getReason());
        assertSame(root, response.getCause());
    }

    @Test
    void handleException_returnsInternalServerErrorResponseStatusException() {
        Exception root = new Exception("server-failed");

        ResponseStatusException response = advice.handleException(root);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getReason().contains("server-failed"));
        assertSame(root, response.getCause());
    }
}
