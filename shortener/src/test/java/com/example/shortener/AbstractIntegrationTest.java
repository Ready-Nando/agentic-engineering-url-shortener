package com.example.shortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests that extend this class without adding configuration share one application
 * context, and therefore one in-memory database, which is emptied before every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM click_event").update();
        jdbc.sql("DELETE FROM short_link").update();
    }

    protected String createLink(String url) throws Exception {
        return postAndReturnCode("""
                {"url": "%s"}
                """.formatted(url));
    }

    protected String createLink(String url, String alias) throws Exception {
        return postAndReturnCode("""
                {"url": "%s", "alias": "%s"}
                """.formatted(url, alias));
    }

    protected long linkId(String code) {
        return jdbc.sql("SELECT id FROM short_link WHERE code = :code").param("code", code).query(Long.class).single();
    }

    private String postAndReturnCode(String json) throws Exception {
        String body = mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }
}
