package com.example.shortener.abuse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AbuseReportRepository {

    private final JdbcClient jdbc;

    public AbuseReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores a report. A reporter who already reported the link keeps their single row, refreshed with the new
     * reason and time, so every reporter counts once however often they report.
     * <p>
     * The unique constraint decides whether the reporter is new, which also holds for concurrent reports. Do not
     * call this inside a transaction: PostgreSQL aborts the transaction after the failed INSERT.
     */
    public void save(AbuseReport report) {
        try {
            bind(jdbc.sql("""
                    INSERT INTO abuse_report (link_id, reporter_hash, reason, reported_at)
                    VALUES (:linkId, :reporterHash, :reason, :reportedAt)
                    """), report).update();
        } catch (DuplicateKeyException e) {
            bind(jdbc.sql("""
                    UPDATE abuse_report SET reason = :reason, reported_at = :reportedAt
                    WHERE link_id = :linkId AND reporter_hash = :reporterHash
                    """), report).update();
        }
    }

    /**
     * Distinct reporters whose report of the link is at or after {@code since}. Rows are unique per reporter
     * ({@code uk_abuse_report_link_id_reporter_hash}), so counting rows counts reporters.
     */
    public long countReportersSince(long linkId, Instant since) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM abuse_report
                        WHERE link_id = :linkId AND reported_at >= :since
                        """)
                .param("linkId", linkId)
                .param("since", toUtc(since))
                .query(Long.class)
                .single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, AbuseReport report) {
        return statement
                .param("linkId", report.linkId())
                .param("reporterHash", report.reporterHash())
                .param("reason", report.reason().name())
                .param("reportedAt", toUtc(report.reportedAt()));
    }

    // Truncated to the column's microseconds, as in the other repositories, so stored values are predictable.
    private static OffsetDateTime toUtc(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
