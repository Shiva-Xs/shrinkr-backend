CREATE TABLE short_links (
    id              BIGSERIAL       PRIMARY KEY,
    slug            VARCHAR(10)     NOT NULL,
    original_url    TEXT            NOT NULL,
    scan_status     VARCHAR(10)     NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMP,
    max_clicks      INTEGER,
    click_count     INTEGER         NOT NULL DEFAULT 0,
    password_hash   VARCHAR(255),
    delete_token    VARCHAR(255)    NOT NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_slug       ON short_links(slug);
CREATE INDEX idx_expires_at        ON short_links(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_created_at        ON short_links(created_at);
