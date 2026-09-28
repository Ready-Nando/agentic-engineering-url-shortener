package com.example.shortener.abuse;

import java.time.Instant;

/**
 * One reporter's report of one link. Holds no personal data: the reporter is only known by a keyed hash.
 *
 * @param reporterHash per-link reporter pseudonym, see {@link ReporterFingerprint}
 */
public record AbuseReport(long linkId, String reporterHash, AbuseReason reason, Instant reportedAt) {
}
