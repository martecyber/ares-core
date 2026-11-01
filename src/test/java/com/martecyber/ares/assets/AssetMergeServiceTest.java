package com.martecyber.ares.assets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.affections.AffectionAssetRepository;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.projects.ProjectAssetAccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Merge field resolution (display name, identifier) and what moves to the surviving asset. */
class AssetMergeServiceTest {

    private AssetRepository repo;
    private AssetMergeService merge;
    private Asset source, target;
    private AssetExternalIdRepository externalIds;

    @BeforeEach
    void setUp() {
        repo = mock(AssetRepository.class);
        merge = new AssetMergeService(repo, mock(AssetRelationshipRepository.class), mock(DetectionRepository.class),
            mock(AffectionAssetRepository.class), mock(ProjectAssetAccessRepository.class),
            mock(AssetService.class), externalIds = mock(AssetExternalIdRepository.class), new ObjectMapper());
        source = asset(1, "Old mail server");
        target = asset(2, null);
        when(repo.findById(1L)).thenReturn(Optional.of(source));
        when(repo.findById(2L)).thenReturn(Optional.of(target));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Asset asset(long id, String displayName) {
        Asset a = new Asset();
        ReflectionTestUtils.setField(a, "id", id);
        a.setOrganizationId(10L);
        a.setType("ip");
        a.setIdentifier("10.0.0." + id);
        a.setMetadata("{}");
        a.setDisplayName(displayName);
        return a;
    }

    @Test
    void unresolvedDisplayNameKeepsTheSourcesWhenTargetHasNone() {
        Asset merged = merge.merge(1L, new AssetMergeService.MergeRequest(2L, List.of()));
        assertEquals("Old mail server", merged.getDisplayName());
        assertFalse(merged.getMetadata().contains("displayName"));
    }

    @Test
    void explicitResolutionWins() {
        target.setDisplayName("Mail");
        Asset merged = merge.merge(1L, new AssetMergeService.MergeRequest(2L, List.of(
            new AssetMergeService.FieldResolution("displayName", "custom", "  Primary MX  "))));
        assertEquals("Primary MX", merged.getDisplayName());

        target.setDisplayName("Mail");
        merged = merge.merge(1L, new AssetMergeService.MergeRequest(2L, List.of(
            new AssetMergeService.FieldResolution("displayName", "target", null))));
        assertEquals("Mail", merged.getDisplayName());
    }

    @Test
    void typedIdentifierIsOnlyAllowedForHosts() {
        assertThrows(IllegalArgumentException.class, () -> merge.merge(1L, new AssetMergeService.MergeRequest(2L, List.of(
            new AssetMergeService.FieldResolution("identifier", "custom", "10.9.9.9")))));

        Asset merged = merge.merge(1L, new AssetMergeService.MergeRequest(2L, List.of(
            new AssetMergeService.FieldResolution("identifier", "source", null))));
        assertEquals("10.0.0.1", merged.getIdentifier());
    }

    @Test
    void toolIdsMoveToTheSurvivingAsset() {
        merge.merge(1L, new AssetMergeService.MergeRequest(2L, List.of()));
        verify(externalIds).reassign(1L, 2L);
    }
}
