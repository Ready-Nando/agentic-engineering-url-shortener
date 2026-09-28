package com.example.shortener.link;

public class InvalidAliasException extends RuntimeException {

    public InvalidAliasException(String detail) {
        super(detail);
    }
}
