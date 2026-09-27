SET search_path TO ares, public;

-- V28 renamed the published-phase statuses to Spanish and added the draft-phase ones directly in
-- Spanish. The rest of the application is English-only — restore the original English names for
-- the published-phase statuses and give the draft-phase ones (which never had an English name)
-- proper English names too. Renaming the row in place (not inserting new rows) preserves every
-- existing finding_status_id/finding_status_transition/finding.status_id reference untouched.
UPDATE finding_status SET name = 'open',          description = 'Finding published, pending resolution'   WHERE name = 'abierto';
UPDATE finding_status SET name = 'in_review',      description = 'Under review by the team'                WHERE name = 'en_revision';
UPDATE finding_status SET name = 'resolved',       description = 'Fix confirmed and verified'               WHERE name = 'resuelto';
UPDATE finding_status SET name = 'accepted_risk',  description = 'Risk accepted by the client'               WHERE name = 'riesgo_asumido';
UPDATE finding_status SET name = 'false_positive', description = 'Confirmed as not a real issue'             WHERE name = 'falso_positivo';

UPDATE finding_status SET name = 'drafting',       description = 'Finding in initial drafting'               WHERE name = 'redactando';
UPDATE finding_status SET name = 'pending_review', description = 'Pending internal review'                   WHERE name = 'revision';
UPDATE finding_status SET name = 'ready_to_publish', description = 'Approved and ready to be published'      WHERE name = 'listo_para_publicar';
