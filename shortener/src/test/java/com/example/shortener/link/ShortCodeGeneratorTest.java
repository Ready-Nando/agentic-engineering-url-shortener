package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShortCodeGeneratorTest {

    @ParameterizedTest
    @ValueSource(ints = {4, 7, 32})
    void generatesBase62CodesOfConfiguredLength(int length) {
        ShortCodeGenerator generator = new ShortCodeGenerator(new SplittableRandom(42), length);

        IntStream.range(0, 1_000).mapToObj(i -> generator.generate())
                .forEach(code -> assertThat(code).hasSize(length).matches("[0-9A-Za-z]+"));
    }

    @Test
    void usesTheWholeAlphabet() {
        ShortCodeGenerator generator = new ShortCodeGenerator(new SplittableRandom(7), 7);
        Set<Character> seen = new HashSet<>();

        IntStream.range(0, 2_000).forEach(i -> generator.generate().chars().forEach(c -> seen.add((char) c)));

        assertThat(seen).hasSize(ShortCodeGenerator.ALPHABET.length());
    }

    @Test
    void isDeterministicForTheSameRandomSequence() {
        List<String> first = generate(new ShortCodeGenerator(new SplittableRandom(99), 7), 20);
        List<String> second = generate(new ShortCodeGenerator(new SplittableRandom(99), 7), 20);

        assertThat(first).isEqualTo(second).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 33})
    void rejectsLengthsTheRedirectRouteCannotServe(int length) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ShortCodeGenerator(new SplittableRandom(1), length));
    }

    private static List<String> generate(ShortCodeGenerator generator, int count) {
        return IntStream.range(0, count).mapToObj(i -> generator.generate()).toList();
    }
}
