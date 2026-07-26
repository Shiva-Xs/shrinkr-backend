package com.shivaxdev.shrinkr.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ShortenResult {

    private String originalUrl;
    private String slug;
    private String shortUrl;

    private String manageUrl;
}
