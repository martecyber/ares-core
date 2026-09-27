CREATE TABLE ares.kb_wordlist_folder (
    id         BIGSERIAL PRIMARY KEY,
    parent_id  BIGINT REFERENCES ares.kb_wordlist_folder(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ares.kb_wordlist (
    id          BIGSERIAL PRIMARY KEY,
    folder_id   BIGINT REFERENCES ares.kb_wordlist_folder(id) ON DELETE SET NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    object_key  VARCHAR(1024) NOT NULL,
    size_bytes  BIGINT NOT NULL DEFAULT 0,
    sha256      VARCHAR(64),
    line_count  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ
);
CREATE INDEX idx_kb_wordlist_folder ON ares.kb_wordlist(folder_id);
