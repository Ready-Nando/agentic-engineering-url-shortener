package com.example.shortener.analytics.api;

import com.example.shortener.analytics.LinkStats;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record LinkStatsResponse(
        String code,
        long totalClicks,
        @Nullable Instant lastClickAt,
        Window window,
        long clicksInWindow,
        List<Day> daily,
        List<Referrer> topReferrers) {

    /**
     * Inclusive UTC date range the windowed figures refer to.
     */
    public record Window(LocalDate from, LocalDate to, int days) {
    }

    public record Day(LocalDate date, long clicks) {
    }

    public record Referrer(String host, long clicks) {
    }

    static LinkStatsResponse from(LinkStats stats) {
        return new LinkStatsResponse(
                stats.code(),
                stats.totalClicks(),
                stats.lastClickAt(),
                new Window(stats.from(), stats.to(), stats.days()),
                stats.clicksInWindow(),
                stats.daily().stream().map(day -> new Day(day.date(), day.clicks())).toList(),
                stats.topReferrers().stream().map(ref -> new Referrer(ref.host(), ref.clicks())).toList());
    }
}
