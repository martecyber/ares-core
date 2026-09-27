-- KEV moves from two separate Mongo collections (kb_cisa_kev, kb_vulncheck_kev) to one unified
-- Postgres table (AQL-wide initiative, Phase 4). "source" discriminates 'cisa' vs 'vulncheck' —
-- a single (cve_id, source) namespace matches how the AQL surface exposes this (cve.kev.source,
-- cve.kev.detail...) rather than two separate cve.cisaKev.*/cve.vulncheckKev.* namespaces.
--
-- VulnCheck's feed lists one record per multi-CVE group (VulnCheckKevEntry.cve is a List) — each
-- such record becomes N rows here, one per CVE, keyed (cve_id, 'vulncheck') same as CISA's
-- naturally-one-CVE-per-entry shape. No information is lost: nothing downstream ever needed to
-- know which other CVEs originally shared a VulnCheck entry.
--
-- Deliberately NOT a foreign key to ares.cve(cve_id): the old Mongo collections had no such
-- coupling, and a hard FK here would make the KEV sync fail (or require per-row upsert-order
-- coordination) whenever a feed lists a CVE ID our own CVE corpus hasn't synced yet — a real,
-- not-hypothetical timing case since the two syncs run independently on their own schedules.

CREATE TABLE ares.cve_kev_detail (
    id                              BIGSERIAL PRIMARY KEY,
    cve_id                          TEXT NOT NULL,
    source                          TEXT NOT NULL,
    vendor_project                  TEXT,
    product                         TEXT,
    vulnerability_name              TEXT,
    short_description               TEXT,
    required_action                 TEXT,
    due_date                        DATE,
    date_added                      DATE,
    cisa_date_added                 DATE,
    known_ransomware_campaign_use   BOOLEAN NOT NULL DEFAULT FALSE,
    reported_exploited_by_canaries  BOOLEAN,
    cwes                            TEXT[] NOT NULL DEFAULT '{}',
    xdb_urls                        TEXT[] NOT NULL DEFAULT '{}',
    reported_exploitation_urls      TEXT[] NOT NULL DEFAULT '{}',
    notes                           TEXT,
    synced_at                       TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_cve_kev_detail_cve_source ON ares.cve_kev_detail(cve_id, source);
CREATE INDEX ix_cve_kev_detail_cve_id ON ares.cve_kev_detail(cve_id);
CREATE INDEX ix_cve_kev_detail_source ON ares.cve_kev_detail(source);
CREATE INDEX ix_cve_kev_detail_synced_at ON ares.cve_kev_detail(synced_at);
CREATE INDEX ix_cve_kev_detail_known_ransomware ON ares.cve_kev_detail(known_ransomware_campaign_use);
