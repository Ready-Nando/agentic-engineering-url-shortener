package com.example.shortener.link;

import com.example.shortener.config.ShortenerProperties;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Generates random base62 codes. Uniqueness is not checked here: the database unique constraint is
 * the source of truth and {@link LinkService} retries on collision.
 */
@Component
public class ShortCodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final RandomGenerator random;
    private final int length;

    @Autowired
    public ShortCodeGenerator(RandomGenerator random, ShortenerProperties properties) {
        this(random, properties.codeLength());
    }

    ShortCodeGenerator(RandomGenerator random, int length) {
        if (length < 4 || length > 32) {
            throw new IllegalArgumentException("Code length must be between 4 and 32, was " + length);
        }
        this.random = random;
        this.length = length;
    }

    public String generate() {
        char[] code = new char[length];
        for (int i = 0; i < length; i++) {
            code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }
}
