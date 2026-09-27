package com.martecyber.ares.projects;

import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.projects.dto.ScopeStatusDto;
import jakarta.transaction.Transactional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProjectAssetAccessService {

    private final ProjectAssetAccessRepository repo;
    private final ProjectRepository projects;
    private final AssetRepository assets;
    private final AssetScopeClassifier classifier;
    private final ScopeClassifyScheduler scheduler;

    public ProjectAssetAccessService(ProjectAssetAccessRepository repo,
                                        ProjectRepository projects,
                                        AssetRepository assets,
                                        @Lazy AssetScopeClassifier classifier,
                                        @Lazy ScopeClassifyScheduler scheduler) {
        this.repo = repo;
        this.projects = projects;
        this.assets = assets;
        this.classifier = classifier;
        this.scheduler = scheduler;
    }

    private void scheduleAfterCommit(Long projectId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { scheduler.schedule(projectId); }
            });
        } else {
            scheduler.schedule(projectId);
        }
    }

    public List<Long> listAssetIds(Long projectId) {
        projects.findById(projectId)
            .orElseThrow(() -> NotFoundException.of("project", projectId));
        return repo.findByProjectId(projectId).stream()
            .map(ProjectAssetAccess::getAssetId).toList();
    }

    /** Returns scope status for every asset in the project, keyed by assetId. */
    public Map<Long, ScopeStatusDto> getScopeStatuses(Long projectId) {
        return repo.findByProjectId(projectId).stream()
            .collect(Collectors.toMap(
                ProjectAssetAccess::getAssetId,
                a -> new ScopeStatusDto(a.getScopeStatus(), a.isScopeOverride())
            ));
    }

    @Transactional
    public void add(Long projectId, Long assetId) {
        projects.findById(projectId)
            .orElseThrow(() -> NotFoundException.of("project", projectId));
        assets.findById(assetId).orElseThrow(() -> NotFoundException.of("asset", assetId));

        if (!repo.existsByProjectIdAndAssetId(projectId, assetId)) {
            ProjectAssetAccess e = new ProjectAssetAccess();
            e.setProjectId(projectId);
            e.setAssetId(assetId);
            repo.save(e);
        }
        scheduleAfterCommit(projectId);
    }

    @Transactional
    public void remove(Long projectId, Long assetId) {
        repo.deleteByProjectIdAndAssetId(projectId, assetId);
    }

    /** Sets a manual override on scope status and re-runs the classifier so neighbours inherit it. */
    @Transactional
    public ScopeStatusDto setOverride(Long projectId, Long assetId, String status) {
        ProjectAssetAccess access = repo.findByProjectId(projectId).stream()
            .filter(a -> a.getAssetId().equals(assetId))
            .findFirst()
            .orElseThrow(() -> NotFoundException.of("project_asset_access", assetId));
        access.setScopeStatus(status);
        access.setScopeOverride(true);
        repo.save(access);
        scheduleAfterCommit(projectId);
        return new ScopeStatusDto(status, true);
    }

    /** Clears a manual override and re-runs the classifier so neighbours are re-evaluated. */
    @Transactional
    public void clearOverride(Long projectId, Long assetId) {
        ProjectAssetAccess access = repo.findByProjectId(projectId).stream()
            .filter(a -> a.getAssetId().equals(assetId))
            .findFirst()
            .orElseThrow(() -> NotFoundException.of("project_asset_access", assetId));
        access.setScopeOverride(false);
        repo.save(access);
        scheduleAfterCommit(projectId);
    }
}
