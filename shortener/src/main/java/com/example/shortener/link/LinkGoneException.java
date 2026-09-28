package com.example.shortener.link;

public class LinkGoneException extends RuntimeException {

    public LinkGoneException(String code) {
        super("Link '" + code + "' has been disabled");
    }
}
