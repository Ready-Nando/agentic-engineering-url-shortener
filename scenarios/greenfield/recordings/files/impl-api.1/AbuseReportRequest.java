package com.example.shortener.abuse.api;

import com.example.shortener.abuse.AbuseReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * @param reason one of the {@link AbuseReason} names. Bound as a string so that an unknown value is reported as
 *               a field error ({@code validation-failed}) rather than as an unreadable body ({@code malformed-request}).
 */
public record AbuseReportRequest(
        @NotNull
        @Pattern(regexp = "SPAM|PHISHING|MALWARE|OTHER", message = "must be one of SPAM, PHISHING, MALWARE, OTHER")
        String reason) {

    AbuseReason toAbuseReason() {
        return AbuseReason.valueOf(reason);
    }
}
