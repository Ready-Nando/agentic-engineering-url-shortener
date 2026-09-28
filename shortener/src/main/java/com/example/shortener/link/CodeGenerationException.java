package com.example.shortener.link;

public class CodeGenerationException extends RuntimeException {

    public CodeGenerationException(int attempts) {
        super("Could not generate a unique short code after " + attempts + " attempts");
    }
}
