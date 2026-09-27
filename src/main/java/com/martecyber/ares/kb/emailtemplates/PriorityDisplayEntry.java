package com.martecyber.ares.kb.emailtemplates;

/**
 * One severity level's display override on an {@link EmailTemplate} — {@code label} (falls back
 * to the bare P0-P4 code, see {@code PriorityLabels}) and {@code color} (a {@code #rrggbb} hex,
 * falls back to the same palette {@code SeverityTag.vue}/{@code ares-ui/src/utils/cvss.ts} use
 * everywhere else in the app). Mirrors {@code ReportTemplate.priorityColors} — DOCX report
 * templates' own per-template severity styling — but as a single color rather than a bg/text
 * pair, since an email template's severity badge is plain HTML/CSS, not a Word cell fill.
 * Resolved into the {@code finding.severityLabel}/{@code finding.severityColor} template
 * variables by {@code FindingPresentationService#severityDisplay}, keyed by raw severity
 * ({@code critical}/{@code high}/{@code medium}/{@code low}/{@code info}) — not by P0-P4 — to
 * match {@code ReportTemplate.priorityColors}'s own key convention.
 */
public record PriorityDisplayEntry(String label, String color) {}
