package com.martecyber.ares.reporting.dto;

/** Rendered subject/HTML for a finding against one email template — what an actual send would
 *  produce, without sending anything (see {@code FindingEmailReportService#previewForFinding}). */
public record EmailPreviewDto(String subject, String html) {}
