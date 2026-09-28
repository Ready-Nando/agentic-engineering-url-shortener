package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.AbstractIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

class LinkRepositoryIntegrationTest extends AbstractIntegrationTest {

    private static final Instant CREATED = Instant.parse("2026-03-15T10:00:00Z");

    @Autowired
    LinkRepository repository;

    @Test
    void returnsTheLinkExactlyAsALaterReadSeesIt() {
        // System clocks on Linux have nanosecond resolution; the column keeps microseconds.
        ShortLink inserted = repository.insert("nanos01", "https://example.org/", Instant.parse("2026-03-15T10:00:00.123456789Z"), null, null);

        assertThat(repository.findByCode("nanos01")).contains(inserted);
        assertThat(inserted.createdAt()).isEqualTo(Instant.parse("2026-03-15T10:00:00.123456Z"));
    }

    @Test
    void letsUniqueViolationsSurfaceForCodeAndIdempotencyKey() {
        repository.insert("taken01", "https://example.org/a", CREATED, "key-1", "a".repeat(64));

        assertThatThrownBy(() -> repository.insert("taken01", "https://example.org/b", CREATED, null, null))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> repository.insert("other01", "https://example.org/b", CREATED, "key-1", "b".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findsLinksByIdempotencyKeyTogetherWithTheRequestHash() {
        ShortLink link = repository.insert("keyed01", "https://example.org/", CREATED, "key-2", "c".repeat(64));

        assertThat(repository.findByIdempotencyKey("key-2"))
                .contains(new LinkRepository.IdempotentLink(link, "c".repeat(64)));
        assertThat(repository.findByIdempotencyKey("key-3")).isEmpty();
    }

    @Test
    void disablesOnlyActiveLinksAndKeepsTheFirstDisabledTime() {
        repository.insert("active1", "https://example.org/", CREATED, null, null);
        Instant first = CREATED.plusSeconds(60);

        assertThat(repository.disable("active1", first)).isTrue();
        assertThat(repository.disable("active1", first.plusSeconds(60))).isFalse();
        assertThat(repository.disable("unknown", first)).isFalse();

        assertThat(repository.findByCode("active1")).hasValueSatisfying(link -> {
            assertThat(link.status()).isEqualTo(LinkStatus.DISABLED);
            assertThat(link.disabledAt()).isEqualTo(first);
        });
    }
}
