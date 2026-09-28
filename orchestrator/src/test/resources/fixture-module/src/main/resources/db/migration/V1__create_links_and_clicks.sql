-- Initial schema; statements end with ';' (this one is inside a comment)
CREATE TABLE links (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32)   NOT NULL UNIQUE,
    target_url  VARCHAR(2048) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE clicks (
    id         BIGSERIAL PRIMARY KEY,
    link_id    BIGINT    NOT NULL,
    clicked_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_clicks_link FOREIGN KEY (link_id) REFERENCES links (id) ON DELETE CASCADE
);

CREATE INDEX idx_clicks_link ON clicks (link_id);
