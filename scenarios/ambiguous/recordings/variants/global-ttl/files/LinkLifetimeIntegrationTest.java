package com.example.shortener.link.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import com.example.shortener.TestClockConfiguration;
import com.example.shortener.config.ShortenerProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Every link expires one configured lifetime (365 days in application.yml) after its creation. "Now" is the fixed
 * test clock, so a link is aged by moving its stored created_at into the past instead of waiting.
 */
class LinkLifetimeIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = TestClockConfiguration.NOW;
    private static final Duration LIFETIME = Duration.ofDays(365);

    @Autowired
    ShortenerProperties properties;

    @Test
    @DisplayName("AC-1: the application runs with the 365-day lifetime configured in application.yml")
    void configuredLifetimeIs365Days() {
        assertThat(properties.linkLifetime()).isEqualTo(LIFETIME);
    }

    @Test
    @DisplayName("AC-2: a link redirects with 302 until its lifetime has passed and answers 404 link-not-found from then on")
    void redirectsUntilTheLifetimeHasPassed() throws Exception {
        String code = createLink("https://example.org/campaign");
        age(code, NOW.minus(LIFETIME).plusSeconds(1));

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.org/campaign"));

        age(code, NOW.minus(LIFETIME));
        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://example.com/problems/link-not-found"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mockMvc.perform(head("/{code}", code)).andExpect(status().isNotFound());

        assertThat(clickCount()).as("only the redirect before expiry was recorded").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-2: an expired link answers exactly like a code that never existed")
    void expiredLinkIsIndistinguishableFromAnUnknownCode() throws Exception {
        String expired = createLink("https://example.org/old", "old-offer");
        age(expired, NOW.minus(LIFETIME).minusSeconds(1));

        String expiredBody = mockMvc.perform(get("/old-offer"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String unknownBody = mockMvc.perform(get("/new-offer"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(expiredBody.replace("old-offer", "CODE")).isEqualTo(unknownBody.replace("new-offer", "CODE"));
        assertThat(clickCount()).isZero();
    }

    @Test
    @DisplayName("AC-3: an expired link can still be read and keeps reporting its clicks")
    void expiredLinkStaysReadableWithItsStatistics() throws Exception {
        String code = createLink("https://example.org/stats");
        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
        age(code, NOW.minus(LIFETIME).minusSeconds(1));

        mockMvc.perform(get("/{code}", code)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/links/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1));
    }

    @Test
    @DisplayName("AC-4: links stored before expiry existed expire by their created_at as well, without any migration")
    void existingRowsExpireRetroactively() throws Exception {
        insertRow("legacy01", NOW.minus(Duration.ofDays(400)));
        insertRow("legacy02", NOW.minus(Duration.ofDays(30)));

        mockMvc.perform(get("/legacy01")).andExpect(status().isNotFound());
        mockMvc.perform(get("/legacy02"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.org/legacy02"));
    }

    @Test
    @DisplayName("AC-5: link responses are unchanged and carry no expiry field")
    void linkResponsesGainNoField() throws Exception {
        String code = createLink("https://example.org/fields");

        mockMvc.perform(get("/api/v1/links/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value("2026-03-15T10:00:00Z"))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    private void age(String code, Instant createdAt) {
        jdbc.sql("UPDATE short_link SET created_at = :createdAt WHERE code = :code")
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .param("code", code)
                .update();
    }

    private void insertRow(String code, Instant createdAt) {
        jdbc.sql("INSERT INTO short_link (code, target_url, status, created_at) VALUES (:code, :url, 'ACTIVE', :createdAt)")
                .param("code", code)
                .param("url", "https://example.org/" + code)
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
    }

    private long clickCount() {
        return jdbc.sql("SELECT COUNT(*) FROM click_event").query(Long.class).single();
    }
}
