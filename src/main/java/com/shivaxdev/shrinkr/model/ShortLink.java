package com.shivaxdev.shrinkr.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "short_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String slug;

    @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    @Column(name = "scan_status", nullable = false, length = 10)
    private String scanStatus;  

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "max_clicks")
    private Integer maxClicks;

    @Column(name = "click_count", nullable = false)
    @Builder.Default
    private int clickCount = 0;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "delete_token", nullable = false)
    private String deleteToken;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
