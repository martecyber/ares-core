package com.martecyber.ares.imports;

import com.martecyber.ares.assets.*;
import com.martecyber.ares.projects.ProjectAssetAccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A source tool's own asset id resolves re-imports to the same asset, for any type, per organization. */
class AssetImportHelperExternalIdTest {

    private static final long ORG = 10L;

    private AssetRepository assets;
    private AssetExternalIdRepository externalIds;
    private AssetImportHelper helper;

    @BeforeEach
    void setUp() {
        assets = mock(AssetRepository.class);
        externalIds = mock(AssetExternalIdRepository.class);
        helper = new AssetImportHelper(assets, mock(AssetRelationshipRepository.class), mock(AssetService.class),
            mock(ProjectAssetAccessRepository.class), mock(ScanImportChangeRepository.class), externalIds);
        when(assets.findByOrganizationIdAndTypeAndIdentifier(any(), any(), any())).thenReturn(Optional.empty());
        when(externalIds.findByOrganizationIdAndToolAndExternalId(any(), any(), any())).thenReturn(Optional.empty());
        when(assets.save(any())).thenAnswer(inv -> {
            Asset a = inv.getArgument(0);
            if (a.getId() == null) ReflectionTestUtils.setField(a, "id", 500L);
            return a;
        });
    }

    private static ParsedAsset withToolId(String identifier, String type, String tool, String id) {
        return new ParsedAsset(identifier, type, Map.of(
            AssetMetadataKeys.EXTERNAL_ID_TOOL_KEY, tool, AssetMetadataKeys.EXTERNAL_ID_VALUE_KEY, id, "source", "x"));
    }

    private static Asset asset(long id, long org, String type, String identifier) {
        Asset a = new Asset();
        ReflectionTestUtils.setField(a, "id", id);
        a.setOrganizationId(org);
        a.setType(type);
        a.setIdentifier(identifier);
        return a;
    }

    private static AssetExternalId mapping(long assetId) {
        AssetExternalId m = new AssetExternalId();
        m.setAssetId(assetId);
        m.setOrganizationId(ORG);
        return m;
    }

    @Test
    void knownToolIdWinsOverAChangedNaturalKey() {
        when(externalIds.findByOrganizationIdAndToolAndExternalId(ORG, "fortirecon", "d-42")).thenReturn(Optional.of(mapping(7L)));
        when(assets.findById(7L)).thenReturn(Optional.of(asset(7, ORG, AssetType.DOMAIN, "old.example.com")));

        assertEquals(7L, helper.resolveOrCreate(ORG, withToolId("new.example.com", AssetType.DOMAIN, "fortirecon", "d-42")));
        verify(assets, never()).save(any());
    }

    @Test
    void newAssetIsCreatedWithoutTheReservedKeysAndItsToolIdIsRecorded() {
        Long id = helper.resolveOrCreate(ORG, withToolId("example.com", AssetType.DOMAIN, "fortirecon", "d-1"));

        ArgumentCaptor<Asset> saved = ArgumentCaptor.forClass(Asset.class);
        verify(assets).save(saved.capture());
        assertFalse(saved.getValue().getMetadata().contains(AssetMetadataKeys.EXTERNAL_ID_TOOL_KEY));
        assertTrue(saved.getValue().getMetadata().contains("source"));

        ArgumentCaptor<AssetExternalId> m = ArgumentCaptor.forClass(AssetExternalId.class);
        verify(externalIds).save(m.capture());
        assertEquals(List.of(id, ORG, "fortirecon", "d-1"),
            List.of(m.getValue().getAssetId(), m.getValue().getOrganizationId(), m.getValue().getTool(), m.getValue().getExternalId()));
    }

    @Test
    void existingAssetFoundByIdentifierGetsTheToolIdRecorded() {
        when(assets.findByOrganizationIdAndTypeAndIdentifier(ORG, AssetType.IP, "203.0.113.4"))
            .thenReturn(Optional.of(asset(9, ORG, AssetType.IP, "203.0.113.4")));

        assertEquals(9L, helper.resolveOrCreate(ORG, withToolId("203.0.113.4", AssetType.IP, "fortirecon", "ip-9")));
        verify(externalIds).save(argThat(m -> m.getAssetId() == 9L && m.getOrganizationId() == ORG));
    }

    @Test
    void aMappingToAnotherTypeIsIgnored() {
        when(externalIds.findByOrganizationIdAndToolAndExternalId(ORG, "tool", "1")).thenReturn(Optional.of(mapping(7L)));
        when(assets.findById(7L)).thenReturn(Optional.of(asset(7, ORG, AssetType.IP, "10.0.0.1")));

        assertEquals(500L, helper.resolveOrCreate(ORG, withToolId("example.com", AssetType.DOMAIN, "tool", "1")));
    }
}
