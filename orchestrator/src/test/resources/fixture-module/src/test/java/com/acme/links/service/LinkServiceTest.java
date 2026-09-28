package com.acme.links.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.links.domain.Link;
import com.acme.links.persistence.ClickRepository;
import com.acme.links.persistence.LinkRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LinkServiceTest {

    private final LinkRepository links = mock(LinkRepository.class);
    private final ClickRepository clicks = mock(ClickRepository.class);
    private final LinkService service = new LinkService(links, clicks);

    @Test
    void resolveRecordsAClick() {
        when(links.findByCode("abcd")).thenReturn(Optional.of(new Link(7, "abcd", "https://example.org", Instant.EPOCH)));

        assertThat(service.resolve("abcd").targetUrl()).isEqualTo("https://example.org");
        verify(clicks).record(7);
    }
}
