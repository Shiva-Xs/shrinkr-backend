package com.shivaxdev.shrinkr.dto;

import com.shivaxdev.shrinkr.model.ShortLink;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class LinkInfoResponse {

    private String slug;
    private String originalUrl;
    private LocalDateTime createdAt;
    private int clickCount;
    private LocalDateTime expiresAt;
    private Integer maxClicks;
    private Integer remainingClicks;   
    private boolean passwordProtected; 
    private String scanStatus;

    public static LinkInfoResponse fromEntity(ShortLink link, int bufferedClicks) {
        int liveClickCount = link.getClickCount() + bufferedClicks;

        Integer remaining = null;
        if (link.getMaxClicks() != null) {

            remaining = Math.max(0, link.getMaxClicks() - liveClickCount);
        }

        return LinkInfoResponse.builder()
                .slug(link.getSlug())
                .originalUrl(link.getOriginalUrl())
                .createdAt(link.getCreatedAt())
                .clickCount(liveClickCount)
                .expiresAt(link.getExpiresAt())
                .maxClicks(link.getMaxClicks())
                .remainingClicks(remaining)
                .passwordProtected(link.getPasswordHash() != null)
                .scanStatus(link.getScanStatus())
                .build();
    }
}
