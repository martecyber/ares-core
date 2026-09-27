package com.martecyber.ares.projects;

import com.martecyber.ares.projects.dto.ScopeEntryDto;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Thin adapter exposing {@link ProjectScopeEntryRepository} to plugins as the {@code
 *  ares-sdk}-owned {@link ScopeFacade} — {@link #upsert}'s find-or-create/content-changed
 *  semantics are the same ones {@code BugHuntingProgramService.upsertScopeEntry} used to
 *  implement itself directly against the repository; moved here so they're genuinely {@code
 *  @Transactional} (a plugin-loaded class can never be the target of a Spring AOP proxy). */
@Component
class ScopeFacadeImpl implements ScopeFacade {

    private final ProjectScopeEntryRepository repo;

    ScopeFacadeImpl(ProjectScopeEntryRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public ScopeEntryDto upsert(Long projectId, String source, String externalId, String kind, String value,
                                String notes, boolean inScope, OffsetDateTime platformCreatedAt,
                                OffsetDateTime platformUpdatedAt, String metadata) {
        OffsetDateTime now = OffsetDateTime.now();
        boolean[] isNew = {false};
        ProjectScopeEntry entry = repo.findByProjectIdAndSourceAndExternalId(projectId, source, externalId)
            .orElseGet(() -> {
                isNew[0] = true;
                ProjectScopeEntry e = new ProjectScopeEntry();
                e.setProjectId(projectId);
                e.setSource(source);
                e.setExternalId(externalId);
                e.setCreatedAt(now);
                e.setPlatformCreatedAt(platformCreatedAt);
                return e;
            });

        String normalizedValue = ScopeNormalizer.ensureUrlScheme(kind, value);

        boolean contentChanged = !Objects.equals(entry.getKind(), kind)
            || !Objects.equals(entry.getValue(), normalizedValue)
            || !Objects.equals(entry.getNotes(), notes)
            || entry.isInScope() != inScope;

        entry.setKind(kind);
        entry.setValue(normalizedValue);
        entry.setNotes(notes);
        entry.setInScope(inScope);
        entry.setMetadata(metadata);
        entry.setPlatformUpdatedAt(platformUpdatedAt);

        if (isNew[0] || contentChanged) {
            entry.setUpdatedAt(now);
        }

        return ProjectService.toScopeDto(repo.save(entry));
    }

    @Override
    public List<ScopeEntryDto> listAll(Long projectId) {
        return repo.findByProjectIdOrderByCreatedAtAsc(projectId).stream().map(ProjectService::toScopeDto).toList();
    }

    @Override
    @Transactional
    public void deleteObsolete(Long projectId, String source, Set<String> activeExternalIds) {
        repo.findByProjectIdAndSource(projectId, source).stream()
            .filter(e -> e.getExternalId() != null && !activeExternalIds.contains(e.getExternalId()))
            .forEach(repo::delete);
    }

    @Override
    public void deleteAllExceptSource(Long projectId, String keepSource) {
        repo.deleteByProjectIdAndSourceNot(projectId, keepSource);
    }
}
