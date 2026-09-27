package com.martecyber.ares.findings;

import com.martecyber.ares.workflows.WorkflowEntityLoader;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Lets a Workflow CONDITION node bind to and evaluate against a live {@link Finding} — see
 *  {@link com.martecyber.ares.workflows.WorkflowEntityLookup}. */
@Component
public class FindingWorkflowEntityLoader implements WorkflowEntityLoader<Finding> {

    private final FindingRepository repo;

    public FindingWorkflowEntityLoader(FindingRepository repo) {
        this.repo = repo;
    }

    @Override
    public String entityType() {
        return "finding";
    }

    @Override
    public Optional<Finding> load(Long id) {
        return repo.findById(id);
    }
}
