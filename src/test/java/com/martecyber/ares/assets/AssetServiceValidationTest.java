package com.martecyber.ares.assets;

import com.martecyber.ares.aql.compile.AqlVariableExpander;
import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.AssetScopeClassifier;
import com.martecyber.ares.projects.ScopeClassifyScheduler;
import com.martecyber.ares.tags.TagRepository;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Manual-entry validation in {@link AssetService#create} / {@link AssetService#update}. */
class AssetServiceValidationTest {

    private AssetRepository repo;
    private OrgScopeService orgScope;
    private AssetService service;

    @BeforeEach
    void setUp() {
        repo = mock(AssetRepository.class);
        service = new AssetService(repo, mock(AssetRelationshipService.class), mock(AssetRelationshipRepository.class),
            mock(OrganizationRepository.class), mock(AssetScopeClassifier.class), mock(ScopeClassifyScheduler.class),
            mock(DetectionRepository.class), mock(AssetTagRepository.class),
            mock(TagRepository.class), orgScope = mock(OrgScopeService.class), mock(AssetAqlRegistry.class),
            mock(WorkflowEventDispatcher.class), mock(AqlVariableExpander.class));
        when(repo.save(any())).thenAnswer(inv -> {
            Asset a = inv.getArgument(0);
            if (a.getId() == null) ReflectionTestUtils.setField(a, "id", 99L);
            return a;
        });
        when(repo.findByOrganizationIdAndTypeAndIdentifier(any(), any(), any())).thenReturn(Optional.empty());
    }

    private static Asset asset(long id, String type, String identifier) {
        Asset a = new Asset();
        ReflectionTestUtils.setField(a, "id", id);
        a.setOrganizationId(1L);
        a.setType(type);
        a.setIdentifier(identifier);
        a.setCode("ACME-X-" + id);
        return a;
    }

    @Test
    void createStoresNormalizedIdentifier() {
        service.create(1L, "ACME-D-1", "domain", "  Example.COM. ", null);
        ArgumentCaptor<Asset> saved = ArgumentCaptor.forClass(Asset.class);
        verify(repo).save(saved.capture());
        assertEquals("example.com", saved.getValue().getIdentifier());
    }

    @Test
    void createRejectsUnknownTypeAndMalformedIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> service.create(1L, "C", "location", "x", null));
        assertThrows(IllegalArgumentException.class, () -> service.create(1L, "C", "ip", "999.1.1.1", null));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsDuplicateButNotForHosts() {
        when(repo.findByOrganizationIdAndTypeAndIdentifier(1L, "domain", "example.com"))
            .thenReturn(Optional.of(asset(5, "domain", "example.com")));
        assertThrows(ConflictException.class, () -> service.create(1L, "C", "domain", "EXAMPLE.com", null));

        when(repo.findByOrganizationIdAndTypeAndIdentifier(1L, "host", "web01"))
            .thenReturn(Optional.of(asset(6, "host", "web01")));
        assertDoesNotThrow(() -> service.create(1L, "C", "host", "web01", null));
    }

    @Test
    void updateRejectsTypeChange() {
        when(repo.findById(5L)).thenReturn(Optional.of(asset(5, "domain", "example.com")));
        assertThrows(IllegalArgumentException.class, () -> service.update(5L, "ip", null, null));
        assertDoesNotThrow(() -> service.update(5L, "domain", null, null)); // same type is a no-op
    }

    @Test
    void updateRejectsIdentifierChangesOnImportKeyedTypes() {
        Asset self = asset(5, "domain", "example.com");
        when(repo.findById(5L)).thenReturn(Optional.of(self));
        when(repo.findByOrganizationIdAndTypeAndIdentifier(1L, "domain", "example.com")).thenReturn(Optional.of(self));
        assertThrows(IllegalArgumentException.class, () -> service.update(5L, null, "not a domain", null));
        assertThrows(IllegalArgumentException.class, () -> service.update(5L, null, "other.com", null));
        assertEquals("example.com", self.getIdentifier());

        // Re-saving the same identifier (any casing) isn't a change.
        assertEquals("example.com", service.update(5L, null, "EXAMPLE.com", null).getIdentifier());
    }

    @Test
    void editableIdentifierTypesStillValidateAndDedupe() {
        Asset app = asset(6, "web_application", "https://a.example.com");
        when(repo.findById(6L)).thenReturn(Optional.of(app));
        when(repo.findByOrganizationIdAndTypeAndIdentifier(1L, "web_application", "https://b.example.com"))
            .thenReturn(Optional.of(asset(7, "web_application", "https://b.example.com")));
        assertThrows(ConflictException.class, () -> service.update(6L, null, "https://b.example.com", null));
        assertEquals("https://c.example.com", service.update(6L, null, "https://c.example.com", null).getIdentifier());

        Asset host = asset(8, "host", "web01");
        when(repo.findById(8L)).thenReturn(Optional.of(host));
        assertEquals("web02", service.update(8L, null, "web02", null).getIdentifier());
    }

    @Test
    void bulkAssertAccessChecksEveryOwningOrg() {
        when(repo.findDistinctOrganizationIdsByIdIn(List.of(1L, 2L))).thenReturn(List.of(10L, 20L));
        doThrow(new org.springframework.security.access.AccessDeniedException("no"))
            .when(orgScope).assertOrgAccess(any(), eq(20L));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> service.assertAccess(List.of(1L, 2L)));
        verify(orgScope).assertOrgAccess(any(), eq(10L));
    }

    @Test
    void bulkAssertAccessSkipsLookupForPlatformAdmin() {
        when(orgScope.isPlatformAdmin(any())).thenReturn(true);
        service.assertAccess(List.of(1L, 2L));
        verify(repo, never()).findDistinctOrganizationIdsByIdIn(any());
    }

    // ── Display name ─────────────────────────────────────────────────────

    @Test
    void createStoresTrimmedDisplayNameAndLeavesTheIdentifierAlone() {
        Asset a = service.create(1L, "ACME-I-1", "ip", "10.0.0.25", null, null, null, "  Mail server  ");
        assertEquals("Mail server", a.getDisplayName());
        assertEquals("10.0.0.25", a.getIdentifier());
    }

    @Test
    void updateSetsClearsOrKeepsTheDisplayName() {
        Asset a = asset(5, "domain", "example.com");
        when(repo.findById(5L)).thenReturn(Optional.of(a));

        service.update(5L, null, null, null, null, null, null, "Corporate site");
        assertEquals("Corporate site", a.getDisplayName());

        service.update(5L, null, null, "{}", null, null, null, null);
        assertEquals("Corporate site", a.getDisplayName(), "null = don't touch");

        service.update(5L, null, null, null, null, null, null, "   ");
        assertNull(a.getDisplayName(), "blank = clear");
        assertEquals("example.com", a.getIdentifier());
    }

    @Test
    void metadataMustBeAJsonObject() {
        Asset a = asset(5, "domain", "example.com");
        when(repo.findById(5L)).thenReturn(Optional.of(a));
        assertThrows(IllegalArgumentException.class, () -> service.update(5L, null, null, "not json"));
        assertThrows(IllegalArgumentException.class, () -> service.update(5L, null, null, "[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> service.create(1L, "C", "domain", "x.com", "\"str\""));

        assertEquals("{\"note\":\"x\"}", service.update(5L, null, null, "{\"note\":\"x\"}").getMetadata());
        assertNull(service.update(5L, null, null, "  ").getMetadata(), "blank = no metadata");
    }

    @Test
    void displayNameRejectsControlCharsAndOverlongValues() {
        assertThrows(IllegalArgumentException.class, () -> AssetIdentifierRules.normalizeDisplayName("a\nb"));
        assertThrows(IllegalArgumentException.class,
            () -> AssetIdentifierRules.normalizeDisplayName("x".repeat(AssetIdentifierRules.DISPLAY_NAME_MAX_LENGTH + 1)));
    }
}
