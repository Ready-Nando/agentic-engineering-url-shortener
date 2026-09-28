package com.example.sdlc.codebase;

import java.util.Comparator;

/**
 * An HTTP handler method. {@code method} is {@code ANY} for a method-level {@code @RequestMapping}
 * without a {@code method} attribute, because Spring then maps every HTTP method.
 */
public record Endpoint(String method, String path, String handlerType, String handlerMethod) {

    static final Comparator<Endpoint> ORDER = Comparator.comparing(Endpoint::path)
            .thenComparing(Endpoint::method)
            .thenComparing(Endpoint::handlerType)
            .thenComparing(Endpoint::handlerMethod);

    /** The endpoint in the {@code "METHOD /path"} form that impact seeds use. */
    public String route() {
        return method + " " + path;
    }

    /**
     * Canonical form of a Spring path pattern: one leading slash, no duplicate or trailing slashes, and
     * {@code {name:regex}} reduced to {@code {name}}. The regex may itself contain braces, as in
     * {@code {code:[a-z]{4,32}}}, so variables are delimited by brace depth rather than by a regex.
     */
    static String normalizePath(String raw) {
        String text = raw.strip();
        StringBuilder out = new StringBuilder("/");
        int i = 0;
        while (i < text.length()) {
            int close = text.charAt(i) == '{' ? closingBrace(text, i) : -1;
            if (close < 0) {
                out.append(text.charAt(i++));
                continue;
            }
            int colon = text.indexOf(':', i);
            int nameEnd = colon >= 0 && colon < close ? colon : close;
            out.append('{').append(text, i + 1, nameEnd).append('}');
            i = close + 1;
        }
        String path = out.toString().replaceAll("/{2,}", "/");
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static int closingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
