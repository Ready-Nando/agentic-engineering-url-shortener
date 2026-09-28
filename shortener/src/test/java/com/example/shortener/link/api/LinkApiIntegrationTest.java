package com.example.shortener.link.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import com.example.shortener.TestClockConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class LinkApiIntegrationTest extends AbstractIntegrationTest {

    private static final String PROBLEMS = "https://example.com/problems/";

    @Test
    void createsLinkWithGeneratedCode() throws Exception {
        MvcResult result = postLink("""
                {"url": "https://example.org/articles/1?ref=newsletter"}
                """)
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code", matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.targetUrl").value("https://example.org/articles/1?ref=newsletter"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value("2026-03-15T10:00:00Z"))
                .andExpect(jsonPath("$.disabledAt", nullValue()))
                .andReturn();

        String code = JsonPath.read(result.getResponse().getContentAsString(), "$.code");
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("https://sho.rt/api/v1/links/" + code);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.shortUrl"))
                .isEqualTo("https://sho.rt/" + code);
    }

    @Test
    void createsLinkWithCustomAlias() throws Exception {
        postLink("""
                {"url": "https://example.org/launch", "alias": "launch-2026"}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://sho.rt/api/v1/links/launch-2026"))
                .andExpect(jsonPath("$.code").value("launch-2026"))
                .andExpect(jsonPath("$.shortUrl").value("https://sho.rt/launch-2026"));
    }

    @Test
    void rejectsMissingUrlWithFieldErrors() throws Exception {
        postLink("{}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/v1/links"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("url"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void rejectsOverlongUrlWithFieldErrors() throws Exception {
        String url = "https://example.org/" + "a".repeat(2029);

        postLink("""
                {"url": "%s"}
                """.formatted(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("url"))
                .andExpect(jsonPath("$.errors[0].message").value("size must be between 0 and 2048"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        postLink("{\"url\": ")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "malformed-request"))
                .andExpect(jsonPath("$.detail").value("The request body is missing or is not valid JSON"));
    }

    @Test
    void rejectsNonHttpTargetUrl() throws Exception {
        postLink("""
                {"url": "javascript:alert(1)"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-target-url"))
                .andExpect(jsonPath("$.detail").value("URL must be absolute and use http or https"));
    }

    @Test
    void rejectsTargetUrlPointingAtTheShortenerItself() throws Exception {
        postLink("""
                {"url": "https://sho.rt/abc1234"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-target-url"));
    }

    @Test
    void rejectsAliasThatIsAlreadyTaken() throws Exception {
        createLink("https://example.org/first", "promo");

        postLink("""
                {"url": "https://example.org/second", "alias": "promo"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "alias-unavailable"))
                .andExpect(jsonPath("$.detail").value("Alias 'promo' is already in use"));
    }

    @Test
    void rejectsReservedAlias() throws Exception {
        postLink("""
                {"url": "https://example.org/", "alias": "Admin"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-alias"))
                .andExpect(jsonPath("$.detail").value("Alias 'Admin' is reserved"));
    }

    @Test
    void rejectsMalformedAlias() throws Exception {
        postLink("""
                {"url": "https://example.org/", "alias": "no/slashes"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-alias"));
    }

    @Test
    void replaysCreationForRepeatedIdempotencyKeyAndPayload() throws Exception {
        String payload = """
                {"url": "https://example.org/checkout"}
                """;
        MvcResult first = postLink(payload, "order-123").andExpect(status().isCreated()).andReturn();
        String code = JsonPath.read(first.getResponse().getContentAsString(), "$.code");

        postLink(payload, "order-123")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string(HttpHeaders.LOCATION, "https://sho.rt/api/v1/links/" + code))
                .andExpect(jsonPath("$.code").value(code));

        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(linkCount()).isEqualTo(1);
    }

    @Test
    void replaysAliasCreationInsteadOfReportingAConflictWithItself() throws Exception {
        String payload = """
                {"url": "https://example.org/checkout", "alias": "checkout"}
                """;
        postLink(payload, "retry-me").andExpect(status().isCreated());

        postLink(payload, "retry-me")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.code").value("checkout"));
    }

    @Test
    void rejectsIdempotencyKeyReusedWithDifferentPayload() throws Exception {
        postLink("""
                {"url": "https://example.org/one"}
                """, "order-123").andExpect(status().isCreated());

        postLink("""
                {"url": "https://example.org/two"}
                """, "order-123")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "idempotency-key-reuse"));

        assertThat(linkCount()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedIdempotencyKey() throws Exception {
        postLink("""
                {"url": "https://example.org/"}
                """, "k".repeat(65))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-idempotency-key"));
    }

    @Test
    void getsLinkByCode() throws Exception {
        String code = createLink("https://example.org/docs");

        mockMvc.perform(get("/api/v1/links/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.shortUrl").value("https://sho.rt/" + code))
                .andExpect(jsonPath("$.targetUrl").value("https://example.org/docs"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void answersNotFoundProblemForUnknownCode() throws Exception {
        mockMvc.perform(get("/api/v1/links/{code}", "missing1"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "link-not-found"))
                .andExpect(jsonPath("$.title").value("Link not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("No link exists for code 'missing1'"))
                .andExpect(jsonPath("$.instance").value("/api/v1/links/missing1"));
    }

    @Test
    void disablingLinkKeepsItReadableAndMakesRedirectGone() throws Exception {
        String code = createLink("https://example.org/sale");

        mockMvc.perform(delete("/api/v1/links/{code}", code))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/v1/links/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.disabledAt").value("2026-03-15T10:00:00Z"));
        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "link-gone"));
    }

    @Test
    void disablingIsIdempotentAndKeepsTheOriginalDisabledTime() throws Exception {
        String code = createLink("https://example.org/sale");
        mockMvc.perform(delete("/api/v1/links/{code}", code)).andExpect(status().isNoContent());
        OffsetDateTime earlier = TestClockConfiguration.NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC);
        jdbc.sql("UPDATE short_link SET disabled_at = :at WHERE code = :code")
                .param("at", earlier).param("code", code).update();

        mockMvc.perform(delete("/api/v1/links/{code}", code)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/links/{code}", code))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.disabledAt").value("2026-03-15T09:00:00Z"));
    }

    @Test
    void answersNotFoundWhenDisablingUnknownCode() throws Exception {
        mockMvc.perform(delete("/api/v1/links/{code}", "missing1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "link-not-found"));
    }

    @Test
    void codeOfDisabledLinkIsNeverReused() throws Exception {
        createLink("https://example.org/old", "campaign");
        mockMvc.perform(delete("/api/v1/links/{code}", "campaign")).andExpect(status().isNoContent());

        postLink("""
                {"url": "https://example.org/new", "alias": "campaign"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "alias-unavailable"));
    }

    @Test
    void unsupportedMethodIsAProblemDocumentWithTheDefaultType() throws Exception {
        // An absent "type" means "about:blank" (RFC 9457, section 3.1.1).
        mockMvc.perform(put("/api/v1/links/abcd"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andExpect(jsonPath("$.status").value(405));
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
}
