package com.example.shortener.abuse.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import com.example.shortener.TestClockConfiguration;
import com.example.shortener.abuse.AbuseReason;
import com.example.shortener.abuse.AbuseReport;
import com.example.shortener.abuse.AbuseReportRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * A stricter rule than the default of 3 reporters within 24 hours, configured the way Trust and Safety would for
 * one environment. Each test would come out differently under the defaults. Runs in its own application
 * context because of the properties.
 */
@TestPropertySource(properties = {
        "shortener.abuse.takedown.threshold=2",
        "shortener.abuse.takedown.window=1h"})
class ConfiguredTakedownIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AbuseReportRepository reports;

    @Test
    @DisplayName("AC-3: with a configured threshold of 2, two distinct reporters take the link down")
    void appliesTheConfiguredThreshold() throws Exception {
        String code = createLink("https://example.org/");

        report(code, "203.0.113.1");
        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());

        report(code, "198.51.100.2");
        mockMvc.perform(get("/{code}", code)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("AC-5: with a configured window of 1 hour, older reports do not count")
    void appliesTheConfiguredWindow() throws Exception {
        String code = createLink("https://example.org/");
        Instant earlier = TestClockConfiguration.NOW.minus(Duration.ofMinutes(61));
        reports.save(new AbuseReport(linkId(code), "a".repeat(64), AbuseReason.SPAM, earlier));
        reports.save(new AbuseReport(linkId(code), "b".repeat(64), AbuseReason.SPAM, earlier));

        report(code, "203.0.113.1");

        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
    }

    private void report(String code, String clientAddress) throws Exception {
        mockMvc.perform(post("/api/v1/links/{code}/reports", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "PHISHING"}
                                """)
                        .with(request -> {
                            request.setRemoteAddr(clientAddress);
                            return request;
                        }))
                .andExpect(status().isAccepted());
    }
}
