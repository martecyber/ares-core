package com.martecyber.ares.findings;

import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionAsset;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.kb.emailtemplates.PriorityDisplayEntry;
import com.martecyber.ares.references.ReferenceCatalog;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.references.ReferenceEntry;
import com.martecyber.ares.references.ReferenceEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FindingPresentationServiceTest {

    private AffectionRepository affectionRepo;
    private ReferenceEntryRepository referenceEntryRepo;
    private ReferenceCatalogRepository referenceCatalogRepo;
    private FindingPresentationService service;

    @BeforeEach
    void setUp() {
        affectionRepo = mock(AffectionRepository.class);
        referenceEntryRepo = mock(ReferenceEntryRepository.class);
        referenceCatalogRepo = mock(ReferenceCatalogRepository.class);
        service = new FindingPresentationService(affectionRepo, referenceEntryRepo, referenceCatalogRepo);
    }

    @Test
    void severityDisplayUsesTheTemplatesConfiguredEntry() {
        Map<String, Object> display = service.severityDisplay("critical",
            Map.of("critical", new PriorityDisplayEntry("CRÍTICA", "#ef4444")));
        assertEquals("CRÍTICA", display.get("severityLabel"));
        assertEquals("#ef4444", display.get("severityColor"));
    }

    @Test
    void severityDisplayFallsBackToTheBarePCodeAndDefaultPaletteWhenTheTemplateHasNoOverride() {
        assertEquals("P1", service.severityDisplay("high", null).get("severityLabel"));
        assertEquals("#f97316", service.severityDisplay("high", null).get("severityColor"));
        assertEquals("P1", service.severityDisplay("high", Map.of()).get("severityLabel"));
        assertEquals("#f97316", service.severityDisplay("high", Map.of()).get("severityColor"));
    }

    @Test
    void scalarsUsesAqlFieldNames() {
        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(1L);
        when(f.getCode()).thenReturn("F-001");
        when(f.getTitle()).thenReturn("SQL Injection");
        when(f.getSeverity()).thenReturn("critical");
        when(f.getStatusName()).thenReturn("open");

        Map<String, Object> scalars = service.scalars(f);

        assertEquals("F-001", scalars.get("code"));
        assertEquals("SQL Injection", scalars.get("title"));
        assertEquals("P0", scalars.get("priority"));
        assertEquals("open", scalars.get("status"));
        // severityLabel/severityColor are template-specific — see severityDisplay()/flatVars(),
        // not part of the AQL-shaped scalars a specific email template isn't in play for yet.
        assertNull(scalars.get("severityLabel"));
    }

    @Test
    void customFieldsRendersMarkdownDownToPlainText() {
        Finding f = mock(Finding.class);
        when(f.getFields()).thenReturn("{\"impact\":\"**Full** compromise\",\"remediation\":\"\"}");

        Map<String, String> fields = service.customFields(f);

        assertEquals("Full compromise", fields.get("impact"));
        assertEquals("", fields.get("remediation"));
    }

    @Test
    void affectionsIsEmptyWhenThereAreNone() {
        when(affectionRepo.findByFindingIdWithAssets(1L)).thenReturn(List.of());
        assertEquals(List.of(), service.affections(1L));
    }

    @Test
    void affectionsExposesEachAffectionWithItsAssetsSplitByRole() {
        Asset asset = mock(Asset.class);
        when(asset.getCode()).thenReturn("A-1");
        when(asset.getType()).thenReturn("host");
        when(asset.getIdentifier()).thenReturn("host-1.example.com");

        AffectionAsset link = mock(AffectionAsset.class);
        when(link.getRole()).thenReturn("affects");
        when(link.getAssetId()).thenReturn(9L);
        when(link.getAsset()).thenReturn(asset);
        when(link.getStatus()).thenReturn("open");

        Affection a = mock(Affection.class);
        when(a.getCode()).thenReturn("AFF-001");
        when(a.getTitle()).thenReturn("Outdated TLS");
        when(a.getDescription()).thenReturn("Server negotiates TLS 1.0.");
        when(a.getStatus()).thenReturn("open");
        when(a.getAssetLinks()).thenReturn(List.of(link));
        when(affectionRepo.findByFindingIdWithAssets(1L)).thenReturn(List.of(a));

        List<Map<String, Object>> affections = service.affections(1L);

        assertEquals(1, affections.size());
        Map<String, Object> am = affections.get(0);
        assertEquals("AFF-001", am.get("code"));
        assertEquals("Outdated TLS", am.get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affects = (List<Map<String, Object>>) am.get("affects");
        assertEquals(1, affects.size());
        assertEquals("host-1.example.com", affects.get(0).get("identifier"));
        assertEquals("open", affects.get(0).get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> detectedAt = (List<Map<String, Object>>) am.get("detectedAt");
        assertEquals(0, detectedAt.size());
    }

    @Test
    void referencesGroupsByAqlNamespaceIncludingAttackNotAmpersandCk() {
        ReferenceCatalog cveCatalog = mock(ReferenceCatalog.class);
        when(cveCatalog.getId()).thenReturn(1L);
        when(cveCatalog.getCode()).thenReturn("CVE");
        ReferenceCatalog attackCatalog = mock(ReferenceCatalog.class);
        when(attackCatalog.getId()).thenReturn(2L);
        when(attackCatalog.getCode()).thenReturn("ATT&CK");
        when(referenceCatalogRepo.findAll()).thenReturn(List.of(cveCatalog, attackCatalog));

        ReferenceEntry cveRef = mock(ReferenceEntry.class);
        when(cveRef.getCatalogId()).thenReturn(1L);
        when(cveRef.getTitle()).thenReturn("CVE-2024-1234");
        when(cveRef.getDescription()).thenReturn("A vulnerability");
        ReferenceEntry attackRef = mock(ReferenceEntry.class);
        when(attackRef.getCatalogId()).thenReturn(2L);
        when(attackRef.getTitle()).thenReturn("T1190");
        when(referenceEntryRepo.findByFindingId(1L)).thenReturn(List.of(cveRef, attackRef));

        Map<String, List<Map<String, Object>>> refs = service.references(1L);

        assertEquals(2, refs.get("all").size());
        assertEquals(1, refs.get("cve").size());
        assertEquals("CVE-2024-1234", refs.get("cve").get(0).get("title"));
        assertEquals(1, refs.get("attack").size());
        assertEquals("T1190", refs.get("attack").get(0).get("title"));
        assertNull(refs.get("att&ck"));
    }

    @Test
    void referencesAllIsPresentButEmptyWhenThereAreNone() {
        when(referenceEntryRepo.findByFindingId(1L)).thenReturn(List.of());
        when(referenceCatalogRepo.findAll()).thenReturn(List.of());
        Map<String, List<Map<String, Object>>> refs = service.references(1L);
        assertEquals(List.of(), refs.get("all"));
    }

    @Test
    void flatVarsFlattensEverythingUnderFindingPrefix() {
        when(affectionRepo.findByFindingIdWithAssets(1L)).thenReturn(List.of());
        when(referenceEntryRepo.findByFindingId(1L)).thenReturn(List.of());
        when(referenceCatalogRepo.findAll()).thenReturn(List.of());

        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(1L);
        when(f.getCode()).thenReturn("F-001");
        when(f.getTitle()).thenReturn("SQL Injection");
        when(f.getSeverity()).thenReturn("critical");
        when(f.getFields()).thenReturn("{}");

        Map<String, Object> vars = service.flatVars(f, Map.of("critical", new PriorityDisplayEntry("CRÍTICA", "#aa0000")));

        assertEquals("F-001", vars.get("finding.code"));
        assertEquals("SQL Injection", vars.get("finding.title"));
        assertEquals("P0", vars.get("finding.priority"));
        assertEquals("CRÍTICA", vars.get("finding.severityLabel"));
        assertEquals("#aa0000", vars.get("finding.severityColor"));
        assertEquals(List.of(), vars.get("finding.affections"));
        assertEquals(List.of(), vars.get("finding.references"));
    }
}
