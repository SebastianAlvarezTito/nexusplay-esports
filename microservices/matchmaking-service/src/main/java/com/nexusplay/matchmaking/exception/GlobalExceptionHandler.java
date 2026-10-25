package com.nexusplay.matchmaking.exception;

import com.nexusplay.matchmaking.client.TournamentRejectedException;
import com.nexusplay.matchmaking.client.TournamentUnavailableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** A no respondió (timeout / conexión / 5xx): respuesta CONTROLADA, nunca un stacktrace. */
    @ExceptionHandler(TournamentUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailable(TournamentUnavailableException ex) {
        Map<String, Object> body = body("tournament_service_unavailable",
                "El servicio de torneos no está disponible en este momento. Intenta nuevamente.");
        body.put("reason", ex.getReason());
        body.put("operation", ex.getOperation());
        return ResponseEntity.status(503).header("Retry-After", "5").body(body);
    }

    /** A respondió con un 4xx de negocio (404 torneo inexistente, 409 sin cupos...): se propaga el código. */
    @ExceptionHandler(TournamentRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(TournamentRejectedException ex) {
        return ResponseEntity.status(ex.getStatus()).body(body("tournament_rejected", ex.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getStatus().getReasonPhrase(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(body("Bad Request", msg));
    }

    private Map<String, Object> body(String error, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("message", message);
        m.put("timestamp", Instant.now().toString());
        return m;
    }
}
