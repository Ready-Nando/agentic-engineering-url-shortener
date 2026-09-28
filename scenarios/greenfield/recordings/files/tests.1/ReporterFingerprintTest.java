package com.example.shortener.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReporterFingerprintTest {

    private static final long LINK = 7;
    private static final String ADDRESS = "203.0.113.7";

    private final ReporterFingerprint fingerprint = new ReporterFingerprint("unit-test-key-0123456789");

    @Test
    @DisplayName("AC-4: the same client reporting the same link always gets the same fingerprint")
    void isStablePerLinkAndClient() {
        assertThat(fingerprint.of(LINK, ADDRESS)).isEqualTo(fingerprint.of(LINK, ADDRESS));
        assertThat(fingerprint.of(LINK, " " + ADDRESS + " ")).isEqualTo(fingerprint.of(LINK, ADDRESS));
    }

    @Test
    @DisplayName("AC-4: another client address is another reporter")
    void differsPerClient() {
        assertThat(fingerprint.of(LINK, "198.51.100.23")).isNotEqualTo(fingerprint.of(LINK, ADDRESS));
    }

    @Test
    @DisplayName("AC-4: addresses in one IPv6 /64 network are one reporter, other networks are not")
    void identifiesIpv6ClientsByTheirNetwork() {
        String reporter = fingerprint.of(LINK, "2001:db8:1:2::10");

        assertThat(fingerprint.of(LINK, "2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEqualTo(reporter);
        assertThat(fingerprint.of(LINK, "2001:db8:1:3::10")).isNotEqualTo(reporter);
    }

    @ParameterizedTest
    @CsvSource({
            "203.0.113.7, 203.0.113.7",
            "2001:db8:1:2:3:4:5:6, 2001:db8:1:2:0:0:0:0/64",
            "2001:DB8:1:2::, 2001:db8:1:2:0:0:0:0/64",
            "0:0:0:0:0:0:0:1, 0:0:0:0:0:0:0:0/64",
            "::ffff:203.0.113.7, 203.0.113.7",
            "[2001:db8:1:2::10], 2001:db8:1:2:0:0:0:0/64",
            "[::ffff:203.0.113.7], 203.0.113.7",
            "unknown, unknown",
            "1:2:3:4:5:6:7:8:9, 1:2:3:4:5:6:7:8:9",
            "localhost:8080, localhost:8080"
    })
    void reducesAddressesToTheReportersNetwork(String address, String network) {
        assertThat(ReporterFingerprint.network(address)).isEqualTo(network);
    }

    @Test
    @DisplayName("AC-6: the fingerprint differs per link, so a reporter cannot be followed across links")
    void differsPerLink() {
        assertThat(fingerprint.of(LINK + 1, ADDRESS)).isNotEqualTo(fingerprint.of(LINK, ADDRESS));
    }

    @Test
    @DisplayName("AC-6: the fingerprint is a 64-character hex digest that reveals nothing of the address")
    void revealsNoAddress() {
        assertThat(fingerprint.of(LINK, ADDRESS)).matches("[0-9a-f]{64}").doesNotContain(ADDRESS);
    }

    @Test
    @DisplayName("AC-6: fingerprints depend on the secret key, so they cannot be recomputed without it")
    void dependsOnTheKey() {
        ReporterFingerprint otherKey = new ReporterFingerprint("another-unit-test-key-987654");

        assertThat(otherKey.of(LINK, ADDRESS)).isNotEqualTo(fingerprint.of(LINK, ADDRESS));
    }

    @Test
    void worksWithoutAConfiguredKey() {
        ReporterFingerprint randomKey = new ReporterFingerprint("");

        assertThat(randomKey.of(LINK, ADDRESS)).matches("[0-9a-f]{64}");
    }
}
