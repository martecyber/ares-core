-- AQL implementation plan, Phase 0/2: hot-field mirror of Knowledge Base (Mongo) entries onto
-- Postgres, so AQL queries combining a Postgres entity (Detection/Finding) with a KB property
-- (e.g. "detections whose CVE is KEV-listed") compile to a plain join instead of a live Mongo
-- round trip. Lazily populated — only entries actually referenced from Postgres (via
-- reference_entry) get a row here, not the full synced KB corpus — so first-query-ever on a
-- never-before-referenced CVE still needs the federated fallback (Phase 2); this table is a
-- speed optimization for the common case, not a hard requirement for correctness.
--
-- code matches reference_entry.title for the same catalog_id (e.g. "CVE-2024-1234") — see
-- ReferenceEntry.java / DetectionReferenceExtractor.link().

CREATE TABLE ares.kb_materialized_ref (
    catalog_id    BIGINT NOT NULL REFERENCES ares.reference_catalog(id),
    code          VARCHAR(100) NOT NULL,
    kev_listed    BOOLEAN,
    cvss_score    NUMERIC(3,1),
    severity      VARCHAR(15),
    exploit_count INT,
    synced_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (catalog_id, code)
);

CREATE INDEX ix_kb_materialized_ref_kev ON ares.kb_materialized_ref(kev_listed) WHERE kev_listed = true;
