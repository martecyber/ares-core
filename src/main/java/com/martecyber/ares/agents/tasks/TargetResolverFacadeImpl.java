package com.martecyber.ares.agents.tasks;

import org.springframework.stereotype.Component;

import java.util.List;

/** Thin adapter exposing {@link TargetResolver} to plugins as the {@code ares-sdk}-owned {@link
 *  TargetResolverFacade}. */
@Component
class TargetResolverFacadeImpl implements TargetResolverFacade {

    private final TargetResolver targetResolver;

    TargetResolverFacadeImpl(TargetResolver targetResolver) {
        this.targetResolver = targetResolver;
    }

    @Override
    public List<String> resolveTargets(Long projectId, Object selector) {
        return targetResolver.resolveUnlimited(projectId, selector);
    }
}
