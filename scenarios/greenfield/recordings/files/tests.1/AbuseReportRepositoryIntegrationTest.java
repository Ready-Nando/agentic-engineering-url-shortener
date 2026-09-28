package com.example.shortener.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.AbstractIntegrationTest;
import com.example.shortener.TestClockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class AbuseReportRepositoryIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = TestClockConfiguration.NOW;
    private static final Instant DAY_AGO = NOW.minus(Duration.ofHours(24));

    @Autowired
    AbuseReportRepository repository;

    @Test
    @DisplayName("AC-4: a repeated report refreshes the reporter's row instead of adding another")
    void repeatedReportRefreshesTheReportersRow() throws Exception {
        long link = linkId(createLink("https://example.org/"));
        repository.save(new AbuseReport(link, reporter('a'), AbuseReason.SPAM, NOW.minus(Duration.ofDays(3))));

        repository.save(new AbuseReport(link, reporter('a'), AbuseReason.MALWARE, NOW));

        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM abuse_report").query().listOfRows();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("reason")).isEqualTo("MALWARE");
            assertThat(((OffsetDateTime) row.get("reported_at")).toInstant()).isEqualTo(NOW);
        });
        assertThat(repository.countReportersSince(link, DAY_AGO)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-5: reporters count from the start of the window, inclusive, and per link")
    void countsReportersOfOneLinkFromTheWindowStart() throws Exception {
        long link = linkId(createLink("https://example.org/one"));
        long otherLink = linkId(createLink("https://example.org/two"));
        repository.save(new AbuseReport(link, reporter('a'), AbuseReason.SPAM, DAY_AGO));
        repository.save(new AbuseReport(link, reporter('b'), AbuseReason.SPAM, DAY_AGO.minus(1, ChronoUnit.MICROS)));
        repository.save(new AbuseReport(link, reporter('c'), AbuseReason.OTHER, NOW));
        repository.save(new AbuseReport(otherLink, reporter('d'), AbuseReason.PHISHING, NOW));

        assertThat(repository.countReportersSince(link, DAY_AGO)).isEqualTo(2);
        assertThat(repository.countReportersSince(otherLink, DAY_AGO)).isEqualTo(1);
    }

    @Test
    void sameReporterOnAnotherLinkIsAnotherReport() throws Exception {
        long link = linkId(createLink("https://example.org/one"));
        long otherLink = linkId(createLink("https://example.org/two"));

        repository.save(new AbuseReport(link, reporter('a'), AbuseReason.SPAM, NOW));
        repository.save(new AbuseReport(otherLink, reporter('a'), AbuseReason.SPAM, NOW));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM abuse_report").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void rejectsReportsOfUnknownLinks() {
        assertThatThrownBy(() -> repository.save(new AbuseReport(999_999, reporter('e'), AbuseReason.SPAM, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String reporter(char filler) {
        return String.valueOf(filler).repeat(64);
    }
}
