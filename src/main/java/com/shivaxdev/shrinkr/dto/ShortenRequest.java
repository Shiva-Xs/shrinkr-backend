package com.shivaxdev.shrinkr.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class ShortenRequest {

    @NotBlank(message = "URL must not be blank")
    @Size(max = 2048, message = "URL must be 2048 characters or fewer")
    private String url;

    @Future(message = "expiresAt must be a future date and time")
    private LocalDateTime expiresAt;

    @Min(value = 1, message = "maxClicks must be at least 1")
    private Integer maxClicks;

    @Size(max = 100, message = "Password must be 100 characters or fewer")
    private String password;
}
