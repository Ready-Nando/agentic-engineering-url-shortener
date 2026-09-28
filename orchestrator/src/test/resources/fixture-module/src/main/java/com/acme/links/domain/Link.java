package com.acme.links.domain;

import java.time.Instant;

public record Link(long id, String code, String targetUrl, Instant createdAt) {
}
