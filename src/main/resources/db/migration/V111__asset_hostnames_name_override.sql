-- Splits HOST asset identity into two concepts: `hostnames` (an ordered list of every
-- hostname ever observed for the host — tools only ever append to it) and the existing
-- `identifier` column reinterpreted as a user-owned "Name" for hosts, auto-derived from
-- hostnames[0] (or a virtual "host-{ip}" fallback) unless `name_override` is set.
--
-- This fixes duplicate HOST assets being created whenever a scan tool reports a
-- different hostname for the same physical host — identity now resolves through the
-- already-stable HOST_INTERFACE->INTERFACE_IP chain instead of through hostname text.
ALTER TABLE ares.asset ADD COLUMN hostnames jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE ares.asset ADD COLUMN name_override boolean NOT NULL DEFAULT false;

-- Backfill existing hosts: seed hostnames from the old single-value metadata.hostname
-- key (superseded by the new column), and strip that key since it's no longer read.
UPDATE ares.asset
SET hostnames = jsonb_build_array(metadata->>'hostname'),
    metadata  = metadata - 'hostname'
WHERE type = 'host' AND metadata->>'hostname' IS NOT NULL;

-- Pin every pre-existing host's current identifier so this migration never silently
-- renames an asset that already has a specific identifier from prior scan history —
-- only new/newly-resolved hosts get the auto-naming behavior going forward. Admins can
-- clear this per-host later to opt into auto-naming.
UPDATE ares.asset SET name_override = true WHERE type = 'host';
