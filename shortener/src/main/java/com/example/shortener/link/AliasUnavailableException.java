package com.example.shortener.link;

public class AliasUnavailableException extends RuntimeException {

    public AliasUnavailableException(String alias) {
        super("Alias '" + alias + "' is already in use");
    }
}
