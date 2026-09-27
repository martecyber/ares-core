package com.martecyber.ares.kb.testing;

import java.util.List;

/** Result of a bulk import — best-effort, so a partial success (some files/entries
 *  imported, others reported as errors) is a normal, expected outcome, not a failure. */
public record TestingProcedureImportResult(int imported, List<String> errors) {}
