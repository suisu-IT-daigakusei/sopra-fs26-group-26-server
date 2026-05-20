package ch.uzh.ifi.hase.soprafs26.exceptions;

import ch.uzh.ifi.hase.soprafs26.service.HotEndpointRateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

@ControllerAdvice(annotations = RestController.class)
public class GlobalExceptionAdvice extends ResponseEntityExceptionHandler {

    private final Logger log = LoggerFactory.getLogger(GlobalExceptionAdvice.class);

    @ExceptionHandler(value = { IllegalArgumentException.class, IllegalStateException.class })
    protected ResponseEntity<Object> handleConflict(RuntimeException ex, WebRequest request) {
        String bodyOfResponse = "This should be application specific";
        return handleExceptionInternal(ex, bodyOfResponse, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(HotEndpointRateLimitException.class)
    protected ResponseEntity<Map<String, Object>> handleHotEndpointRateLimit(HotEndpointRateLimitException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        Map<String, Object> body = Map.of(
                "status", HttpStatus.TOO_MANY_REQUESTS.value(),
                "error", "Too Many Requests",
                "message", ex.getReason() == null ? "Too many requests" : ex.getReason());
        return new ResponseEntity<>(body, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(TransactionSystemException.class)
    public ResponseStatusException handleTransactionSystemException(Exception ex, HttpServletRequest request) {
        log.error("Request: {} raised {}", request.getRequestURL(), ex);
        return new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    }

    @ExceptionHandler(HttpServerErrorException.InternalServerError.class)
    public ResponseStatusException handleException(Exception ex) {
        log.error("Default Exception Handler -> caught:", ex);
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
    }
}
