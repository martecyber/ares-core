-- Sub-classification for host assets (PC, Server, VM, Container, Router, Firewall,
-- Switch, WiFi AP, Other, Unknown). NULL for every non-host asset; existing and new
-- hosts default to 'unknown' when not manually classified. No CHECK constraint —
-- validated in the application layer, same convention as detection.source_type.
ALTER TABLE ares.asset ADD COLUMN host_subtype VARCHAR(20);
UPDATE ares.asset SET host_subtype = 'unknown' WHERE type = 'host';
