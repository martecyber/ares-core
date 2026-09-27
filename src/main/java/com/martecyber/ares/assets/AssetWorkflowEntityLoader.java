package com.martecyber.ares.assets;

import com.martecyber.ares.workflows.WorkflowEntityLoader;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Lets a Workflow CONDITION node bind to and evaluate against a live {@link Asset} — see
 *  {@link com.martecyber.ares.workflows.WorkflowEntityLookup}. */
@Component
public class AssetWorkflowEntityLoader implements WorkflowEntityLoader<Asset> {

    private final AssetRepository repo;

    public AssetWorkflowEntityLoader(AssetRepository repo) {
        this.repo = repo;
    }

    @Override
    public String entityType() {
        return "asset";
    }

    @Override
    public Optional<Asset> load(Long id) {
        return repo.findById(id);
    }
}
