package com.example.shortener.link.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The {@code Location} of a created link is built from {@code shortener.base-url}, like its {@code shortUrl}, and
 * never from the request, so a client cannot make the service hand out links to another origin with a forged
 * {@code Host} or forwarded header. Forwarded-header support is enabled, as recommended behind a reverse proxy, so
 * these headers do change what the request reports about itself. The base URL has a path, to show how it is joined.
 */
@TestPropertySource(properties = {
        "server.forward-headers-strategy=framework",
        "shortener.base-url=https://go.example.com/s/"})
class LinkLocationIntegrationTest extends AbstractIntegrationTest {

    private static final String PAYLOAD = """
            {"url": "https://example.org/launch", "alias": "launch-2026"}
            """;

    @Test
    void createdLinkLocationIgnoresHostAndForwardedHeaders() throws Exception {
        mockMvc.perform(create()
                        .header(HttpHeaders.HOST, "attacker.example")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "http")
                        .header("X-Forwarded-Port", "8443")
                        .header("X-Forwarded-Prefix", "/proxied"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://go.example.com/s/api/v1/links/launch-2026"))
                .andExpect(jsonPath("$.shortUrl").value("https://go.example.com/s/launch-2026"));
    }

    @Test
    void replayedLinkLocationIgnoresHostAndForwardedHeaders() throws Exception {
        MvcResult first = mockMvc.perform(create().header("Idempotency-Key", "launch-post-1"))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult replay = mockMvc.perform(create()
                        .header("Idempotency-Key", "launch-post-1")
                        .header(HttpHeaders.HOST, "attacker.example:9000")
                        .header("Forwarded", "host=evil.example;proto=http"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(replay.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(first.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("https://go.example.com/s/api/v1/links/launch-2026");
        assertThat((String) JsonPath.read(replay.getResponse().getContentAsString(), "$.shortUrl"))
                .isEqualTo("https://go.example.com/s/launch-2026");
    }

    private static MockHttpServletRequestBuilder create() {
        return post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(PAYLOAD);
    }
}
