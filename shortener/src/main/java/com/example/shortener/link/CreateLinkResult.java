package com.example.shortener.link;

/**
 * @param replayed true when an earlier request with the same idempotency key already created {@code link}
 */
public record CreateLinkResult(ShortLink link, boolean replayed) {
}
