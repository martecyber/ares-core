package com.martecyber.ares.workflows.templates;

import java.util.List;

/** Result of a bulk import — best-effort, so a partial success (some files/entries imported,
 *  others reported as errors) is a normal, expected outcome, not a failure. */
public record WorkflowTemplateImportResult(int imported, List<String> errors) {}
