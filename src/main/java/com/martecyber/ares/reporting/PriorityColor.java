package com.martecyber.ares.reporting;

/**
 * Per-priority-level report styling. Colors are 6-digit hex RGB (e.g. "FF0000").
 * {@code label} is the literal text stamped into the generated report for findings of
 * this level — null/blank falls back to the P0-P4 default (see
 * {@link com.martecyber.ares.common.PriorityLabels}). Both the color-replacement pass
 * and the finding's rendered "severity" field key off this same resolved label, so it
 * must stay in sync — see {@code ReportGenerationService.resolvePriorityLabels}.
 */
public record PriorityColor(String bgColor, String textColor, String label) {}
