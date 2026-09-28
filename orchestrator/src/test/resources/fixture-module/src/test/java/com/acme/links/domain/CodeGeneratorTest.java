package com.acme.links.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    @Test
    void generatesSixCharacterCodes() {
        assertThat(new CodeGenerator().next()).hasSize(6);
    }
}
