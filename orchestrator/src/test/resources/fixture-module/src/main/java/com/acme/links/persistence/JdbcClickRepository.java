package com.acme.links.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcClickRepository implements ClickRepository {

    private final JdbcClient jdbc;

    JdbcClickRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(long linkId) {
        jdbc.sql("INSERT INTO clicks (link_id, clicked_at) VALUES (?, now())").param(linkId).update();
    }

    @Override
    public long countFor(String code) {
        return jdbc.sql("SELECT count(*) FROM clicks c "
                        + "JOIN links l ON l.id = c.link_id "
                        + "WHERE l.code = ?")
                .param(code)
                .query(Long.class)
                .single();
    }
}
