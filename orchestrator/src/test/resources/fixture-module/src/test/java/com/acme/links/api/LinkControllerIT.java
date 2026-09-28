package com.acme.links.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LinkController.class)
class LinkControllerIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void shortenStoresTheLink() throws Exception {
        String body = "{\"targetUrl\":\"https://example.org/a?b=c\"}";
        mvc.perform(post("/api/links").contentType("application/json").content(body))
                .andExpect(status().isCreated());

        long rows = jdbc.sql("SELECT count(*) FROM links").query(Long.class).single();
        assertThat(rows).isEqualTo(1);
        assertThat(new ShortenRequest("https://example.org").targetUrl()).isNotBlank();
    }
}
