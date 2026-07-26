CREATE INDEX idx_is_active_false ON short_links(slug) WHERE is_active = FALSE;
ALTER TABLE short_links
    ADD CONSTRAINT chk_original_url_length CHECK (char_length(original_url) <= 2048);
