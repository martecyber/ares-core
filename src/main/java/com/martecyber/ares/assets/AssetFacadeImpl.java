package com.martecyber.ares.assets;

import com.martecyber.ares.imports.AssetImportHelper;
import com.martecyber.ares.imports.ParsedAsset;
import com.martecyber.ares.projects.ProjectAssetAccessRepository;
import org.springframework.stereotype.Component;

/** Thin adapter exposing {@link AssetImportHelper}/{@link ProjectAssetAccessRepository}/{@link
 *  AssetToolSightingService} to plugins as the {@code ares-sdk}-owned {@link AssetFacade}. */
@Component
class AssetFacadeImpl implements AssetFacade {

    private final AssetImportHelper assetImportHelper;
    private final ProjectAssetAccessRepository projectAssetAccessRepository;
    private final AssetToolSightingService assetToolSightingService;

    AssetFacadeImpl(AssetImportHelper assetImportHelper,
                    ProjectAssetAccessRepository projectAssetAccessRepository,
                    AssetToolSightingService assetToolSightingService) {
        this.assetImportHelper = assetImportHelper;
        this.projectAssetAccessRepository = projectAssetAccessRepository;
        this.assetToolSightingService = assetToolSightingService;
    }

    @Override
    public Long resolveOrCreate(Long organizationId, ParsedAsset asset) {
        return assetImportHelper.resolveOrCreate(organizationId, asset);
    }

    @Override
    public void linkIfAbsent(Long fromAssetId, Long toAssetId, String linkType) {
        assetImportHelper.linkIfAbsent(fromAssetId, toAssetId, linkType);
    }

    @Override
    public void ensureWebApplicationTree(Long organizationId, Long projectId, Long webAppAssetId, String webAppUrl) {
        assetImportHelper.ensureWebApplicationTree(organizationId, projectId, webAppAssetId, webAppUrl);
    }

    @Override
    public void grantProjectAccess(Long projectId, Long assetId) {
        projectAssetAccessRepository.linkIfAbsent(projectId, assetId);
    }

    @Override
    public void recordToolSighting(Long assetId, Long projectId, String tool) {
        assetToolSightingService.upsert(assetId, projectId, tool);
    }
}
