package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShortLinkTest {

    private static final Instant CREATED = Instant.parse("2026-03-15T10:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2026-04-01T00:00:00Z");

    @Test
    @DisplayName("AC-3: a link is expired from its expiry instant on, and not a nanosecond earlier")
    void isExpiredFromTheExpiryInstantOn() {
        ShortLink link = link(EXPIRY);

        assertThat(link.isExpiredAt(EXPIRY.minusNanos(1))).isFalse();
        assertThat(link.isExpiredAt(EXPIRY)).isTrue();
        assertThat(link.isExpiredAt(EXPIRY.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("AC-5: a link without an expiry never expires")
    void linkWithoutExpiryNeverExpires() {
        assertThat(link(null).isExpiredAt(Instant.MAX)).isFalse();
    }

    private static ShortLink link(@Nullable Instant expiresAt) {
        return new ShortLink(1, "abcd123", "https://example.org/", LinkStatus.ACTIVE, CREATED, expiresAt, null);
    }
}
