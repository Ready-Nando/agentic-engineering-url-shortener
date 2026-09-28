package com.example.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VisitorFingerprintTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 15);
    private static final String ADDRESS = "203.0.113.7";
    private static final String AGENT = "Mozilla/5.0 (X11; Linux x86_64)";

    private final VisitorFingerprint fingerprint = new VisitorFingerprint("unit-test-key-0123456789");

    @Test
    @DisplayName("AC-2: the same client on the same UTC day always gets the same fingerprint")
    void isStableWithinADay() {
        assertThat(fingerprint.of(ADDRESS, AGENT, DAY)).isEqualTo(fingerprint.of(ADDRESS, AGENT, DAY));
    }

    @Test
    @DisplayName("AC-2: another day, address or user agent is another visitor")
    void differsAcrossDaysAddressesAndAgents() {
        String today = fingerprint.of(ADDRESS, AGENT, DAY);

        assertThat(fingerprint.of(ADDRESS, AGENT, DAY.plusDays(1))).isNotEqualTo(today);
        assertThat(fingerprint.of("198.51.100.23", AGENT, DAY)).isNotEqualTo(today);
        assertThat(fingerprint.of(ADDRESS, "curl/8.5.0", DAY)).isNotEqualTo(today);
    }

    @Test
    @DisplayName("AC-3: the fingerprint is a 64-character hex digest that reveals none of its inputs")
    void revealsNoRawInputs() {
        String value = fingerprint.of(ADDRESS, AGENT, DAY);

        assertThat(value).matches("[0-9a-f]{64}").doesNotContain(ADDRESS).doesNotContain("Mozilla");
    }

    @Test
    @DisplayName("AC-3: fingerprints depend on the secret key, so they cannot be recomputed without it")
    void dependsOnTheKey() {
        VisitorFingerprint otherKey = new VisitorFingerprint("another-unit-test-key-987654");

        assertThat(otherKey.of(ADDRESS, AGENT, DAY)).isNotEqualTo(fingerprint.of(ADDRESS, AGENT, DAY));
    }

    @Test
    void unknownClientAddressHasNoFingerprint() {
        assertThat(fingerprint.of(null, AGENT, DAY)).isNull();
        assertThat(fingerprint.of("  ", AGENT, DAY)).isNull();
    }

    @Test
    void worksWithoutAConfiguredKey() {
        VisitorFingerprint randomKey = new VisitorFingerprint("");

        assertThat(randomKey.of(ADDRESS, AGENT, DAY)).matches("[0-9a-f]{64}");
    }
}
