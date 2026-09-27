-- Seeds real, already-populated SERVICE metadata keys as AQL-queryable field_definition rows —
-- port/protocol are written by every port-scan importer (NmapXMLParser, ScannerParserUtils#emitService
-- shared by Naabu et al.), product/version only by Nmap's -sV service/version detection. Surfaces
-- as service.port / service.protocol / service.product / service.version once AssetAqlRegistry
-- starts asset-type-prefixing field_definition rows that carry a non-null asset_type (see that
-- class). "service" itself (the free-text service-name/banner guess, e.g. "http") is deliberately
-- NOT seeded here — its natural field_key equals its own asset_type ("service"), which would
-- surface as the stuttering "service.service"; left for a follow-up once the registry can decouple
-- a field's AQL-facing name from its jsonb key.
INSERT INTO ares.field_definition (entity_type, asset_type, field_key, title, data_type, allowed_values, sort_order, is_system, created_at)
VALUES
    ('asset', 'service', 'port', 'Port', 'number', NULL, 10, TRUE, NOW()),
    ('asset', 'service', 'protocol', 'Protocol', 'enum', '["tcp", "udp"]'::jsonb, 20, TRUE, NOW()),
    ('asset', 'service', 'product', 'Product', 'string', NULL, 30, TRUE, NOW()),
    ('asset', 'service', 'version', 'Version', 'string', NULL, 40, TRUE, NOW());
