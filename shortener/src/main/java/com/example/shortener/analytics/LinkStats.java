package com.example.shortener.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Click statistics for one link. {@code from} and {@code to} are inclusive UTC dates and
 * {@code daily} has exactly one entry per day between them.
 */
public record LinkStats(
        String code,
        long totalClicks,
        @Nullable Instant lastClickAt,
        LocalDate from,
        LocalDate to,
        int days,
        long clicksInWindow,
        List<DailyClicks> daily,
        List<ReferrerCount> topReferrers) {

    public record DailyClicks(LocalDate date, long clicks) {
    }

    public record ReferrerCount(String host, long clicks) {
    }
}
