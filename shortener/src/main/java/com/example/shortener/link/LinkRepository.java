package com.example.shortener.link;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

// Timestamps are bound and read as OffsetDateTime in UTC: unlike Instant, that is supported by both
// the H2 and PostgreSQL JDBC drivers for TIMESTAMP WITH TIME ZONE columns.
@Repository
public class LinkRepository {

    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts an active link. A {@link org.springframework.dao.DuplicateKeyException} is deliberately
     * left to propagate: it is how callers learn that the code or idempotency key is already taken.
     */
    public ShortLink insert(String code, String targetUrl, Instant createdAt,
                            @Nullable String idempotencyKey, @Nullable String requestHash) {
        OffsetDateTime storedCreatedAt = toUtc(createdAt);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO short_link (code, target_url, status, created_at, idempotency_key, request_hash)
                        VALUES (:code, :targetUrl, :status, :createdAt, :idempotencyKey, :requestHash)
                        """)
                .param("code", code)
                .param("targetUrl", targetUrl)
                .param("status", LinkStatus.ACTIVE.name())
                .param("createdAt", storedCreatedAt)
                .param("idempotencyKey", idempotencyKey)
                .param("requestHash", requestHash)
                .update(keyHolder, "id");
        long id = keyHolder.getKeyAs(Long.class);
        return new ShortLink(id, code, targetUrl, LinkStatus.ACTIVE, storedCreatedAt.toInstant(), null);
    }

    public Optional<ShortLink> findByCode(String code) {
        return jdbc.sql("""
                        SELECT id, code, target_url, status, created_at, disabled_at
                        FROM short_link
                        WHERE code = :code
                        """)
                .param("code", code)
                .query(LinkRepository::mapLink)
                .optional();
    }

    public Optional<IdempotentLink> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("""
                        SELECT id, code, target_url, status, created_at, disabled_at, request_hash
                        FROM short_link
                        WHERE idempotency_key = :idempotencyKey
                        """)
                .param("idempotencyKey", idempotencyKey)
                .query((rs, rowNum) -> new IdempotentLink(mapLink(rs, rowNum), rs.getString("request_hash")))
                .optional();
    }

    /**
     * @return true if the link was active and is now disabled, false if it was already disabled or does not exist
     */
    public boolean disable(String code, Instant disabledAt) {
        int updated = jdbc.sql("""
                        UPDATE short_link
                        SET status = :disabled, disabled_at = :disabledAt
                        WHERE code = :code AND status = :active
                        """)
                .param("disabled", LinkStatus.DISABLED.name())
                .param("disabledAt", toUtc(disabledAt))
                .param("code", code)
                .param("active", LinkStatus.ACTIVE.name())
                .update();
        return updated == 1;
    }

    private static ShortLink mapLink(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime disabledAt = rs.getObject("disabled_at", OffsetDateTime.class);
        return new ShortLink(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("target_url"),
                LinkStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                disabledAt == null ? null : disabledAt.toInstant());
    }

    // The columns keep microseconds and H2 rounds anything finer (Linux clocks have nanoseconds).
    // Truncating first makes the stored value predictable and equal to the link handed back by insert.
    private static OffsetDateTime toUtc(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    public record IdempotentLink(ShortLink link, String requestHash) {
    }
}
