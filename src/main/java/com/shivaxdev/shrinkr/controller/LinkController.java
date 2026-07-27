package com.shivaxdev.shrinkr.controller;

import com.shivaxdev.shrinkr.dto.EditRequest;
import com.shivaxdev.shrinkr.dto.LinkInfoResponse;
import com.shivaxdev.shrinkr.dto.ShortenRequest;
import com.shivaxdev.shrinkr.dto.ShortenResult;
import com.shivaxdev.shrinkr.service.LinkService;
import com.shivaxdev.shrinkr.service.QrService;
import com.shivaxdev.shrinkr.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class LinkController {

    @Value("${app.base-url}")
    private String baseUrl;

    private final LinkService      linkService;
    private final QrService        qrService;
    private final RateLimitService rateLimitService;

    @PostMapping("/api/v1/shorten")
    public ResponseEntity<ShortenResult> shorten(
            @RequestBody @Valid ShortenRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = resolveClientIp(httpRequest);
        ShortenResult result = linkService.shorten(request, clientIp);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/{slug}")
    public ResponseEntity<Void> redirect(@PathVariable String slug, HttpServletRequest httpRequest) {

        requireRedirectAllowance(httpRequest);

        String destination = linkService.resolveRedirect(slug);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(destination))
                .build();
    }

    @GetMapping("/api/v1/info/{slug}")
    public ResponseEntity<LinkInfoResponse> getInfo(@PathVariable String slug) {
        return ResponseEntity.ok(linkService.getInfo(slug));
    }

    @GetMapping("/api/v1/stats/{slug}")
    public ResponseEntity<LinkInfoResponse> getStats(
            @PathVariable String slug,
            @RequestHeader(value = "X-Delete-Token", required = false) String deleteToken) {

        return ResponseEntity.ok(linkService.getStats(slug, deleteToken));
    }

    @PostMapping("/api/v1/unlock/{slug}")
    public ResponseEntity<Map<String, String>> unlock(
            @PathVariable String slug,
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {

        String password = body.get("password");
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Password is required."));
        }

        String clientIp = resolveClientIp(httpRequest);
        String redirectUrl = linkService.unlock(slug, password, clientIp);
        return ResponseEntity.ok(Map.of("redirectUrl", redirectUrl));
    }

    @PutMapping("/api/v1/links/{slug}")
    public ResponseEntity<Void> editLink(
            @PathVariable String slug,
            @RequestHeader(value = "X-Delete-Token", required = false) String deleteToken,
            @RequestBody @Valid EditRequest body) {

        linkService.editLink(slug, deleteToken, body);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/links/{slug}")
    public ResponseEntity<Void> deleteLink(
            @PathVariable String slug,
            @RequestHeader(value = "X-Delete-Token", required = false) String deleteToken) {

        linkService.deleteLink(slug, deleteToken);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/qr/{slug}")
    public ResponseEntity<byte[]> getQrCode(@PathVariable String slug, HttpServletRequest httpRequest) {
        requireRedirectAllowance(httpRequest);
        linkService.assertLinkAccessible(slug);   

        String shortUrl = baseUrl + "/" + slug;
        byte[] png = qrService.generateQrPng(shortUrl);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + slug + ".png\"")
                .body(png);
    }

    private void requireRedirectAllowance(HttpServletRequest httpRequest) {
        if (!rateLimitService.isRedirectAllowed(resolveClientIp(httpRequest))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Try again later.");
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) {
            return cfIp.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[0].trim();
        }
        return request.getRemoteAddr();
    }
}
