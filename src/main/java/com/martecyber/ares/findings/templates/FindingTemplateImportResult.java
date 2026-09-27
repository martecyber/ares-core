package com.martecyber.ares.findings.templates;

import java.util.List;

/** Result of a bulk import — best-effort, so a partial success (some files/entries
 *  imported, others reported as errors) is a normal, expected outcome, not a failure. */
public record FindingTemplateImportResult(int imported, List<String> errors) {}
