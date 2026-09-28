package com.acme.links.persistence;

import com.acme.links.domain.Link;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LinkRepository {

    private static final String SELECT_LINK = """
            SELECT id, code, target_url, created_at
              FROM links
             WHERE code = :code
            """;

    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Link insert(String code, String targetUrl) {
        jdbc.sql("""
                INSERT INTO links (code, target_url, created_at)
                VALUES (:code, :targetUrl, now())
                """)
            .param("code", code)
            .param("targetUrl", targetUrl)
            .update();
        return findByCode(code).orElseThrow();
    }

    public Optional<Link> findByCode(String code) {
        return jdbc.sql(SELECT_LINK).param("code", code).query(Link.class).optional();
    }

    public void deleteByCode(String code) {
        jdbc.sql("DELETE FROM links WHERE code = :code").param("code", code).update();
    }

    public void incrementHits(String code) {
        jdbc.sql("UPDATE links SET hit_count = hit_count + 1 WHERE code = :code").param("code", code).update();
    }
}
