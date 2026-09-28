package com.acme.links.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(int codeLength, String baseUrl) {
}
