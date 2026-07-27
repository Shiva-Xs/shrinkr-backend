package com.shivaxdev.shrinkr.controller;

import com.shivaxdev.shrinkr.dto.ShortenRequest;
import com.shivaxdev.shrinkr.dto.ShortenResult;
import com.shivaxdev.shrinkr.service.LinkService;
import com.shivaxdev.shrinkr.service.QrService;
import com.shivaxdev.shrinkr.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkControllerTest {

    @Mock LinkService linkService;
    @Mock QrService qrService;
    @Mock RateLimitService rateLimitService;

    @InjectMocks LinkController linkController;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(linkController, "baseUrl", "https://shrinkr.in");
    }

    @Test
    void shorten_withXForwardedFor_extractsOriginalClientIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");

        ShortenRequest shortenReq = new ShortenRequest();
        ReflectionTestUtils.setField(shortenReq, "url", "https://example.com");

        ShortenResult mockResult = ShortenResult.builder()
                .slug("abc1234")
                .shortUrl("https://shrinkr.in/abc1234")
                .originalUrl("https://example.com")
                .manageUrl("https://shrinkr.in/manage/abc1234?token=tok")
                .build();

        when(linkService.shorten(any(ShortenRequest.class), eq("203.0.113.195"))).thenReturn(mockResult);

        linkController.shorten(shortenReq, request);

        verify(linkService).shorten(any(ShortenRequest.class), eq("203.0.113.195"));
    }

    @Test
    void shorten_withCfConnectingIp_takesCfConnectingIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18");

        ShortenRequest shortenReq = new ShortenRequest();
        ReflectionTestUtils.setField(shortenReq, "url", "https://example.com");

        ShortenResult mockResult = ShortenResult.builder()
                .slug("abc1234")
                .shortUrl("https://shrinkr.in/abc1234")
                .originalUrl("https://example.com")
                .manageUrl("https://shrinkr.in/manage/abc1234?token=tok")
                .build();

        when(linkService.shorten(any(ShortenRequest.class), eq("1.2.3.4"))).thenReturn(mockResult);

        linkController.shorten(shortenReq, request);

        verify(linkService).shorten(any(ShortenRequest.class), eq("1.2.3.4"));
    }
}
