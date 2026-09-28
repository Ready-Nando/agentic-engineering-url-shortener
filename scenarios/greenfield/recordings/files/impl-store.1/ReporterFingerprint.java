package com.example.shortener.abuse;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pseudonymous reporter identifier: HMAC-SHA256 over the link id and the client's network.
 * <p>
 * The key stops anyone holding the database from recovering addresses by hashing the (small) IPv4 space, and
 * including the link id means that the same person cannot be followed across the links they report. IPv6
 * clients are identified by their /64 network: a subscriber usually controls a whole /64 and could otherwise
 * pose as any number of reporters. The client address itself is never stored or logged.
 */
@Component
public class ReporterFingerprint {

    private static final Logger log = LoggerFactory.getLogger(ReporterFingerprint.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final int IPV6_NETWORK_BYTES = 8;
    // Only text shaped like an IPv6 literal is handed to InetAddress, which then parses it without a name lookup.
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*(%[\\w.-]+)?");

    private final SecretKeySpec key;

    public ReporterFingerprint(@Value("${shortener.abuse.reporter-hash-secret:}") String configuredKey) {
        byte[] keyBytes;
        if (configuredKey.isBlank()) {
            // Repeated reports then count again after a restart or on another instance: fine for development only.
            log.warn("No reporter fingerprint key configured (shortener.abuse.reporter-hash-secret); using a random per-process key");
            keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
        } else {
            keyBytes = configuredKey.getBytes(StandardCharsets.UTF_8);
        }
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * Hex-encoded fingerprint of whoever reports link {@code linkId} from {@code clientAddress}.
     */
    public String of(long linkId, String clientAddress) {
        String input = linkId + "\n" + network(clientAddress);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }

    /**
     * The part of a client address that identifies a reporter: the /64 prefix of an IPv6 address, the IPv4
     * address of an IPv4-mapped one, and anything else as given. IPv6 addresses are also accepted in brackets,
     * which is how Spring's forwarded-header support ({@code server.forward-headers-strategy=framework}) reports
     * them; otherwise every address of a /64 would count as another reporter behind such a proxy.
     */
    static String network(String clientAddress) {
        String address = clientAddress.strip();
        if (address.length() > 2 && address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        if (address.indexOf(':') < 0 || !IPV6_LITERAL.matcher(address).matches()) {
            return address;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            if (!(parsed instanceof Inet6Address)) {
                return parsed.getHostAddress();
            }
            byte[] network = parsed.getAddress();
            Arrays.fill(network, IPV6_NETWORK_BYTES, network.length, (byte) 0);
            return InetAddress.getByAddress(network).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            return address;
        }
    }
}
