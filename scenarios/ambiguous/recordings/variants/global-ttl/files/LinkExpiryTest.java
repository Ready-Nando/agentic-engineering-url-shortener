package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.shortener.config.ShortenerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The expiry boundary and the metric outcome, with a lifetime shorter than the configured default. */
class LinkExpiryTest {

    private static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");
    private static final Duration LIFETIME = Duration.ofDays(30);

    private final LinkRepository repository = mock(LinkRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ShortenerProperties properties = new ShortenerProperties(
            URI.create("https://sho.rt"), 7, 5, new ShortenerProperties.Stats(7, 90), LIFETIME);
    private final LinkService service = new LinkService(
            repository,
            new ShortCodeGenerator(new SplittableRandom(1), 7),
            new TargetUrlValidator(properties),
            new AliasPolicy(),
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC),
            meterRegistry);

    private ShortLink stored(String code, Instant createdAt, LinkStatus status) {
        ShortLink link = new ShortLink(1, code, "https://example.org/" + code, status, createdAt,
                status == LinkStatus.DISABLED ? createdAt : null);
        when(repository.findByCode(code)).thenReturn(Optional.of(link));
        return link;
    }

    private double redirects(String outcome) {
        return meterRegistry.counter("shortener.redirects", "outcome", outcome).count();
    }

    @Test
    @DisplayName("AC-2: a link redirects until one lifetime after its creation and is not found from that instant on")
    void expiresExactlyOneLifetimeAfterCreation() {
        ShortLink young = stored("young1", NOW.minus(LIFETIME).plusNanos(1_000), LinkStatus.ACTIVE);
        stored("expired1", NOW.minus(LIFETIME), LinkStatus.ACTIVE);

        assertThat(service.resolve("young1")).isEqualTo(young);
        assertThatThrownBy(() -> service.resolve("expired1"))
                .isInstanceOf(LinkNotFoundException.class)
                .hasMessage(new LinkNotFoundException("expired1").getMessage());
        assertThat(redirects("found")).isEqualTo(1);
        assertThat(redirects("expired")).isEqualTo(1);
        assertThat(redirects("not_found")).isZero();
    }

    @Test
    @DisplayName("AC-2: an expired link that was also disabled answers like an unknown code instead of 410")
    void expiryHidesThatADisabledLinkExisted() {
        stored("gone1234", NOW.minus(LIFETIME).minusSeconds(1), LinkStatus.DISABLED);
        stored("disabled", NOW.minusSeconds(1), LinkStatus.DISABLED);

        assertThatThrownBy(() -> service.resolve("gone1234")).isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.resolve("disabled")).isInstanceOf(LinkGoneException.class);
        assertThat(redirects("expired")).isEqualTo(1);
        assertThat(redirects("gone")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-3: reading an expired link is unaffected; only following it changes")
    void readingAnExpiredLinkStillWorks() {
        ShortLink expired = stored("expired2", NOW.minus(LIFETIME).minusSeconds(1), LinkStatus.ACTIVE);

        assertThat(service.get("expired2")).isEqualTo(expired);
        assertThat(redirects("expired")).isZero();
    }
}
