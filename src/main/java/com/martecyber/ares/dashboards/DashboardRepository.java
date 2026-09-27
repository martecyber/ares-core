package com.martecyber.ares.dashboards;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DashboardRepository extends JpaRepository<Dashboard, Long> {

    // IsTemplateFalse everywhere below: scope_id is already null for real PLATFORM dashboards,
    // and a template ALSO always has scope_id null (any level) — without this filter, a PLATFORM
    // template would be indistinguishable from the real platform dashboard(s) in these lookups.
    List<Dashboard> findByLevelAndScopeIdAndIsTemplateFalseOrderByIsDefaultDescNameAsc(DashboardLevel level, Long scopeId);

    Optional<Dashboard> findByLevelAndScopeIdAndIsTemplateFalseAndIsDefaultTrue(DashboardLevel level, Long scopeId);

    long countByLevelAndScopeIdAndIsTemplateFalse(DashboardLevel level, Long scopeId);

    List<Dashboard> findByLevelAndIsTemplateTrueOrderByNameAsc(DashboardLevel level);

    Optional<Dashboard> findByLevelAndIsTemplateTrueAndNameIgnoreCase(DashboardLevel level, String name);
}
