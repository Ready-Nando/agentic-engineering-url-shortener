package com.example.shortener.link;

import com.example.shortener.config.ShortenerProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class TargetUrlValidator {

    static final int MAX_LENGTH = 2048;

    private final String ownHost;

    public TargetUrlValidator(ShortenerProperties properties) {
        this.ownHost = normalizeHost(properties.baseUrl().getHost());
    }

    public void validate(@Nullable String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidTargetUrlException("URL must not be blank");
        }
        if (url.length() > MAX_LENGTH) {
            throw new InvalidTargetUrlException("URL must not be longer than " + MAX_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidTargetUrlException("URL is not well-formed: " + e.getReason());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InvalidTargetUrlException("URL must be absolute and use http or https");
        }
        // Userinfo is rejected outright: "https://trusted.example@evil.example" is a classic phishing disguise.
        if (uri.getRawUserInfo() != null) {
            throw new InvalidTargetUrlException("URL must not contain user information");
        }
        if (uri.getHost() == null) {
            throw new InvalidTargetUrlException("URL must contain a valid host");
        }
        if (normalizeHost(uri.getHost()).equals(ownHost)) {
            throw new InvalidTargetUrlException("URL must not point to this shortener");
        }
    }

    private static String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }
}
