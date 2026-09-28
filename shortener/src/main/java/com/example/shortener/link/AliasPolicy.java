package com.example.shortener.link;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AliasPolicy {

    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{4,32}$");

    // Aliases live in the same path namespace as the application's own routes; keep these free so
    // current and future top-level routes cannot be shadowed or impersonated.
    private static final Set<String> RESERVED = Set.of(
            "about", "actuator", "admin", "api", "assets", "docs", "error", "favicon", "health", "help",
            "index", "info", "login", "logout", "metrics", "openapi", "robots", "sitemap", "static",
            "status", "swagger", "swagger-ui", "www");

    public void validate(String alias) {
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new InvalidAliasException(
                    "Alias must be 4-32 characters of letters, digits, '_' or '-'");
        }
        if (isReserved(alias)) {
            throw new InvalidAliasException("Alias '" + alias + "' is reserved");
        }
    }

    public boolean isReserved(String alias) {
        return RESERVED.contains(alias.toLowerCase(Locale.ROOT));
    }
}
