package com.example.shortener.link.api;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.link.LinkStatus;
import com.example.shortener.link.ShortLink;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record LinkResponse(
        String code,
        String shortUrl,
        String targetUrl,
        LinkStatus status,
        Instant createdAt,
        @Nullable Instant disabledAt) {

    static LinkResponse from(ShortLink link, ShortenerProperties properties) {
        return new LinkResponse(
                link.code(),
                properties.shortUrl(link.code()),
                link.targetUrl(),
                link.status(),
                link.createdAt(),
                link.disabledAt());
    }
}
