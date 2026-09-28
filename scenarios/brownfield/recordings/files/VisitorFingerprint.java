package com.example.shortener.analytics;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pseudonymous visitor identifier: HMAC-SHA256 over the UTC day, client address and user agent.
 * <p>
 * The key stops anyone holding the database from reversing fingerprints by hashing the (small) IPv4
 * space, and including the day makes the same person unlinkable across days. The raw inputs are never
 * stored or logged.
 */
@Component
public class VisitorFingerprint {

    private static final Logger log = LoggerFactory.getLogger(VisitorFingerprint.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public VisitorFingerprint(@Value("${shortener.analytics.visitor-hash-secret:}") String configuredKey) {
        byte[] keyBytes;
        if (configuredKey.isBlank()) {
            // Counts then restart with the process: fine for development, not for production.
            log.warn("No visitor fingerprint key configured (shortener.analytics.visitor-hash-secret); using a random per-process key");
            keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
        } else {
            keyBytes = configuredKey.getBytes(StandardCharsets.UTF_8);
        }
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * Hex-encoded fingerprint, or null when the client address is unknown (such clicks are still
     * counted as clicks, just not as visitors).
     */
    public @Nullable String of(@Nullable String clientAddress, @Nullable String userAgent, LocalDate day) {
        if (clientAddress == null || clientAddress.isBlank()) {
            return null;
        }
        String input = day + "\n" + clientAddress.strip() + "\n" + (userAgent == null ? "" : userAgent.strip());
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }
}
