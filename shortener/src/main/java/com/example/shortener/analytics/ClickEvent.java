package com.example.shortener.analytics;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A single redirect. Intentionally holds no IP address, user agent or full referrer URL.
 *
 * @param referrerHost lower-cased host of the Referer header, or null when absent or unparseable
 */
public record ClickEvent(long linkId, Instant occurredAt, @Nullable String referrerHost) {
}
