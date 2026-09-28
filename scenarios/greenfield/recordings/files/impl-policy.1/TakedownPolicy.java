package com.example.shortener.abuse;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * When community reports take a link down automatically: once {@value #THRESHOLD} distinct reporters have
 * reported it within the last 24 hours. Requiring several reporters keeps any single reporter from disabling
 * someone else's link; the sliding window keeps old, unrelated reports from adding up.
 */
@Component
public class TakedownPolicy {

    static final int THRESHOLD = 3;
    static final Duration WINDOW = Duration.ofHours(24);

    /**
     * Earliest report time (inclusive) that still counts towards a takedown decided at {@code now}.
     */
    public Instant windowStart(Instant now) {
        return now.minus(WINDOW);
    }

    /**
     * @param distinctReporters reporters of the link with a report at or after {@link #windowStart(Instant)}
     */
    public boolean requiresTakedown(long distinctReporters) {
        return distinctReporters >= THRESHOLD;
    }
}
