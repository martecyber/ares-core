package com.martecyber.ares.detections;

import com.martecyber.ares.workflows.WorkflowEntityLoader;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Lets a Workflow CONDITION node bind to and evaluate against a live {@link Detection} — see
 *  {@link com.martecyber.ares.workflows.WorkflowEntityLookup}. Finding/Asset have their own
 *  sibling loaders ({@code FindingWorkflowEntityLoader}/{@code AssetWorkflowEntityLoader}). */
@Component
public class DetectionWorkflowEntityLoader implements WorkflowEntityLoader<Detection> {

    private final DetectionRepository repo;

    public DetectionWorkflowEntityLoader(DetectionRepository repo) {
        this.repo = repo;
    }

    @Override
    public String entityType() {
        return "detection";
    }

    @Override
    public Optional<Detection> load(Long id) {
        return repo.findById(id);
    }
}
