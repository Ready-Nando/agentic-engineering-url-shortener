package com.example.shortener.link;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record ShortLink(
        long id,
        String code,
        String targetUrl,
        LinkStatus status,
        Instant createdAt,
        @Nullable Instant disabledAt) {

    public boolean isActive() {
        return status == LinkStatus.ACTIVE;
    }
}
