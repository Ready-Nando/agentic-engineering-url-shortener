package com.example.shortener.link.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import com.example.shortener.TestClockConfiguration;
import com.example.shortener.analytics.ClickEvent;
import com.example.shortener.analytics.ClickEventRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * "Now" is fixed at 2026-03-15T10:00:00Z by the shared test clock. Time cannot pass, so a test lets a link
 * expire by moving its stored expiry to the present or the past, which is what the row of a link whose
 * expiry has been reached looks like.
 */
class LinkExpiryIntegrationTest extends AbstractIntegrationTest {

    private static final String PROBLEMS = "https://example.com/problems/";

    @Autowired
    ClickEventRepository clickEvents;

    @Test
    @DisplayName("AC-1: an expiresAt given at creation is returned by POST and GET")
    void returnsTheExpiryGivenAtCreation() throws Exception {
        String code = createExpiringLink("https://example.org/spring-sale", "2026-04-01T00:00:00Z");

        mockMvc.perform(get("/api/v1/links/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value("2026-04-01T00:00:00Z"));
    }

    @Test
    @DisplayName("AC-1: an expiresAt with a UTC offset is kept as the same instant and returned in UTC")
    void returnsTheExpiryInUtc() throws Exception {
        postLink("""
                {"url": "https://example.org/", "expiresAt": "2026-04-01T02:00:00+02:00"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value("2026-04-01T00:00:00Z"));
    }

    @Test
    @DisplayName("AC-1: a link created without expiresAt reports it as null")
    void reportsNullForALinkWithoutExpiry() throws Exception {
        String body = postLink("""
                {"url": "https://example.org/"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").hasJsonPath())
                .andExpect(jsonPath("$.expiresAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/v1/links/{code}", JsonPath.<String>read(body, "$.code")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").hasJsonPath())
                .andExpect(jsonPath("$.expiresAt").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-03-14T10:00:00Z, expiresAt must be in the future",
            "2026-03-15T10:00:00Z, expiresAt must be in the future",
            "2027-03-15T10:00:00.000001Z, expiresAt must be at most 365 days in the future",
            "2030-01-01T00:00:00Z, expiresAt must be at most 365 days in the future"
    })
    @DisplayName("AC-2: an expiresAt that is not in the future or more than 365 days ahead is rejected")
    void rejectsExpiryOutsideTheAllowedRange(String expiresAt, String detail) throws Exception {
        postLink("""
                {"url": "https://example.org/", "expiresAt": "%s"}
                """.formatted(expiresAt))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-expiry"))
                .andExpect(jsonPath("$.title").value("Invalid expiry"))
                .andExpect(jsonPath("$.detail").value(detail));

        assertThat(linkCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-03-15T10:00:01Z", "2027-03-15T10:00:00Z"})
    @DisplayName("AC-2: expiries from just after now up to exactly 365 days ahead are accepted")
    void acceptsExpiryInsideTheAllowedRange(String expiresAt) throws Exception {
        postLink("""
                {"url": "https://example.org/", "expiresAt": "%s"}
                """.formatted(expiresAt))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(expiresAt));
    }

    // Epoch seconds 1775000000 are 2026-03-31T23:33:20Z, inside the allowed range: only the format is wrong.
    @ParameterizedTest
    @ValueSource(strings = {"\"next week\"", "\"2026-04-01\"", "\"2026-04-01T00:00:00\"", "\"1775000000\"", "1775000000"})
    @DisplayName("AC-2: an expiresAt that is not an ISO-8601 date-time with offset is rejected")
    void rejectsExpiryThatIsNotADateTimeWithOffset(String expiresAtJson) throws Exception {
        postLink("""
                {"url": "https://example.org/", "expiresAt": %s}
                """.formatted(expiresAtJson))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-expiry"))
                .andExpect(jsonPath("$.detail").value(
                        "expiresAt must be an ISO-8601 date-time with offset, such as 2027-01-01T00:00:00Z"));

        assertThat(linkCount()).isZero();
    }

    @Test
    @DisplayName("AC-3: a link redirects until its expiry and answers 410 Gone from then on, recording no click")
    void stopsRedirectingAtTheExpiryInstant() throws Exception {
        String code = createExpiringLink("https://example.org/offer", "2026-03-15T10:00:01Z");

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.org/offer"));
        assertThat(clickCount()).isEqualTo(1);

        expireAt(code, TestClockConfiguration.NOW);

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isGone())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "link-gone"))
                .andExpect(jsonPath("$.title").value("Link gone"))
                .andExpect(jsonPath("$.detail").value("Link '" + code + "' expired at 2026-03-15T10:00:00Z"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        assertThat(clickCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-4: an expired link can still be read and keeps its statistics")
    void expiredLinkStaysReadableWithItsStatistics() throws Exception {
        String code = createExpiringLink("https://example.org/offer", "2026-03-20T00:00:00Z");
        clickEvents.insert(new ClickEvent(linkId(code), Instant.parse("2026-03-14T12:00:00Z"), "news.example"));
        expireAt(code, Instant.parse("2026-03-15T00:00:00Z"));

        mockMvc.perform(get("/api/v1/links/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value("2026-03-15T00:00:00Z"));
        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1))
                .andExpect(jsonPath("$.clicksInWindow").value(1))
                .andExpect(jsonPath("$.topReferrers[0].host").value("news.example"));
    }

    @Test
    @DisplayName("AC-5: a link stored without an expiry, like every link created before expiry existed, keeps redirecting")
    void linkWithoutExpiryNeverExpires() throws Exception {
        // Only the columns a link had before the expiry migration, created ten years before "now".
        jdbc.sql("""
                        INSERT INTO short_link (code, target_url, status, created_at)
                        VALUES ('legacy1', 'https://example.org/legacy', 'ACTIVE', :createdAt)
                        """)
                .param("createdAt", OffsetDateTime.parse("2016-03-15T10:00:00Z"))
                .update();

        mockMvc.perform(get("/legacy1"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.org/legacy"));
        mockMvc.perform(get("/api/v1/links/legacy1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(nullValue()));
    }

    @Test
    void replaysTheExpiryForARepeatedIdempotencyKeyAndPayload() throws Exception {
        String payload = """
                {"url": "https://example.org/checkout", "expiresAt": "2026-04-01T00:00:00Z"}
                """;
        postLink(payload, "order-7").andExpect(status().isCreated());

        postLink(payload, "order-7")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.expiresAt").value("2026-04-01T00:00:00Z"));
    }

    @Test
    void rejectsAnIdempotencyKeyReusedWithAnotherExpiry() throws Exception {
        postLink("""
                {"url": "https://example.org/checkout", "expiresAt": "2026-04-01T00:00:00Z"}
                """, "order-8").andExpect(status().isCreated());

        postLink("""
                {"url": "https://example.org/checkout", "expiresAt": "2026-05-01T00:00:00Z"}
                """, "order-8")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "idempotency-key-reuse"));
        postLink("""
                {"url": "https://example.org/checkout"}
                """, "order-8")
                .andExpect(status().isUnprocessableContent());

        assertThat(linkCount()).isEqualTo(1);
    }

    private String createExpiringLink(String url, String expiresAt) throws Exception {
        String body = postLink("""
                {"url": "%s", "expiresAt": "%s"}
                """.formatted(url, expiresAt))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(expiresAt))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    private void expireAt(String code, Instant expiresAt) {
        jdbc.sql("UPDATE short_link SET expires_at = :expiresAt WHERE code = :code")
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .param("code", code)
                .update();
    }

    private ResultActions postLink(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions postLink(String json, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(json));
    }

    private long linkCount() {
        return jdbc.sql("SELECT COUNT(*) FROM short_link").query(Long.class).single();
    }

    private long clickCount() {
        return jdbc.sql("SELECT COUNT(*) FROM click_event").query(Long.class).single();
    }
}
