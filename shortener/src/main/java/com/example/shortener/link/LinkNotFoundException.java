package com.example.shortener.link;

public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException(String code) {
        super("No link exists for code '" + code + "'");
    }
}
