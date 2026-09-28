package com.example.shortener.analytics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import com.example.shortener.analytics.ClickEvent;
import com.example.shortener.analytics.ClickEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * "Now" is 2026-03-15T10:00:00Z (shared test clock), so the default window is 2026-03-09..2026-03-15.
 */
class UniqueVisitorsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ClickEventRepository clickEvents;

    @Test
    @DisplayName("AC-1: uniqueVisitors counts distinct visitor fingerprints inside the requested window only")
    void countsDistinctVisitorsInsideTheWindow() throws Exception {
        String code = createLink("https://example.org/");
        long id = linkId(code);
        click(id, "2026-03-01T12:00:00Z", fingerprint('0'));
        click(id, "2026-03-10T08:00:00Z", fingerprint('a'));
        click(id, "2026-03-10T09:30:00Z", fingerprint('a'));
        click(id, "2026-03-12T10:00:00Z", fingerprint('b'));

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clicksInWindow").value(3))
                .andExpect(jsonPath("$.uniqueVisitors").value(2));

        mockMvc.perform(get("/api/v1/links/{code}/stats", code).param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uniqueVisitors").value(3));
    }

    @Test
    @DisplayName("AC-2: repeated redirects by the same client on the same day count as one visitor")
    void sameClientOnTheSameDayCountsOnce() throws Exception {
        String code = createLink("https://example.org/");

        redirect(code, "203.0.113.7", "Mozilla/5.0");
        redirect(code, "203.0.113.7", "Mozilla/5.0");
        redirect(code, "203.0.113.7", "curl/8.5.0");
        redirect(code, "198.51.100.23", "Mozilla/5.0");

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(4))
                .andExpect(jsonPath("$.uniqueVisitors").value(3));
    }

    @Test
    @DisplayName("AC-3: a recorded click stores a keyed hash and no raw IP address or user agent")
    void storesNoRawPersonalData() throws Exception {
        String code = createLink("https://example.org/");

        redirect(code, "203.0.113.7", "Mozilla/5.0 (Agent Smith)");

        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM click_event").query().listOfRows();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.values()).noneMatch(value -> value != null
                    && (value.toString().contains("203.0.113.7") || value.toString().contains("Agent Smith")));
            assertThat(row.get("visitor_hash").toString()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    @DisplayName("AC-4: clicks recorded before fingerprinting count in totals but not as unique visitors")
    void legacyClicksCountInTotalsOnly() throws Exception {
        String code = createLink("https://example.org/");
        long id = linkId(code);
        clickEvents.insert(new ClickEvent(id, Instant.parse("2026-03-14T12:00:00Z"), "news.example"));
        click(id, "2026-03-14T13:00:00Z", fingerprint('c'));

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(2))
                .andExpect(jsonPath("$.clicksInWindow").value(2))
                .andExpect(jsonPath("$.uniqueVisitors").value(1));
    }

    @Test
    @DisplayName("AC-5: existing statistics fields keep their values when unique visitors are added")
    void existingFieldsAreUnchanged() throws Exception {
        String code = createLink("https://example.org/");
        long id = linkId(code);
        clickEvents.insert(new ClickEvent(id, Instant.parse("2026-03-09T00:00:00Z"), "b.example", fingerprint('d')));
        clickEvents.insert(new ClickEvent(id, Instant.parse("2026-03-15T09:00:00Z"), "b.example", fingerprint('d')));

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(2))
                .andExpect(jsonPath("$.lastClickAt").value("2026-03-15T09:00:00Z"))
                .andExpect(jsonPath("$.window.days").value(7))
                .andExpect(jsonPath("$.clicksInWindow").value(2))
                .andExpect(jsonPath("$.daily[*].clicks").value(contains(1, 0, 0, 0, 0, 0, 1)))
                .andExpect(jsonPath("$.topReferrers[0].host").value("b.example"))
                .andExpect(jsonPath("$.uniqueVisitors").value(1));
    }

    private void click(long linkId, String at, String visitorHash) {
        clickEvents.insert(new ClickEvent(linkId, Instant.parse(at), null, visitorHash));
    }

    private void redirect(String code, String clientAddress, String userAgent) throws Exception {
        mockMvc.perform(get("/{code}", code)
                        .header(HttpHeaders.USER_AGENT, userAgent)
                        .with(request -> {
                            request.setRemoteAddr(clientAddress);
                            return request;
                        }))
                .andExpect(status().isFound());
    }

    private static String fingerprint(char filler) {
        return String.valueOf(filler).repeat(64);
    }
}
