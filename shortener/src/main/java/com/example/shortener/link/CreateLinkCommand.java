package com.example.shortener.link;

import org.jspecify.annotations.Nullable;

public record CreateLinkCommand(String targetUrl, @Nullable String alias, @Nullable String idempotencyKey) {
}
