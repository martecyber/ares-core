SET search_path TO ares, public;

-- Normalise legacy scope kind values to the updated taxonomy.
-- Old kinds no longer in use: ip_range → ip, cloud_resource → other, out_of_scope → other
UPDATE engagement_scope_entry SET kind = 'ip'    WHERE kind = 'ip_range';
UPDATE engagement_scope_entry SET kind = 'other' WHERE kind = 'cloud_resource';
UPDATE engagement_scope_entry SET kind = 'other' WHERE kind = 'out_of_scope';
