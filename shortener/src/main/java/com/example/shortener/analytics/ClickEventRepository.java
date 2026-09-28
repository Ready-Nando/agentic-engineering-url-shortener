package com.example.shortener.analytics;

import com.example.shortener.analytics.LinkStats.DailyClicks;
import com.example.shortener.analytics.LinkStats.ReferrerCount;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All time ranges are half-open: {@code from} inclusive, {@code to} exclusive.
 */
@Repository
public class ClickEventRepository {

    private final JdbcClient jdbc;

    public ClickEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ClickEvent event) {
        jdbc.sql("""
                        INSERT INTO click_event (link_id, occurred_at, referrer_host)
                        VALUES (:linkId, :occurredAt, :referrerHost)
                        """)
                .param("linkId", event.linkId())
                .param("occurredAt", toUtc(event.occurredAt()))
                .param("referrerHost", event.referrerHost())
                .update();
    }

    public long countByLink(long linkId) {
        return jdbc.sql("SELECT COUNT(*) FROM click_event WHERE link_id = :linkId")
                .param("linkId", linkId)
                .query(Long.class)
                .single();
    }

    public long countByLinkBetween(long linkId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM click_event
                        WHERE link_id = :linkId AND occurred_at >= :from AND occurred_at < :to
                        """)
                .param("linkId", linkId)
                .param("from", toUtc(from))
                .param("to", toUtc(to))
                .query(Long.class)
                .single();
    }

    /**
     * Click counts per UTC calendar day. Days without clicks are absent from the result.
     */
    public List<DailyClicks> dailyCounts(long linkId, Instant from, Instant to) {
        // Buckets by days since the Unix epoch rather than CAST(... AS DATE): casting a TIMESTAMP WITH
        // TIME ZONE to DATE uses the session time zone (in H2 and PostgreSQL), whereas epoch seconds are
        // UTC by definition, so UTC days come out right whatever the server or JVM time zone is.
        return jdbc.sql("""
                        SELECT FLOOR(EXTRACT(EPOCH FROM occurred_at) / 86400) AS epoch_day, COUNT(*) AS clicks
                        FROM click_event
                        WHERE link_id = :linkId AND occurred_at >= :from AND occurred_at < :to
                        GROUP BY FLOOR(EXTRACT(EPOCH FROM occurred_at) / 86400)
                        ORDER BY epoch_day
                        """)
                .param("linkId", linkId)
                .param("from", toUtc(from))
                .param("to", toUtc(to))
                .query((rs, rowNum) -> new DailyClicks(LocalDate.ofEpochDay(rs.getLong("epoch_day")), rs.getLong("clicks")))
                .list();
    }

    /**
     * Most frequent referrer hosts, ties broken alphabetically so results are stable. Clicks without
     * a referrer are not counted.
     */
    public List<ReferrerCount> topReferrers(long linkId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT referrer_host, COUNT(*) AS clicks
                        FROM click_event
                        WHERE link_id = :linkId AND occurred_at >= :from AND occurred_at < :to
                          AND referrer_host IS NOT NULL
                        GROUP BY referrer_host
                        ORDER BY clicks DESC, referrer_host ASC
                        LIMIT :limit
                        """)
                .param("linkId", linkId)
                .param("from", toUtc(from))
                .param("to", toUtc(to))
                .param("limit", limit)
                .query((rs, rowNum) -> new ReferrerCount(rs.getString("referrer_host"), rs.getLong("clicks")))
                .list();
    }

    public Optional<Instant> lastClickAt(long linkId) {
        return jdbc.sql("""
                        SELECT occurred_at FROM click_event
                        WHERE link_id = :linkId
                        ORDER BY occurred_at DESC
                        LIMIT 1
                        """)
                .param("linkId", linkId)
                .query((rs, rowNum) -> rs.getObject("occurred_at", OffsetDateTime.class).toInstant())
                .optional();
    }

    // Truncated rather than left to H2, which rounds to the column's microseconds and could push a
    // click at 23:59:59.9999999 into the next day.
    private static OffsetDateTime toUtc(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
