package com.example.shortener.analytics.api;

import com.example.shortener.analytics.AnalyticsService;
import com.example.shortener.config.ShortenerProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatsController {

    private final AnalyticsService analyticsService;
    private final ShortenerProperties properties;

    public StatsController(AnalyticsService analyticsService, ShortenerProperties properties) {
        this.analyticsService = analyticsService;
        this.properties = properties;
    }

    @GetMapping("/api/v1/links/{code}/stats")
    public LinkStatsResponse stats(@PathVariable String code, @RequestParam(required = false) @Nullable Integer days) {
        int window = days != null ? days : properties.stats().defaultDays();
        return LinkStatsResponse.from(analyticsService.stats(code, window));
    }
}
