package com.example.shortener.abuse;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * When community reports take a link down automatically: once {@code threshold} distinct reporters have
 * reported it within the sliding {@code window}, by default 3 reporters within 24 hours. Trust and Safety tunes
 * both per environment with {@code shortener.abuse.takedown.threshold} and {@code shortener.abuse.takedown.window}.
 * <p>
 * Requiring several reporters keeps any single reporter from disabling someone else's link, which is why the
 * threshold cannot be set below 2; the window keeps old, unrelated reports from adding up.
 */
@Component
public class TakedownPolicy {

    private static final Logger log = LoggerFactory.getLogger(TakedownPolicy.class);

    private final int threshold;
    private final Duration window;

    public TakedownPolicy(@Value("${shortener.abuse.takedown.threshold:3}") int threshold,
                          @Value("${shortener.abuse.takedown.window:24h}") Duration window) {
        if (threshold < 2) {
            throw new IllegalArgumentException("shortener.abuse.takedown.threshold must be at least 2, was " + threshold);
        }
        if (!window.isPositive()) {
            throw new IllegalArgumentException("shortener.abuse.takedown.window must be positive, was " + window);
        }
        this.threshold = threshold;
        this.window = window;
        log.info("Links are disabled automatically after abuse reports from {} distinct reporters within {}", threshold, window);
    }

    /**
     * Earliest report time (inclusive) that still counts towards a takedown decided at {@code now}.
     */
    public Instant windowStart(Instant now) {
        return now.minus(window);
    }

    /**
     * @param distinctReporters reporters of the link with a report at or after {@link #windowStart(Instant)}
     */
    public boolean requiresTakedown(long distinctReporters) {
        return distinctReporters >= threshold;
    }
}
