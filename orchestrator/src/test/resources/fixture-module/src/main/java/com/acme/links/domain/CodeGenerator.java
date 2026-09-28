package com.acme.links.domain;

import java.security.SecureRandom;

/**
 * Random short codes. Unlike {@link LinkStats} this is not a value type, but it is still free of Spring.
 */
public final class CodeGenerator {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
