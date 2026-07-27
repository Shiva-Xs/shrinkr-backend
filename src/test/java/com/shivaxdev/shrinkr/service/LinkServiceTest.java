package com.shivaxdev.shrinkr.service;

import com.shivaxdev.shrinkr.dto.ShortenRequest;
import com.shivaxdev.shrinkr.exception.PasswordProtectedException;
import com.shivaxdev.shrinkr.model.ShortLink;
import com.shivaxdev.shrinkr.repository.LinkRepository;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

    @Mock LinkRepository     linkRepository;
    @Mock SlugService        slugService;
    @Mock RateLimitService   rateLimitService;
    @Mock MalwareScanService malwareScanService;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks LinkService linkService;

    private static final String HASH = BCrypt.hashpw("secret", BCrypt.gensalt());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(linkService, "baseUrl",     "http://localhost:8080");
        ReflectionTestUtils.setField(linkService, "frontendUrl", "http://localhost:3000");

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void resolveRedirect_cacheHit_returnsUrlWithoutDbCall() {
        when(valueOps.get("slug:abc123")).thenReturn("https://example.com");

        assertThat(linkService.resolveRedirect("abc123")).isEqualTo("https://example.com");
        verifyNoInteractions(linkRepository);
    }

    @Test
    void resolveRedirect_flaggedLink_redirectsToWarningPage() {
        stubLink(activeLink("abc123", "https://evil.com").scanStatus("FLAGGED").build());

        assertThat(linkService.resolveRedirect("abc123"))
                .isEqualTo("http://localhost:3000/warning/abc123");
    }

    @Test
    void resolveRedirect_atExactClickCap_succeeds() {
        stubLink(activeLink("abc123", "https://example.com").maxClicks(5).clickCount(4).build());
        when(valueOps.increment("clicks:buffer:abc123")).thenReturn(1L); 

        assertThat(linkService.resolveRedirect("abc123")).isEqualTo("https://example.com");
        verify(valueOps, never()).decrement(anyString());
    }

    @Test
    void resolveRedirect_clickCapExceeded_throws410AndRollsBackBuffer() {
        stubLink(activeLink("abc123", "https://example.com").maxClicks(5).clickCount(5).build());
        when(valueOps.increment("clicks:buffer:abc123")).thenReturn(1L); 

        assertThatThrownBy(() -> linkService.resolveRedirect("abc123"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("click limit");
        verify(valueOps).decrement("clicks:buffer:abc123");
    }

    @Test
    void resolveRedirect_expiredLink_throws410() {
        stubLink(activeLink("abc123", "https://example.com")
                .expiresAt(LocalDateTime.now().minusDays(1)).build());

        assertThatThrownBy(() -> linkService.resolveRedirect("abc123"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expired");
    }

    @Test
    void resolveRedirect_passwordProtected_throwsPasswordProtectedException() {
        stubLink(activeLink("abc123", "https://example.com").passwordHash(HASH).build());

        assertThatThrownBy(() -> linkService.resolveRedirect("abc123"))
                .isInstanceOf(PasswordProtectedException.class);
    }

    @Test
    void resolveRedirect_redisDown_stillServesFromDatabase() {

        when(valueOps.get("slug:abc123")).thenThrow(new RedisConnectionFailureException("redis down"));
        when(valueOps.increment("clicks:buffer:abc123")).thenThrow(new RedisConnectionFailureException("redis down"));
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(linkRepository.findBySlug("abc123"))
                .thenReturn(Optional.of(activeLink("abc123", "https://example.com").build()));

        assertThat(linkService.resolveRedirect("abc123")).isEqualTo("https://example.com");
    }

    @Test
    void resolveRedirect_expiringLink_cachedWithTtlCappedAtExpiry() {

        stubLink(activeLink("abc123", "https://example.com")
                .expiresAt(LocalDateTime.now().plusMinutes(30)).build());
        when(valueOps.increment("clicks:buffer:abc123")).thenReturn(1L);

        assertThat(linkService.resolveRedirect("abc123")).isEqualTo("https://example.com");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("slug:abc123"), eq("https://example.com"), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(30));
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushClickBuffer_drainsBufferWithAtomicDbIncrement() {

        Cursor<String> cursor = mock(Cursor.class);

        doAnswer(inv -> {
            java.util.function.Consumer<String> consumer = inv.getArgument(0);
            consumer.accept("clicks:buffer:abc123");
            return null;
        }).when(cursor).forEachRemaining(any());
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(valueOps.getAndDelete("clicks:buffer:abc123")).thenReturn("7");

        linkService.flushClickBuffer();

        verify(linkRepository).incrementClickCount("abc123", 7);
        verify(linkRepository, never()).save(any());
    }

    @Test
    void unlock_correctPassword_returnsOriginalUrl() {
        stubLink(activeLink("abc123", "https://example.com").passwordHash(HASH).build());
        when(rateLimitService.isAllowed("1.2.3.4")).thenReturn(true);
        when(valueOps.increment("clicks:buffer:abc123")).thenReturn(1L);

        assertThat(linkService.unlock("abc123", "secret", "1.2.3.4")).isEqualTo("https://example.com");
    }

    @Test
    void unlock_wrongPassword_throws401() {
        stubLink(activeLink("abc123", "https://example.com").passwordHash(HASH).build());
        when(rateLimitService.isAllowed("1.2.3.4")).thenReturn(true);

        assertThatThrownBy(() -> linkService.unlock("abc123", "wrong", "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Incorrect password");
    }

    @Test
    void unlock_atExactClickCap_succeedsMatchingRedirectBehaviour() {

        stubLink(activeLink("abc123", "https://example.com")
                .passwordHash(HASH).maxClicks(5).clickCount(4).build());
        when(rateLimitService.isAllowed("1.2.3.4")).thenReturn(true);
        when(valueOps.increment("clicks:buffer:abc123")).thenReturn(1L);

        assertThat(linkService.unlock("abc123", "secret", "1.2.3.4")).isEqualTo("https://example.com");
        verify(valueOps, never()).decrement(anyString());
    }

    @Test
    void unlock_clickCapExceeded_throws410AndRollsBackBuffer() {

        stubLink(activeLink("abc123", "https://example.com")
                .passwordHash(HASH).maxClicks(5).clickCount(4).build());
        when(rateLimitService.isAllowed("1.2.3.4")).thenReturn(true);
        when(valueOps.increment("clicks:buffer:abc123")).thenReturn(2L);

        assertThatThrownBy(() -> linkService.unlock("abc123", "secret", "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("click limit");
        verify(valueOps).decrement("clicks:buffer:abc123");
    }

    @Test
    void unlock_flaggedLink_throws410() {
        stubLink(activeLink("abc123", "https://evil.com").scanStatus("FLAGGED").passwordHash(HASH).build());
        when(rateLimitService.isAllowed("1.2.3.4")).thenReturn(true);

        assertThatThrownBy(() -> linkService.unlock("abc123", "secret", "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("flagged");
    }

    @Test
    void shorten_rateLimitExceeded_throws429() {
        when(rateLimitService.isAllowed("1.2.3.4")).thenReturn(false);
        ShortenRequest req = new ShortenRequest();
        ReflectionTestUtils.setField(req, "url", "https://example.com");

        assertThatThrownBy(() -> linkService.shorten(req, "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Rate limit");
    }

    @Test
    void shorten_invalidUrlScheme_throwsIllegalArgumentException() {
        when(rateLimitService.isAllowed("1.2.3.4")).thenReturn(true);
        ShortenRequest req = new ShortenRequest();
        ReflectionTestUtils.setField(req, "url", "ftp://example.com/file");

        assertThatThrownBy(() -> linkService.shorten(req, "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteLink_softDeletesLinkAndEvictsCache() {
        String rawToken = "my-raw-token";
        ShortLink link = activeLink("abc123", "https://example.com")
                .deleteToken(DigestUtils.sha256Hex(rawToken)).build();
        when(linkRepository.findBySlug("abc123")).thenReturn(Optional.of(link));

        linkService.deleteLink("abc123", rawToken);

        assertThat(link.isActive()).isFalse();
        verify(linkRepository).saveAndFlush(link);
        verify(redisTemplate).delete("slug:abc123");
        verify(redisTemplate).delete("clicks:buffer:abc123");
    }

    private void stubLink(ShortLink link) {

        lenient().when(valueOps.get("slug:" + link.getSlug())).thenReturn(null);
        when(linkRepository.findBySlug(link.getSlug())).thenReturn(Optional.of(link));
    }

    private ShortLink.ShortLinkBuilder activeLink(String slug, String url) {
        return ShortLink.builder()
                .slug(slug).originalUrl(url)
                .scanStatus("CLEAN").isActive(true)
                .deleteToken("dummy-token-hash");
    }
}
