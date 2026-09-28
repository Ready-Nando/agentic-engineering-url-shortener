package com.acme.links.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Goes through HTTP only, so it never names RedirectController. */
@SpringBootTest
@AutoConfigureMockMvc
class RedirectIT {

    @Autowired
    MockMvc mvc;

    @Test
    void redirectsToTheTarget() throws Exception {
        mvc.perform(get("/r/{code}", "abcd"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.org"));
    }

    @Test
    void goIsAnAliasForR() throws Exception {
        mvc.perform(get("/go/abcd?utm_source=mail")).andExpect(status().isFound());
    }
}
