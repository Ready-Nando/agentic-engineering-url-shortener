package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AliasPolicyTest {

    private final AliasPolicy policy = new AliasPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"abcd", "launch-2026", "Spring_Sale", "a1-_", "abcdefghijklmnopqrstuvwxyz012345"})
    void acceptsWellFormedAliases(String alias) {
        assertThatCode(() -> policy.validate(alias)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "abcdefghijklmnopqrstuvwxyz0123456", "with space", "dot.ted", "slash/ed",
            "café", "semi;colon", "q?uery"})
    void rejectsMalformedAliases(String alias) {
        assertThatThrownBy(() -> policy.validate(alias))
                .isInstanceOf(InvalidAliasException.class)
                .hasMessageContaining("4-32 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "Actuator", "Swagger", "swagger-ui", "HEALTH", "openapi", "static",
            "error"})
    void rejectsReservedWordsIgnoringCase(String alias) {
        assertThatThrownBy(() -> policy.validate(alias))
                .isInstanceOf(InvalidAliasException.class)
                .hasMessageContaining("reserved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "Admin", "OpenAPI"})
    void reportsReservedWordsIgnoringCase(String word) {
        assertThat(policy.isReserved(word)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"apis", "administrator", "my-admin"})
    void onlyExactReservedWordsAreReserved(String word) {
        assertThat(policy.isReserved(word)).isFalse();
    }
}
