package com.martecyber.ares.projects;

import com.martecyber.ares.common.NotFoundException;
import org.springframework.stereotype.Component;

/** Thin adapter exposing {@link ProjectTypeRepository} to plugins as the {@code ares-sdk}-owned
 *  {@link ProjectTypeFacade} — same find-by-code-or-create / soft-disable-only semantics the
 *  bughunting plugin's own (now-removed) {@code BugHuntingProjectTypes} helper used directly. */
@Component
class ProjectTypeFacadeImpl implements ProjectTypeFacade {

    private final ProjectTypeRepository repo;

    ProjectTypeFacadeImpl(ProjectTypeRepository repo) {
        this.repo = repo;
    }

    @Override
    public void ensure(ProjectTypeSpec spec) {
        Long supertypeId = null;
        if (spec.parentCode() != null) {
            supertypeId = repo.findByCode(spec.parentCode())
                .orElseThrow(() -> NotFoundException.of("project type", spec.parentCode()))
                .getId();
        }
        ProjectType t = repo.findByCode(spec.code()).orElseGet(ProjectType::new);
        t.setCode(spec.code());
        t.setName(spec.displayName());
        t.setSupertypeId(supertypeId);
        t.setSystem(true);
        t.setDisabled(false);
        t.setRequiredPluginId(spec.requiredPluginId());
        t.setContinuousNumbering(spec.continuousNumbering());
        t.setDescription(spec.description());
        repo.save(t);
    }

    @Override
    public void disable(String code) {
        repo.findByCode(code).ifPresent(t -> {
            t.setDisabled(true);
            repo.save(t);
        });
    }
}
