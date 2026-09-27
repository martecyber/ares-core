-- Bugcrowd and YesWeHack's public API is customer/company-facing (Program Owner
-- account) rather than something an individual hacker/researcher account has access
-- to, so a project can no longer be scoped as a "Bug Hunting — Bugcrowd/YesWeHack"
-- programme from the researcher perspective. Their connection setup lives under Data
-- Sources now (marked "coming soon" there, frontend-only — see INTEGRATION_TYPES in
-- ares-ui/src/api/tool-integrations.ts) but the project subtype itself is disabled —
-- Intigriti and HackerOne both expose a real researcher token and stay enabled.
UPDATE ares.project_type SET disabled = TRUE WHERE code IN ('BH_BC', 'BH_YWH');
