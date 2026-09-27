SET search_path TO ares, public;

-- Rename published-phase statuses to Spanish
UPDATE finding_status SET name = 'abierto',        description = 'Hallazgo publicado, pendiente de resolución' WHERE name = 'open';
UPDATE finding_status SET name = 'en_revision',    description = 'Bajo análisis del equipo'                   WHERE name = 'in_review';
UPDATE finding_status SET name = 'resuelto',       description = 'Corrección confirmada y verificada'          WHERE name = 'resolved';
UPDATE finding_status SET name = 'riesgo_asumido', description = 'Riesgo aceptado por el cliente'             WHERE name = 'accepted_risk';
UPDATE finding_status SET name = 'falso_positivo', description = 'Confirmado como no es un problema real'      WHERE name = 'false_positive';

-- Add draft-phase statuses
INSERT INTO finding_status (name, description, means_closed) VALUES
    ('redactando',          'Hallazgo en fase de redacción inicial', FALSE),
    ('revision',            'Pendiente de revisión interna',         FALSE),
    ('listo_para_publicar', 'Aprobado y listo para ser publicado',   FALSE);

-- Rebuild transitions for the new workflow
DELETE FROM finding_status_transition;

-- Draft-phase transitions (redactando → revision → listo_para_publicar, with back-steps)
INSERT INTO finding_status_transition (from_status_id, to_status_id) VALUES
    ((SELECT id FROM finding_status WHERE name = 'redactando'),          (SELECT id FROM finding_status WHERE name = 'revision')),
    ((SELECT id FROM finding_status WHERE name = 'revision'),            (SELECT id FROM finding_status WHERE name = 'listo_para_publicar')),
    ((SELECT id FROM finding_status WHERE name = 'revision'),            (SELECT id FROM finding_status WHERE name = 'redactando')),
    ((SELECT id FROM finding_status WHERE name = 'listo_para_publicar'), (SELECT id FROM finding_status WHERE name = 'revision'));

-- Published-phase transitions (abierto → resuelto | riesgo_asumido | falso_positivo)
INSERT INTO finding_status_transition (from_status_id, to_status_id) VALUES
    ((SELECT id FROM finding_status WHERE name = 'abierto'),       (SELECT id FROM finding_status WHERE name = 'resuelto')),
    ((SELECT id FROM finding_status WHERE name = 'abierto'),       (SELECT id FROM finding_status WHERE name = 'riesgo_asumido')),
    ((SELECT id FROM finding_status WHERE name = 'abierto'),       (SELECT id FROM finding_status WHERE name = 'falso_positivo')),
    ((SELECT id FROM finding_status WHERE name = 'resuelto'),      (SELECT id FROM finding_status WHERE name = 'abierto')),
    ((SELECT id FROM finding_status WHERE name = 'riesgo_asumido'),(SELECT id FROM finding_status WHERE name = 'abierto'));

-- All existing findings default to draft=true with 'abierto' status — update any that were created
-- as published (isDraft=false) to keep abierto, and any draft ones to redactando
UPDATE finding SET status_id = (SELECT id FROM finding_status WHERE name = 'redactando')
WHERE is_draft = TRUE
  AND status_id = (SELECT id FROM finding_status WHERE name = 'abierto');
