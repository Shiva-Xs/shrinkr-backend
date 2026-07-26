package com.shivaxdev.shrinkr.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PasswordProtectedException.class)
    public ResponseEntity<Map<String, Object>> handlePasswordProtected(PasswordProtectedException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "passwordProtected", true,
                        "slug", ex.getSlug()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error",   "VALIDATION_ERROR",
                        "message", message.isEmpty() ? "Invalid request body." : message
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error",   "INVALID_REQUEST",
                        "message", "Request body is missing or malformed JSON."
                ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity
                .status(ex.getStatusCode())
                .body(Map.of(
                        "error",   toErrorCode(ex.getStatusCode()),
                        "message", ex.getReason() != null ? ex.getReason() : "An error occurred"
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error",   "INVALID_URL",
                        "message", ex.getMessage()
                ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error",   "INTERNAL_ERROR",
                        "message", "An unexpected error occurred."
                ));
    }

    private String toErrorCode(HttpStatusCode status) {
        if (status == HttpStatus.NOT_FOUND)         return "LINK_NOT_FOUND";
        if (status == HttpStatus.GONE)              return "LINK_UNAVAILABLE";
        if (status == HttpStatus.TOO_MANY_REQUESTS) return "RATE_LIMITED";
        if (status == HttpStatus.UNAUTHORIZED)      return "UNAUTHORIZED";
        if (status == HttpStatus.FORBIDDEN)         return "FORBIDDEN";
        if (status == HttpStatus.BAD_REQUEST)       return "BAD_REQUEST";
        return "ERROR";
    }
}
