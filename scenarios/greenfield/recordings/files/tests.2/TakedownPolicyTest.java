package com.example.shortener.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The defaults (3 reporters within 24 hours) as bound from configuration are covered end to end by
 * {@code AbuseReportApiIntegrationTest}; these tests pin the rule itself.
 */
class TakedownPolicyTest {

    private static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");

    private final TakedownPolicy policy = new TakedownPolicy(3, Duration.ofHours(24));

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

    @Test
    void appliesAConfiguredThresholdAndWindow() {
        TakedownPolicy strict = new TakedownPolicy(5, Duration.ofHours(6));

        assertThat(strict.requiresTakedown(4)).isFalse();
        assertThat(strict.requiresTakedown(5)).isTrue();
        assertThat(strict.windowStart(NOW)).isEqualTo(Instant.parse("2026-03-15T04:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void neverLetsASingleReporterTakeALinkDown(int threshold) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TakedownPolicy(threshold, Duration.ofHours(24)))
                .withMessageContaining("shortener.abuse.takedown.threshold must be at least 2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1H"})
    void rejectsAnEmptyOrNegativeWindow(String window) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TakedownPolicy(3, Duration.parse(window)))
                .withMessageContaining("shortener.abuse.takedown.window must be positive");
    }
}
