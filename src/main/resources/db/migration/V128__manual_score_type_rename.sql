-- The "Textual" score type (a free-form 0-10 slider) is now "Manual" — a direct P0-P4
-- pick instead. Renaming in place keeps every existing finding_score/finding_template_score
-- row (and its typeId FK) intact; only the type's display title changes.
UPDATE finding_score_type SET title = 'Manual', description = 'Manually assessed priority (P0-P4)'
WHERE title = 'Textual';
