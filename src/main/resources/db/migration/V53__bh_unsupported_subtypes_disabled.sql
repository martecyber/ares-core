-- Bugcrowd, YesWeHack and Intigriti BH subtypes are not yet operational
UPDATE ares.engagement_type SET disabled = TRUE WHERE code IN ('BH_BC', 'BH_YWH', 'BH_INTG');
