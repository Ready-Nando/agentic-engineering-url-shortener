package com.example.shortener.link;

public class InvalidExpiryException extends RuntimeException {

    public InvalidExpiryException(String detail) {
        super(detail);
    }
}
