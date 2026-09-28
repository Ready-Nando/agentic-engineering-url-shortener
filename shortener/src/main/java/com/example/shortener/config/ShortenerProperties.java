package com.example.shortener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("shortener")
public record ShortenerProperties(
        @DefaultValue("http://localhost:8080") @NotNull URI baseUrl,
        // Bounded by the redirect route pattern, which only matches codes of 4..32 characters.
        @DefaultValue("7") @Min(4) @Max(32) int codeLength,
        @DefaultValue("5") @Min(1) int maxCodeGenerationAttempts,
        @DefaultValue @Valid Stats stats) {

    public ShortenerProperties {
        if (baseUrl != null && baseUrl.getHost() == null) {
            throw new IllegalArgumentException("shortener.base-url must be an absolute URL with a host: " + baseUrl);
        }
    }

    public String shortUrl(String code) {
        String base = baseUrl.toString();
        return (base.endsWith("/") ? base : base + "/") + code;
    }

    public record Stats(
            @DefaultValue("7") @Min(1) int defaultDays,
            @DefaultValue("90") @Min(1) int maxDays) {

        public Stats {
            if (defaultDays > maxDays) {
                throw new IllegalArgumentException(
                        "shortener.stats.default-days (" + defaultDays + ") exceeds max-days (" + maxDays + ")");
            }
        }
    }
}
