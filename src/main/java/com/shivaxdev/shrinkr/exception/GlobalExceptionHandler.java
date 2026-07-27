package com.shivaxdev.shrinkr.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @ExceptionHandler(PasswordProtectedException.class)
    public ResponseEntity<?> handlePasswordProtected(PasswordProtectedException ex,
                                                     HttpServletRequest request) {
        if (isSlugRedirectRequest(request)) {
            return ResponseEntity
                    .status(HttpStatus.FOUND)
                    .location(URI.create(frontendUrl + "/unlock/" + ex.getSlug()))
                    .build();
        }
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
    public ResponseEntity<?> handleResponseStatus(ResponseStatusException ex,
                                                  HttpServletRequest request) {
        // For browser-initiated slug redirects, send to the SPA's error pages
        // instead of returning raw JSON that the browser would display.
        if (isSlugRedirectRequest(request)) {
            String path = request.getRequestURI();
            String slug = path.length() > 1 ? path.substring(1) : "unknown";
            return ResponseEntity
                    .status(HttpStatus.FOUND)
                    .location(URI.create(frontendUrl + "/gone/" + slug))
                    .build();
        }
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

    /**
     * Returns true when the request is a direct browser navigation to /{slug},
     * i.e. NOT an /api/v1/* call. API calls should always get JSON back.
     * Browser redirect requests come in as GET on a short slug path.
     */
    private boolean isSlugRedirectRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return "GET".equalsIgnoreCase(method)
                && path != null
                && !path.startsWith("/api/")
                && path.matches("/[a-zA-Z0-9]{4,12}");
    }
}
