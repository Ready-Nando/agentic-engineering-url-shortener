package com.example.shortener.abuse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import com.example.shortener.TestClockConfiguration;
import com.example.shortener.abuse.AbuseReason;
import com.example.shortener.abuse.AbuseReport;
import com.example.shortener.abuse.AbuseReportRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * "Now" is fixed at 2026-03-15T10:00:00Z by the shared test clock. Distinct reporters are simulated with
 * distinct client addresses from the documentation ranges.
 */
class AbuseReportApiIntegrationTest extends AbstractIntegrationTest {

    private static final String PROBLEMS = "https://example.com/problems/";
    private static final Instant NOW = TestClockConfiguration.NOW;

    @Autowired
    AbuseReportRepository reports;

    @Autowired
    MeterRegistry meterRegistry;

    @ParameterizedTest
    @EnumSource(AbuseReason.class)
    @DisplayName("AC-1: every reason is accepted with 202 and no body, and the report is recorded")
    void acceptsAndRecordsEveryReason(AbuseReason reason) throws Exception {
        String code = createLink("https://example.org/");

        report(code, reason.name(), "203.0.113.10")
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(storedReports()).singleElement().satisfies(row -> {
            assertThat(row.get("link_id")).isEqualTo(linkId(code));
            assertThat(row.get("reason")).isEqualTo(reason.name());
            assertThat(((OffsetDateTime) row.get("reported_at")).toInstant()).isEqualTo(NOW);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCAM", "phishing", ""})
    @DisplayName("AC-2: an unknown reason is a validation error and records nothing")
    void rejectsUnknownReasons(String reason) throws Exception {
        String code = createLink("https://example.org/");

        report(code, reason, "203.0.113.10")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].message").value("must be one of SPAM, PHISHING, MALWARE, OTHER"));

        assertThat(storedReports()).isEmpty();
    }

    @Test
    @DisplayName("AC-2: a missing reason is a validation error and records nothing")
    void rejectsMissingReason() throws Exception {
        String code = createLink("https://example.org/");

        postReport(code, "{}", "203.0.113.10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be null"));

        assertThat(storedReports()).isEmpty();
    }

    @Test
    @DisplayName("AC-2: a body that is not JSON is a malformed request")
    void rejectsMalformedBody() throws Exception {
        String code = createLink("https://example.org/");

        postReport(code, "{\"reason\": ", "203.0.113.10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "malformed-request"));

        assertThat(storedReports()).isEmpty();
    }

    @Test
    @DisplayName("AC-2: reporting an unknown code answers 404")
    void answersNotFoundForUnknownCode() throws Exception {
        report("missing1", "SPAM", "203.0.113.10")
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "link-not-found"));
    }

    @Test
    @DisplayName("AC-2: reporting a disabled link answers 410 and records nothing")
    void answersGoneForDisabledLink() throws Exception {
        String code = createLink("https://example.org/");
        mockMvc.perform(delete("/api/v1/links/{code}", code)).andExpect(status().isNoContent());

        report(code, "PHISHING", "203.0.113.10")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "link-gone"));

        assertThat(storedReports()).isEmpty();
    }

    @Test
    @DisplayName("AC-3: reports from three distinct reporters within 24 hours disable the link")
    void threeDistinctReportersTakeTheLinkDown() throws Exception {
        String code = createLink("https://example.org/");
        double takedownsBefore = takedowns();

        report(code, "SPAM", "203.0.113.1").andExpect(status().isAccepted());
        report(code, "PHISHING", "198.51.100.2").andExpect(status().isAccepted());
        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());

        report(code, "MALWARE", "192.0.2.3").andExpect(status().isAccepted());

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "link-gone"));
        mockMvc.perform(get("/api/v1/links/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.disabledAt").value("2026-03-15T10:00:00Z"));
        assertThat(takedowns()).isEqualTo(takedownsBefore + 1);
    }

    @Test
    void reportsDoNotCountAsRedirects() throws Exception {
        String code = createLink("https://example.org/");
        double redirectsBefore = redirects("found") + redirects("not_found") + redirects("gone");

        report(code, "SPAM", "203.0.113.1").andExpect(status().isAccepted());
        report("missing1", "SPAM", "203.0.113.1").andExpect(status().isNotFound());

        assertThat(redirects("found") + redirects("not_found") + redirects("gone")).isEqualTo(redirectsBefore);
    }

    @Test
    @DisplayName("AC-4: repeated reports by the same reporter count once")
    void repeatedReportsCountOnce() throws Exception {
        String code = createLink("https://example.org/");

        for (String reason : List.of("SPAM", "PHISHING", "MALWARE", "OTHER", "SPAM")) {
            report(code, reason, "203.0.113.7").andExpect(status().isAccepted());
        }
        report(code, "SPAM", "198.51.100.8").andExpect(status().isAccepted());

        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
        assertThat(storedReports()).hasSize(2);
    }

    @Test
    @DisplayName("AC-4: addresses from one IPv6 /64 network count as one reporter")
    void oneIpv6NetworkIsOneReporter() throws Exception {
        String code = createLink("https://example.org/");

        report(code, "SPAM", "2001:db8:1:2::10").andExpect(status().isAccepted());
        report(code, "SPAM", "2001:db8:1:2::20").andExpect(status().isAccepted());
        report(code, "SPAM", "2001:db8:1:2:ffff:ffff:ffff:ffff").andExpect(status().isAccepted());
        // The bracketed form reported behind a proxy with server.forward-headers-strategy=framework.
        report(code, "SPAM", "[2001:db8:1:2::30]").andExpect(status().isAccepted());

        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
        assertThat(storedReports()).hasSize(1);
    }

    @Test
    @DisplayName("AC-5: reports older than 24 hours do not count towards a takedown")
    void reportsOlderThanTheWindowDoNotCount() throws Exception {
        String code = createLink("https://example.org/");
        long id = linkId(code);
        reports.save(new AbuseReport(id, reporter('a'), AbuseReason.SPAM, NOW.minus(Duration.ofHours(24)).minusSeconds(1)));
        reports.save(new AbuseReport(id, reporter('b'), AbuseReason.PHISHING, NOW.minus(Duration.ofDays(3))));

        report(code, "SPAM", "203.0.113.1").andExpect(status().isAccepted());

        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
    }

    @Test
    @DisplayName("AC-5: a report exactly 24 hours old still counts")
    void windowIncludesItsStart() throws Exception {
        String code = createLink("https://example.org/");
        long id = linkId(code);
        reports.save(new AbuseReport(id, reporter('a'), AbuseReason.SPAM, NOW.minus(Duration.ofHours(24))));
        reports.save(new AbuseReport(id, reporter('b'), AbuseReason.SPAM, NOW.minus(Duration.ofHours(1))));

        report(code, "SPAM", "203.0.113.1").andExpect(status().isAccepted());

        mockMvc.perform(get("/{code}", code)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("AC-6: only a 64-character keyed hash identifies the reporter, and it differs per link")
    void storesNoClientAddress() throws Exception {
        String first = createLink("https://example.org/one");
        String second = createLink("https://example.org/two");

        report(first, "SPAM", "203.0.113.99").andExpect(status().isAccepted());
        report(second, "SPAM", "203.0.113.99").andExpect(status().isAccepted());

        List<Map<String, Object>> rows = storedReports();
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row).containsOnlyKeys("id", "link_id", "reporter_hash", "reason", "reported_at");
            assertThat(row.get("reporter_hash").toString()).matches("[0-9a-f]{64}");
            assertThat(row.values()).noneMatch(value -> value != null && value.toString().contains("203.0.113"));
        });
        assertThat(rows.get(0).get("reporter_hash")).isNotEqualTo(rows.get(1).get("reporter_hash"));
    }

    private ResultActions report(String code, String reason, String clientAddress) throws Exception {
        return postReport(code, """
                {"reason": "%s"}
                """.formatted(reason), clientAddress);
    }

    private ResultActions postReport(String code, String json, String clientAddress) throws Exception {
        return mockMvc.perform(post("/api/v1/links/{code}/reports", code)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(request -> {
                    request.setRemoteAddr(clientAddress);
                    return request;
                }));
    }

    private List<Map<String, Object>> storedReports() {
        return jdbc.sql("SELECT * FROM abuse_report ORDER BY id").query().listOfRows();
    }

    private double takedowns() {
        return meterRegistry.counter("shortener.abuse.takedowns").count();
    }

    private double redirects(String outcome) {
        return meterRegistry.counter("shortener.redirects", "outcome", outcome).count();
    }

    private static String reporter(char filler) {
        return String.valueOf(filler).repeat(64);
    }
}
