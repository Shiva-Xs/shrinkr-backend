package com.shivaxdev.shrinkr.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class EditRequest {

    @Future(message = "expiresAt must be a future date and time")
    private LocalDateTime expiresAt;

    @Min(value = 1, message = "maxClicks must be at least 1")
    private Integer maxClicks;

    private String password;

    private boolean clearExpiry;

    private boolean clearMaxClicks;

    private boolean clearPassword;
}
