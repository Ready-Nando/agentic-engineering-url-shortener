package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.AbstractIntegrationTest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

@Import(CodeCollisionIntegrationTest.ScriptedRandomConfiguration.class)
class CodeCollisionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ScriptedRandom random;

    @BeforeEach
    void resetScript() {
        random.clear();
    }

    @Test
    void retriesWithAFreshCodeWhenTheGeneratedOneIsTaken() throws Exception {
        random.willGenerate("AAAAAAA", "AAAAAAA", "BBBBBBB");
        createLink("https://example.org/first");

        postLink("https://example.org/second")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("BBBBBBB"));

        assertThat(random.remaining()).isZero();
    }

    @Test
    void generatedCodesAndCustomAliasesShareOneNamespace() throws Exception {
        createLink("https://example.org/alias", "CCCCCCC");
        random.willGenerate("CCCCCCC", "DDDDDDD");

        postLink("https://example.org/generated")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("DDDDDDD"));
    }

    @Test
    void keepsTheIdempotencyKeyAcrossRetries() throws Exception {
        random.willGenerate("EEEEEEE", "EEEEEEE", "FFFFFFF");
        createLink("https://example.org/first");

        postLink("https://example.org/second", "retry-key").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("FFFFFFF"));
        postLink("https://example.org/second", "retry-key").andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.code").value("FFFFFFF"));
    }

    @Test
    void neverHandsOutAReservedWordAsAGeneratedCode() throws Exception {
        random.willGenerate("metrics", "OpenAPI", "HHHHHHH");

        postLink("https://example.org/reserved")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("HHHHHHH"));

        assertThat(random.remaining()).isZero();
    }

    @Test
    void givesUpWithServiceUnavailableAfterTheConfiguredNumberOfAttempts() throws Exception {
        random.willGenerate("GGGGGGG");
        createLink("https://example.org/first");
        random.willGenerate("GGGGGGG", "GGGGGGG", "GGGGGGG", "GGGGGGG", "GGGGGGG");

        postLink("https://example.org/second")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://example.com/problems/code-generation-failed"))
                .andExpect(jsonPath("$.status").value(503));

        // Exactly max-code-generation-attempts (5) codes were drawn; a sixth draw would have failed the script.
        assertThat(random.remaining()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM short_link").query(Long.class).single()).isEqualTo(1);
    }

    private ResultActions postLink(String url) throws Exception {
        return mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"" + url + "\"}"));
    }

    private ResultActions postLink(String url, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content("{\"url\": \"" + url + "\"}"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedRandomConfiguration {

        @Bean
        @Primary
        ScriptedRandom scriptedRandom() {
            return new ScriptedRandom();
        }
    }

    /**
     * Replays the characters of scripted codes, so a test decides exactly which codes the generator produces.
     */
    static class ScriptedRandom implements RandomGenerator {

        private final Deque<Integer> indexes = new ArrayDeque<>();

        void willGenerate(String... codes) {
            for (String code : codes) {
                code.chars().forEach(c -> indexes.add(ShortCodeGenerator.ALPHABET.indexOf(c)));
            }
        }

        int remaining() {
            return indexes.size();
        }

        void clear() {
            indexes.clear();
        }

        @Override
        public int nextInt(int bound) {
            Integer next = indexes.poll();
            if (next == null) {
                throw new IllegalStateException("The test did not script enough codes");
            }
            return next;
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException("Only nextInt(bound) is scripted");
        }
    }
}
