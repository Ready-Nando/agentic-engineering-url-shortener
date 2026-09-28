package com.example.shortener.analytics.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import com.example.shortener.analytics.ClickEvent;
import com.example.shortener.analytics.ClickEventRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * "Now" is fixed at 2026-03-15T10:00:00Z by the shared test clock, so the default 7-day window is
 * 2026-03-09 to 2026-03-15 inclusive.
 */
class StatsApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ClickEventRepository clickEvents;

    @Test
    void reportsTotalsAndAZeroFilledDailySeriesForTheDefaultWindow() throws Exception {
        String code = createLink("https://example.org/");
        long id = linkId(code);
        click(id, "2026-03-01T12:00:00Z", "old.example");
        click(id, "2026-03-08T23:59:59Z", "old.example");
        click(id, "2026-03-09T00:00:00Z", "b.example");
        click(id, "2026-03-12T08:00:00Z", "c.example");
        click(id, "2026-03-12T09:00:00Z", "c.example");
        click(id, "2026-03-12T23:59:59Z", "c.example");
        click(id, "2026-03-12T10:00:00Z", null);
        click(id, "2026-03-15T09:59:00Z", "b.example");

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.totalClicks").value(8))
                .andExpect(jsonPath("$.lastClickAt").value("2026-03-15T09:59:00Z"))
                .andExpect(jsonPath("$.window.from").value("2026-03-09"))
                .andExpect(jsonPath("$.window.to").value("2026-03-15"))
                .andExpect(jsonPath("$.window.days").value(7))
                .andExpect(jsonPath("$.clicksInWindow").value(6))
                .andExpect(jsonPath("$.daily[*].date").value(contains(
                        "2026-03-09", "2026-03-10", "2026-03-11", "2026-03-12", "2026-03-13", "2026-03-14", "2026-03-15")))
                .andExpect(jsonPath("$.daily[*].clicks").value(contains(1, 0, 0, 4, 0, 0, 1)))
                .andExpect(jsonPath("$.topReferrers[*].host").value(contains("c.example", "b.example")))
                .andExpect(jsonPath("$.topReferrers[*].clicks").value(contains(3, 2)));
    }

    @Test
    void oneDayWindowCoversOnlyTodayInUtc() throws Exception {
        String code = createLink("https://example.org/");
        long id = linkId(code);
        // Finer than the column's microseconds: must be truncated, not rounded into today.
        click(id, "2026-03-14T23:59:59.999999999Z", "yesterday.example");
        click(id, "2026-03-15T00:00:00Z", "today.example");

        mockMvc.perform(get("/api/v1/links/{code}/stats", code).param("days", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(2))
                .andExpect(jsonPath("$.window.from").value("2026-03-15"))
                .andExpect(jsonPath("$.window.to").value("2026-03-15"))
                .andExpect(jsonPath("$.clicksInWindow").value(1))
                .andExpect(jsonPath("$.daily", hasSize(1)))
                .andExpect(jsonPath("$.topReferrers[*].host").value(contains("today.example")));
    }

    @Test
    void topReferrersAreTheFiveMostFrequentWithTiesBrokenByHost() throws Exception {
        String code = createLink("https://example.org/");
        long id = linkId(code);
        String today = "2026-03-15T08:00:00Z";
        for (int i = 0; i < 3; i++) {
            click(id, today, "z.example");
        }
        click(id, today, "y.example");
        click(id, today, "y.example");
        for (String host : new String[] {"e.example", "d.example", "c.example", "b.example", "a.example"}) {
            click(id, today, host);
        }

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topReferrers[*].host")
                        .value(contains("z.example", "y.example", "a.example", "b.example", "c.example")))
                .andExpect(jsonPath("$.topReferrers[*].clicks").value(contains(3, 2, 1, 1, 1)));
    }

    @Test
    void linkWithoutClicksHasEmptyStatistics() throws Exception {
        String code = createLink("https://example.org/");

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(0))
                .andExpect(jsonPath("$.lastClickAt", nullValue()))
                .andExpect(jsonPath("$.clicksInWindow").value(0))
                .andExpect(jsonPath("$.daily", hasSize(7)))
                .andExpect(jsonPath("$.daily[*].clicks", everyItem(is(0))))
                .andExpect(jsonPath("$.topReferrers", empty()));
    }

    @Test
    void acceptsTheMaximumWindow() throws Exception {
        String code = createLink("https://example.org/");

        mockMvc.perform(get("/api/v1/links/{code}/stats", code).param("days", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.from").value("2025-12-16"))
                .andExpect(jsonPath("$.daily", hasSize(90)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "91"})
    void rejectsWindowsOutsideTheAllowedRange(String days) throws Exception {
        String code = createLink("https://example.org/");

        mockMvc.perform(get("/api/v1/links/{code}/stats", code).param("days", days))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://example.com/problems/invalid-stats-window"))
                .andExpect(jsonPath("$.detail").value("days must be between 1 and 90, was " + days));
    }

    @Test
    void rejectsNonNumericWindow() throws Exception {
        String code = createLink("https://example.org/");

        mockMvc.perform(get("/api/v1/links/{code}/stats", code).param("days", "week"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://example.com/problems/invalid-parameter"))
                .andExpect(jsonPath("$.detail").value("Parameter 'days' has an invalid value"));
    }

    @Test
    void answersNotFoundForUnknownCode() throws Exception {
        mockMvc.perform(get("/api/v1/links/{code}/stats", "missing1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://example.com/problems/link-not-found"));
    }

    @Test
    void disabledLinkKeepsItsStatistics() throws Exception {
        String code = createLink("https://example.org/");
        click(linkId(code), "2026-03-14T12:00:00Z", "news.example");
        mockMvc.perform(delete("/api/v1/links/{code}", code)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1))
                .andExpect(jsonPath("$.topReferrers[0].host").value("news.example"));
    }

    @Test
    void countsRedirectsServedByTheApplication() throws Exception {
        String code = createLink("https://example.org/");

        mockMvc.perform(get("/{code}", code).header(HttpHeaders.REFERER, "https://social.example/post/1"))
                .andExpect(status().isFound());
        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(2))
                .andExpect(jsonPath("$.lastClickAt").value("2026-03-15T10:00:00Z"))
                .andExpect(jsonPath("$.daily[6].clicks").value(2))
                .andExpect(jsonPath("$.topReferrers[*].host").value(contains("social.example")));
    }

    private void click(long linkId, String at, String referrerHost) {
        clickEvents.insert(new ClickEvent(linkId, Instant.parse(at), referrerHost));
    }
}
