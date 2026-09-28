package com.example.shortener.link.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import com.example.shortener.TestClockConfiguration;
import com.example.shortener.analytics.ClickEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

class RedirectIntegrationTest extends AbstractIntegrationTest {

    @Test
    void redirectsWithUncacheableFound() throws Exception {
        String code = createLink("https://example.org/landing?campaign=spring");

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.org/landing?campaign=spring"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void redirectsCustomAlias() throws Exception {
        createLink("https://example.org/docs", "Docs_v2");

        mockMvc.perform(get("/Docs_v2"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.org/docs"));
    }

    @Test
    void percentEncodesNonAsciiTargetsInTheLocationHeader() throws Exception {
        String code = createLink("https://example.org/café?q=crème");

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.org/caf%C3%A9?q=cr%C3%A8me"));
    }

    @Test
    void answersNotFoundForUnknownCode() throws Exception {
        mockMvc.perform(get("/nope123"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://example.com/problems/link-not-found"));

        assertThat(clickCount()).isZero();
    }

    @Test
    void answersGoneForDisabledLinkWithoutRecordingAClick() throws Exception {
        String code = createLink("https://example.org/expired-offer");
        mockMvc.perform(delete("/api/v1/links/{code}", code)).andExpect(status().isNoContent());

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isGone())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://example.com/problems/link-gone"))
                .andExpect(jsonPath("$.title").value("Link disabled"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        assertThat(clickCount()).isZero();
    }

    @Test
    void recordsClickWithReferrerHostOnly() throws Exception {
        String code = createLink("https://example.org/");

        mockMvc.perform(get("/{code}", code)
                        .header(HttpHeaders.REFERER, "https://News.Example.ORG/search?q=my+private+query")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Test)")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isFound());

        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM click_event").query().listOfRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row).containsOnlyKeys("id", "link_id", "occurred_at", "referrer_host");
        assertThat(row.get("link_id")).isEqualTo(linkId(code));
        assertThat(row.get("referrer_host")).isEqualTo("news.example.org");
        assertThat(((OffsetDateTime) row.get("occurred_at")).toInstant()).isEqualTo(TestClockConfiguration.NOW);
    }

    @Test
    void recordsClickWithoutReferrer() throws Exception {
        String code = createLink("https://example.org/");

        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());

        assertThat(jdbc.sql("SELECT referrer_host FROM click_event").query(String.class).list())
                .singleElement().isNull();
    }

    @Test
    void headRequestRedirectsWithoutCountingAClick() throws Exception {
        String code = createLink("https://example.org/");

        mockMvc.perform(head("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.org/"));

        assertThat(clickCount()).isZero();
    }

    private long clickCount() {
        return jdbc.sql("SELECT COUNT(*) FROM click_event").query(Long.class).single();
    }

    /**
     * Runs in its own application context in which the click repository is a mock that tests make fail.
     */
    @Nested
    class WhenTheClickStoreFails {

        @MockitoBean
        ClickEventRepository clickEventRepository;

        @Autowired
        MockMvc failingMockMvc;

        @Autowired
        MeterRegistry meterRegistry;

        @Test
        void redirectStillSucceedsAndTheDropIsCounted() throws Exception {
            doThrow(new DataAccessResourceFailureException("click store unavailable"))
                    .when(clickEventRepository).insert(any());
            failingMockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"url": "https://example.org/resilient", "alias": "resilient"}
                                    """))
                    .andExpect(status().isCreated());
            double droppedBefore = meterRegistry.counter("shortener.analytics.dropped").count();

            failingMockMvc.perform(get("/resilient").header(HttpHeaders.REFERER, "https://example.net/"))
                    .andExpect(status().isFound())
                    .andExpect(header().string(HttpHeaders.LOCATION, "https://example.org/resilient"));

            assertThat(meterRegistry.counter("shortener.analytics.dropped").count()).isEqualTo(droppedBefore + 1);
        }

        @Test
        void statisticsFailWithAGenericProblemThatHidesTheCause() throws Exception {
            when(clickEventRepository.countByLink(anyLong()))
                    .thenThrow(new DataAccessResourceFailureException("Connection refused: jdbc:h2:mem:internal-db"));
            String code = createLink("https://example.org/stats");

            failingMockMvc.perform(get("/api/v1/links/{code}/stats", code))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://example.com/problems/internal-error"))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(content().string(not(containsString("jdbc"))));
        }
    }
}
