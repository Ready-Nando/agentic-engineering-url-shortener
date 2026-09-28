package com.example.sdlc.cli;

/** Minimal ANSI styling; disabled when output is not a terminal or NO_COLOR is set. */
record Style(boolean enabled) {

    static Style detect() {
        return new Style(System.console() != null && System.getenv("NO_COLOR") == null && !"dumb".equals(System.getenv("TERM")));
    }

    String green(String text) {
        return wrap("32", text);
    }

    String red(String text) {
        return wrap("31", text);
    }

    String yellow(String text) {
        return wrap("33", text);
    }

    String cyan(String text) {
        return wrap("36", text);
    }

    String dim(String text) {
        return wrap("2", text);
    }

    String bold(String text) {
        return wrap("1", text);
    }

    private String wrap(String code, String text) {
        return enabled ? "\u001b[" + code + "m" + text + "\u001b[0m" : text;
    }
}
