package com.example.shortener.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TakedownPolicyTest {

    private static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");

    private final TakedownPolicy policy = new TakedownPolicy();

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2})
    @DisplayName("AC-3: fewer than three distinct reporters never take a link down")
    void fewerThanThreeReportersAreNotEnough(long reporters) {
        assertThat(policy.requiresTakedown(reporters)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {3, 4, 100})
    @DisplayName("AC-3: three or more distinct reporters take a link down")
    void threeReportersAreEnough(long reporters) {
        assertThat(policy.requiresTakedown(reporters)).isTrue();
    }

    @Test
    @DisplayName("AC-5: only reports from the last 24 hours count")
    void windowCoversTheLast24Hours() {
        assertThat(policy.windowStart(NOW)).isEqualTo(Instant.parse("2026-03-14T10:00:00Z"));
    }
}
