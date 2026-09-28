package com.example.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ShortenerPropertiesTest {

    private static ShortenerProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("shortener", ShortenerProperties.class);
    }

    @Test
    @DisplayName("AC-1: without a configured value every link lives 365 days")
    void defaultLifetimeIs365Days() {
        assertThat(bind(Map.of()).linkLifetime()).isEqualTo(Duration.ofDays(365));
    }

    @Test
    @DisplayName("AC-1: shortener.link-lifetime accepts Spring durations such as 30d or PT12H")
    void lifetimeIsConfigurable() {
        assertThat(bind(Map.of("shortener.link-lifetime", "30d")).linkLifetime()).isEqualTo(Duration.ofDays(30));
        assertThat(bind(Map.of("shortener.link-lifetime", "PT12H")).linkLifetime()).isEqualTo(Duration.ofHours(12));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1d"})
    @DisplayName("AC-1: a lifetime of zero or less prevents the application from starting")
    void nonPositiveLifetimeIsRejected(String lifetime) {
        assertThatThrownBy(() -> bind(Map.of("shortener.link-lifetime", lifetime)))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("shortener.link-lifetime must be positive");
    }
}
