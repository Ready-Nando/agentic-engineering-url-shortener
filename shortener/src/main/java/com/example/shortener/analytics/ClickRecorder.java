package com.example.shortener.analytics;

import com.example.shortener.link.ShortLink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Records clicks for analytics. A redirect is the product; analytics are best effort, so a failing
 * click store must never turn a working redirect into an error.
 */
@Component
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);
    private static final int MAX_HOST_LENGTH = 255;

    private final ClickEventRepository clickEventRepository;
    private final Clock clock;
    private final Counter dropped;

    public ClickRecorder(ClickEventRepository clickEventRepository, Clock clock, MeterRegistry meterRegistry) {
        this.clickEventRepository = clickEventRepository;
        this.clock = clock;
        this.dropped = Counter.builder("shortener.analytics.dropped")
                .description("Click events that could not be stored")
                .register(meterRegistry);
    }

    public void record(ShortLink link, @Nullable String refererHeader) {
        ClickEvent event = new ClickEvent(link.id(), clock.instant(), referrerHost(refererHeader));
        try {
            clickEventRepository.insert(event);
        } catch (DataAccessException e) {
            dropped.increment();
            // Exception messages can echo SQL parameter values, so only the exception type is logged.
            log.warn("Dropped click event for link id {} ({})", link.id(),
                    NestedExceptionUtils.getMostSpecificCause(e).getClass().getName());
        }
    }

    /**
     * Reduces a Referer header to its host. Paths and query strings often carry personal data
     * (search terms, session ids, e-mail addresses), and the host is all the statistics need.
     */
    static @Nullable String referrerHost(@Nullable String refererHeader) {
        if (refererHeader == null || refererHeader.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(refererHeader.strip()).getHost();
            if (host == null || host.length() > MAX_HOST_LENGTH) {
                return null;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
