SET search_path TO ares, public;

-- Add missing en_revision transitions for the published phase.
-- V28 deleted all transitions and rebuilt them, but omitted en_revision
-- in the published workflow, leaving findings in that state orphaned
-- (no outgoing transitions → impossible to close → SLA tracking broken).

INSERT INTO finding_status_transition (from_status_id, to_status_id) VALUES
    ((SELECT id FROM finding_status WHERE name = 'abierto'),     (SELECT id FROM finding_status WHERE name = 'en_revision')),
    ((SELECT id FROM finding_status WHERE name = 'en_revision'), (SELECT id FROM finding_status WHERE name = 'abierto')),
    ((SELECT id FROM finding_status WHERE name = 'en_revision'), (SELECT id FROM finding_status WHERE name = 'resuelto')),
    ((SELECT id FROM finding_status WHERE name = 'en_revision'), (SELECT id FROM finding_status WHERE name = 'riesgo_asumido')),
    ((SELECT id FROM finding_status WHERE name = 'en_revision'), (SELECT id FROM finding_status WHERE name = 'falso_positivo'));
