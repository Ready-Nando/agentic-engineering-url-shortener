package com.example.shortener.link;

public class InvalidTargetUrlException extends RuntimeException {

    public InvalidTargetUrlException(String detail) {
        super(detail);
    }
}
