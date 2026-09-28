package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.link.LinkRepository.IdempotentLink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * Covers the idempotency insert race, which cannot be provoked deterministically through the API:
 * a concurrent request with the same key commits between this request's lookup and its insert.
 */
class LinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");

    private final LinkRepository repository = mock(LinkRepository.class);
    private final ShortenerProperties properties = new ShortenerProperties(
            URI.create("https://sho.rt"), 7, 5, new ShortenerProperties.Stats(7, 90));
    private final LinkService service = new LinkService(
            repository,
            new ShortCodeGenerator(new SplittableRandom(1), 7),
            new TargetUrlValidator(properties),
            new AliasPolicy(),
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new SimpleMeterRegistry());

    @Test
    void returnsTheWinnersLinkWhenAConcurrentRequestWithTheSameKeyInsertedFirst() {
        CreateLinkCommand command = new CreateLinkCommand("https://example.org/a", null, "order-42");
        ShortLink winner = new ShortLink(7, "Wx12345", "https://example.org/a", LinkStatus.ACTIVE, NOW, null);
        when(repository.findByIdempotencyKey("order-42")).thenReturn(
                Optional.empty(),
                Optional.of(new IdempotentLink(winner, LinkService.requestHash(command))));
        when(repository.insert(anyString(), anyString(), any(), eq("order-42"), anyString()))
                .thenThrow(new DuplicateKeyException("uk_short_link_idempotency_key"));

        CreateLinkResult result = service.create(command);

        assertThat(result.replayed()).isTrue();
        assertThat(result.link()).isEqualTo(winner);
        verify(repository, times(1)).insert(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void rejectsTheLoserWhenTheConcurrentRequestWithTheSameKeyHadADifferentPayload() {
        CreateLinkCommand command = new CreateLinkCommand("https://example.org/a", "launch", "order-42");
        CreateLinkCommand other = new CreateLinkCommand("https://example.org/b", "launch", "order-42");
        ShortLink winner = new ShortLink(7, "launch", "https://example.org/b", LinkStatus.ACTIVE, NOW, null);
        when(repository.findByIdempotencyKey("order-42")).thenReturn(
                Optional.empty(),
                Optional.of(new IdempotentLink(winner, LinkService.requestHash(other))));
        when(repository.insert(eq("launch"), anyString(), any(), eq("order-42"), anyString()))
                .thenThrow(new DuplicateKeyException("uk_short_link_code"));

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void requestHashDistinguishesAliasFromNoAlias() {
        String withoutAlias = LinkService.requestHash(new CreateLinkCommand("https://example.org", null, "k"));
        String withAlias = LinkService.requestHash(new CreateLinkCommand("https://example.org", "abcd", "k"));

        assertThat(withoutAlias).hasSize(64).isNotEqualTo(withAlias);
    }
}
