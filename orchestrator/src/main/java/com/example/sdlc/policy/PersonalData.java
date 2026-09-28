package com.example.sdlc.policy;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recognises names of columns (or fields) that hold raw personal data: IP addresses, e-mail addresses, phone
 * numbers and user agents. Matching works on whole name tokens, so "description", "zip" or "recipient" never
 * match "ip"; a name ending in a derivation suffix (e.g. {@code visitor_ip_hash}) is a pseudonym, not raw data.
 */
public final class PersonalData {

    private static final Set<String> RAW_TOKENS = Set.of(
            "ip", "ips", "ipv4", "ipv6", "ipaddr", "ipaddress",
            "email", "emails", "emailaddress",
            "phone", "phones", "phonenumber", "msisdn",
            "useragent", "remoteaddr");
    private static final List<List<String>> RAW_TOKEN_PAIRS = List.of(
            List.of("e", "mail"), List.of("user", "agent"), List.of("remote", "addr"), List.of("remote", "address"));
    private static final Set<String> DERIVED_SUFFIXES = Set.of("hash", "hmac", "fingerprint", "digest");

    private PersonalData() {
    }

    /** @param columnName an identifier, optionally quoted ({@code "client_ip"}, {@code `email`}, {@code [phone]}) */
    public static boolean isRawPersonalDataColumn(String columnName) {
        List<String> tokens = tokens(columnName);
        if (tokens.isEmpty() || DERIVED_SUFFIXES.contains(tokens.getLast())) {
            return false;
        }
        if (tokens.stream().anyMatch(RAW_TOKENS::contains)) {
            return true;
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (RAW_TOKEN_PAIRS.contains(tokens.subList(i, i + 2))) {
                return true;
            }
        }
        return false;
    }

    /** Splits on anything but letters and digits, and at camel-case humps, so client_ip, clientIp and "client-ip" agree. */
    private static List<String> tokens(String identifier) {
        if (identifier == null) {
            return List.of();
        }
        String separated = identifier.replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", "_");
        return Arrays.stream(separated.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> !token.isEmpty())
                .toList();
    }
}
