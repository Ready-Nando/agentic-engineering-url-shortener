package com.example.shortener.link;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be 1-64 characters of letters, digits, '_' or '-'");
    }
}
