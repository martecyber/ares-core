CREATE TABLE ares.kb_wordlist_repo (
    id                  BIGSERIAL PRIMARY KEY,
    repo_url            VARCHAR(1024) NOT NULL,
    branch              VARCHAR(255)  NOT NULL DEFAULT 'main',
    path_filter         VARCHAR(1024),
    github_token        VARCHAR(4096),
    auto_sync           BOOLEAN       NOT NULL DEFAULT FALSE,
    sync_interval_hours INT           NOT NULL DEFAULT 24,
    last_sync_at        TIMESTAMPTZ,
    last_sync_status    VARCHAR(32),
    last_sync_error     TEXT,
    file_count          INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

ALTER TABLE ares.kb_wordlist
    ADD COLUMN source_repo_id   BIGINT REFERENCES ares.kb_wordlist_repo(id) ON DELETE SET NULL,
    ADD COLUMN source_repo_path VARCHAR(1024),
    ADD COLUMN source_repo_sha1 VARCHAR(40);

CREATE INDEX idx_kb_wordlist_source_repo ON ares.kb_wordlist(source_repo_id);
CREATE UNIQUE INDEX idx_kb_wordlist_repo_path
    ON ares.kb_wordlist(source_repo_id, source_repo_path)
    WHERE source_repo_id IS NOT NULL;
