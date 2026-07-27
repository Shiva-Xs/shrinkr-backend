package com.shivaxdev.shrinkr.service;

import com.shivaxdev.shrinkr.dto.EditRequest;
import com.shivaxdev.shrinkr.dto.LinkInfoResponse;
import com.shivaxdev.shrinkr.dto.ShortenRequest;
import com.shivaxdev.shrinkr.dto.ShortenResult;
import com.shivaxdev.shrinkr.exception.PasswordProtectedException;
import com.shivaxdev.shrinkr.model.ShortLink;
import com.shivaxdev.shrinkr.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LinkService {

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private final LinkRepository     linkRepository;
    private final SlugService        slugService;
    private final RateLimitService   rateLimitService;
    private final MalwareScanService malwareScanService;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String CACHE_KEY_PREFIX  = "slug:";
    private static final String BUFFER_KEY_PREFIX = "clicks:buffer:";

    public ShortenResult shorten(ShortenRequest req, String clientIp) {

        if (!rateLimitService.isAllowed(clientIp)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Try again later.");
        }

        validateUrl(req.getUrl());

        String rawToken     = UUID.randomUUID().toString();
        String tokenHash    = DigestUtils.sha256Hex(rawToken);
        String passwordHash = (req.getPassword() != null && !req.getPassword().isBlank())
                ? BCrypt.hashpw(req.getPassword(), BCrypt.gensalt())
                : null;

        ShortLink saved = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            ShortLink link = ShortLink.builder()
                    .slug(slugService.generateUniqueSlug())
                    .originalUrl(req.getUrl())
                    .scanStatus("PENDING")
                    .expiresAt(req.getExpiresAt())
                    .maxClicks(req.getMaxClicks())
                    .deleteToken(tokenHash)
                    .passwordHash(passwordHash)
                    .build();
            try {
                saved = linkRepository.save(link);
                break;
            } catch (DataIntegrityViolationException e) {
                log.debug("Slug collision on attempt {} — retrying", attempt + 1);
            }
        }

        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to create link. Please try again.");
        }

        try {
            malwareScanService.scanAndUpdate(saved.getId(), req.getUrl());
        } catch (Exception e) {

            log.warn("Async scan task rejected for slug={} — scan will not run, link stays PENDING permanently. reason={}",
                    saved.getSlug(), e.getMessage());
        }

        return ShortenResult.builder()
                .originalUrl(req.getUrl())
                .slug(saved.getSlug())
                .shortUrl(baseUrl + "/" + saved.getSlug())
                .manageUrl(frontendUrl + "/manage/" + saved.getSlug() + "?token=" + rawToken)
                .build();
    }

    public String resolveRedirect(String slug) {

        String cachedUrl = safeCacheGet(CACHE_KEY_PREFIX + slug);
        if (cachedUrl != null) {
            safeBufferIncrement(slug);
            return cachedUrl;
        }

        ShortLink link = findLinkOrThrow(slug);

        if (!link.isActive()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has been deleted.");
        }

        if ("FLAGGED".equals(link.getScanStatus())) {
            return frontendUrl + "/warning/" + slug;
        }

        if (link.getExpiresAt() != null && LocalDateTime.now().isAfter(link.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has expired.");
        }

        if (link.getPasswordHash() != null) {

            throw new PasswordProtectedException(slug);
        }

        Long newBuffered = safeBufferIncrement(slug);
        if (link.getMaxClicks() != null) {
            int total = link.getClickCount() + (newBuffered != null ? newBuffered.intValue() : 1);
            if (total > link.getMaxClicks()) {
                safeBufferDecrement(slug);
                throw new ResponseStatusException(HttpStatus.GONE, "This link has reached its click limit.");
            }
        }

        if (isCacheable(link)) {
            Duration ttl = Duration.ofHours(24);
            if (link.getExpiresAt() != null) {
                Duration untilExpiry = Duration.between(LocalDateTime.now(), link.getExpiresAt());
                if (untilExpiry.compareTo(ttl) < 0) ttl = untilExpiry;
            }
            if (ttl.getSeconds() >= 1) {   
                try {
                    redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + slug, link.getOriginalUrl(), ttl);
                } catch (DataAccessException e) {
                    log.warn("Redis unavailable — slug={} not cached, serving from DB. reason={}",
                            slug, e.getMessage());
                }
            }
        }

        return link.getOriginalUrl();
    }

    private boolean isCacheable(ShortLink link) {
        return "CLEAN".equals(link.getScanStatus())
                && link.isActive()
                && link.getMaxClicks()    == null
                && link.getPasswordHash() == null;
    }

    @Scheduled(fixedDelay = 60_000)
    public void flushClickBuffer() {
        ScanOptions options = ScanOptions.scanOptions()
                .match(BUFFER_KEY_PREFIX + "*")
                .count(100)
                .build();

        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        } catch (DataAccessException e) {

            log.warn("Redis unavailable — click flush skipped this cycle. reason={}", e.getMessage());
            return;
        }

        if (keys.isEmpty()) return;

        for (String key : keys) {
            try {

                String value = redisTemplate.opsForValue().getAndDelete(key);
                if (value == null) continue;

                int count;
                try {
                    count = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    log.warn("Click flush: non-numeric value key={} value={}", key, value);
                    continue;
                }
                if (count <= 0) continue;

                String slug = key.substring(BUFFER_KEY_PREFIX.length());
                try {

                    linkRepository.incrementClickCount(slug, count);
                } catch (Exception dbEx) {

                    redisTemplate.opsForValue().increment(BUFFER_KEY_PREFIX + slug, count);
                    throw dbEx;
                }

            } catch (Exception e) {
                log.warn("Click flush failed key={} reason={}", key, e.getMessage());
            }
        }
    }

    public LinkInfoResponse getInfo(String slug) {
        ShortLink link = findLinkOrThrow(slug);
        if (!link.isActive()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has been deleted.");
        }
        return LinkInfoResponse.fromEntity(link, getBufferedClickCount(slug));
    }

    public LinkInfoResponse getStats(String slug, String rawToken) {
        ShortLink link = findLinkOrThrow(slug);
        verifyToken(rawToken, link);
        if (!link.isActive()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has been deleted.");
        }
        return LinkInfoResponse.fromEntity(link, getBufferedClickCount(slug));
    }

    public String unlock(String slug, String password, String clientIp) {
        if (!rateLimitService.isAllowed(clientIp)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Try again later.");
        }
        ShortLink link = findLinkOrThrow(slug);

        if (!link.isActive()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has been deleted.");
        }
        if ("FLAGGED".equals(link.getScanStatus())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has been flagged as unsafe.");
        }

        if (link.getExpiresAt() != null && LocalDateTime.now().isAfter(link.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has expired.");
        }
        if (link.getPasswordHash() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This link is not password protected.");
        }

        if (!BCrypt.checkpw(password, link.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect password.");
        }

        Long newBuffered = safeBufferIncrement(slug);
        if (link.getMaxClicks() != null) {
            int total = link.getClickCount() + (newBuffered != null ? newBuffered.intValue() : 1);
            if (total > link.getMaxClicks()) {
                safeBufferDecrement(slug);
                throw new ResponseStatusException(HttpStatus.GONE, "This link has reached its click limit.");
            }
        }
        return link.getOriginalUrl();
    }

    @Transactional
    public void editLink(String slug, String rawToken, EditRequest req) {
        ShortLink link = findLinkOrThrow(slug);
        verifyToken(rawToken, link);

        if (!link.isActive()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has been deleted.");
        }

        if (req.getExpiresAt() != null)          link.setExpiresAt(req.getExpiresAt());
        else if (req.isClearExpiry())            link.setExpiresAt(null);

        if (req.getMaxClicks() != null)          link.setMaxClicks(req.getMaxClicks());
        else if (req.isClearMaxClicks())         link.setMaxClicks(null);

        if (req.isClearPassword())                                  link.setPasswordHash(null);
        else if (req.getPassword() != null && !req.getPassword().isBlank()) {
            link.setPasswordHash(BCrypt.hashpw(req.getPassword(), BCrypt.gensalt()));
        }

        linkRepository.save(link);
        try {
            redisTemplate.delete(CACHE_KEY_PREFIX + slug);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable — cache not cleared for slug={} reason={}", slug, e.getMessage());
        }
    }

    @Transactional
    public void deleteLink(String slug, String rawToken) {
        ShortLink link = findLinkOrThrow(slug);
        verifyToken(rawToken, link);

        link.setActive(false);
        linkRepository.save(link);
        try {
            redisTemplate.delete(CACHE_KEY_PREFIX + slug);
            redisTemplate.delete(BUFFER_KEY_PREFIX + slug);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable — cache/buffer not cleared for slug={} reason={}", slug, e.getMessage());
        }
    }

    private ShortLink findLinkOrThrow(String slug) {
        return linkRepository.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found."));
    }

    public void assertLinkAccessible(String slug) {
        ShortLink link = findLinkOrThrow(slug);
        if (!link.isActive()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has been deleted.");
        }
    }

    private int getBufferedClickCount(String slug) {
        String buffered = safeCacheGet(BUFFER_KEY_PREFIX + slug);
        if (buffered == null) return 0;
        try {
            return Integer.parseInt(buffered);
        } catch (NumberFormatException e) {
            log.warn("Non-numeric click buffer slug={} value={}", slug, buffered);
            return 0;
        }
    }

    private String safeCacheGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable — read skipped key={} reason={}", key, e.getMessage());
            return null;
        }
    }

    private Long safeBufferIncrement(String slug) {
        try {
            return redisTemplate.opsForValue().increment(BUFFER_KEY_PREFIX + slug);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable — click not counted slug={} reason={}", slug, e.getMessage());
            return null;
        }
    }

    private void safeBufferDecrement(String slug) {
        try {
            redisTemplate.opsForValue().decrement(BUFFER_KEY_PREFIX + slug);
        } catch (DataAccessException e) {

        }
    }

    private void verifyToken(String rawToken, ShortLink link) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing X-Delete-Token header.");
        }
        boolean valid = MessageDigest.isEqual(
                DigestUtils.sha256Hex(rawToken).getBytes(StandardCharsets.UTF_8),
                link.getDeleteToken().getBytes(StandardCharsets.UTF_8)
        );
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid token.");
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank.");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL format.");
        }
        String appHost = URI.create(baseUrl).getHost();
        if (uri.getHost() != null && uri.getHost().equalsIgnoreCase(appHost)) {
            throw new IllegalArgumentException("Cannot shorten a Shrinkr URL.");
        }

        try {
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (address.isSiteLocalAddress()
             || address.isLoopbackAddress()
             || address.isLinkLocalAddress()
             || address.isAnyLocalAddress()) {
                throw new IllegalArgumentException("URL resolves to a private or internal address.");
            }

            if (address instanceof java.net.Inet6Address) {
                byte[] bytes = address.getAddress();
                if ((bytes[0] & 0xFE) == 0xFC) {
                    throw new IllegalArgumentException("URL resolves to a private or internal address.");
                }
            }

            if (address instanceof java.net.Inet4Address) {
                byte[] bytes = address.getAddress();
                int b0 = bytes[0] & 0xFF;
                int b1 = bytes[1] & 0xFF;
                if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                    throw new IllegalArgumentException("URL resolves to a private or internal address.");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("URL hostname could not be resolved: " + uri.getHost());
        }
    }
}
