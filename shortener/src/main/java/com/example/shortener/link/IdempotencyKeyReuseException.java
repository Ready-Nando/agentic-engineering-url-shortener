package com.example.shortener.link;

public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request payload");
    }
}
