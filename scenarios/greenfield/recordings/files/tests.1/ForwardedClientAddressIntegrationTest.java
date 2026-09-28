package com.example.shortener.abuse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The deployment the README recommends behind a reverse proxy: Spring's forwarded-header support takes the client
 * address from {@code X-Forwarded-For} and reports IPv6 clients in brackets, which must not defeat the /64
 * grouping. Runs in its own application context because of the property.
 */
@TestPropertySource(properties = "server.forward-headers-strategy=framework")
class ForwardedClientAddressIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("AC-4: behind a proxy, addresses from one IPv6 /64 network still count as one reporter")
    void groupsForwardedIpv6ClientsByNetwork() throws Exception {
        String code = createLink("https://example.org/");

        for (String client : List.of("2001:db8:1:2::10", "2001:db8:1:2::20", "2001:db8:1:2::30")) {
            reportFrom(code, client);
        }

        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM abuse_report").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-3: behind a proxy, reporters are told apart by the forwarded address, not the proxy's")
    void identifiesReportersByTheForwardedAddress() throws Exception {
        String code = createLink("https://example.org/");

        for (String client : List.of("203.0.113.1", "198.51.100.2", "192.0.2.3")) {
            reportFrom(code, client);
        }

        mockMvc.perform(get("/{code}", code)).andExpect(status().isGone());
    }

    // Every request reaches the service from the proxy's address (MockMvc's default 127.0.0.1).
    private void reportFrom(String code, String forwardedFor) throws Exception {
        mockMvc.perform(post("/api/v1/links/{code}/reports", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "SPAM"}
                                """)
                        .header("X-Forwarded-For", forwardedFor))
                .andExpect(status().isAccepted());
    }
}
