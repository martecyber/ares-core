package com.martecyber.ares.findings;

import com.martecyber.ares.assets.AssetAqlRegistry;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.registry.AqlFieldKind;
import com.martecyber.ares.aql.registry.AqlFieldType;
import com.martecyber.ares.aql.registry.FieldDefinitionRepository;
import com.martecyber.ares.detections.DetectionAqlRegistry;
import com.martecyber.ares.detections.DetectionStatusRepository;
import com.martecyber.ares.kb.cve.CveAqlRegistry;
import com.martecyber.ares.references.ReferenceCatalog;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure Mockito unit test — no Spring context, no DB, mirrors WorkflowGraphValidatorTest's style.
 *  Only checks field registration shape; actual query resolution for KB_FEDERATED fields needs
 *  live Mongo data and is covered by manual live verification instead (same convention already
 *  established for Detection's own cve.* fields — see PostgresSpecificationCompilerDetectionIT).
 *  Builds every registry through a real {@link AqlRegistryLookup} (not FindingAqlRegistry alone)
 *  since the "cve.<leaf>"/"detections.<leaf>"/etc. flat entries only get built by its
 *  post-construction expandRelations pass (AQL-wide initiative, Phase 2/3) — constructing
 *  FindingAqlRegistry standalone would leave every relation field bare/unexpanded. */
class FindingAqlRegistryTest {

    private static final List<String> ALL_CATALOG_CODES = List.of("CVE", "CWE", "CAPEC", "OWASP", "ATT&CK");

    private FindingAqlRegistry registryWithAllCatalogsSeeded() {
        var fieldDefinitionRepo = mock(FieldDefinitionRepository.class);
        when(fieldDefinitionRepo.findByEntityTypeAndOrganizationIdIsNullOrderByAssetTypeAscSortOrderAsc("finding"))
            .thenReturn(List.of());
        when(fieldDefinitionRepo.findByEntityTypeAndOrganizationIdIsNullOrderByAssetTypeAscSortOrderAsc("asset"))
            .thenReturn(List.of());
        var statusRepo = mock(FindingStatusRepository.class);
        when(statusRepo.findAll()).thenReturn(List.of());
        var referenceCatalogRepo = mock(ReferenceCatalogRepository.class);
        for (String code : ALL_CATALOG_CODES) {
            when(referenceCatalogRepo.findByCode(code)).thenReturn(Optional.of(mock(ReferenceCatalog.class)));
        }
        var detectionStatusRepo = mock(DetectionStatusRepository.class);
        when(detectionStatusRepo.findAllByOrderByIdAsc()).thenReturn(List.of());

        var findingRegistry = new FindingAqlRegistry(fieldDefinitionRepo, statusRepo, referenceCatalogRepo);
        var detectionRegistry = new DetectionAqlRegistry(referenceCatalogRepo, detectionStatusRepo);
        var assetRegistry = new AssetAqlRegistry(fieldDefinitionRepo);
        var cveRegistry = new CveAqlRegistry();
        var cveKevDetailRegistry = new com.martecyber.ares.kb.kev.CveKevDetailAqlRegistry();
        var owaspRegistry = new com.martecyber.ares.kb.owasp.OwaspAqlRegistry();
        var cweRegistry = new com.martecyber.ares.kb.cwe.CweAqlRegistry();
        var capecRegistry = new com.martecyber.ares.kb.capec.CapecAqlRegistry();
        var attackRegistry = new com.martecyber.ares.kb.attack.AttackAqlRegistry();
        var attackTacticRegistry = new com.martecyber.ares.kb.attack.AttackTacticAqlRegistry();
        var attackMitigationRegistry = new com.martecyber.ares.kb.attack.AttackMitigationAqlRegistry();
        // expandRelations loops over every RelationAqlField a registry holds — Finding also has
        // detections/affectedAssets/detectedAtAssets (Phase 2), not just cve (Phase 3), so all
        // target registries need to be present or expansion throws "Unknown AQL entity". CveAqlRegistry
        // itself now also has a "kev" relation (Phase 4), unconditionally registered (no
        // catalog-seeded guard), so CveKevDetailAqlRegistry has to be present here too even though
        // this test never queries cve.kev.* directly. OwaspAqlRegistry/CweAqlRegistry/
        // CapecAqlRegistry/AttackAqlRegistry are needed the same way now that owasp.*/cwe.*/
        // capec.*/attack.* are all RelationAqlFields (Phase 5 complete), guarded by the mocked
        // "OWASP"/"CWE"/"CAPEC"/"ATT&CK" catalogs above. AttackTacticAqlRegistry/
        // AttackMitigationAqlRegistry are needed too since AttackAqlRegistry itself now has
        // "tactics"/"mitigations" relations of its own.
        new AqlRegistryLookup(List.of(findingRegistry, detectionRegistry, assetRegistry, cveRegistry,
            cveKevDetailRegistry, owaspRegistry, cweRegistry, capecRegistry, attackRegistry,
            attackTacticRegistry, attackMitigationRegistry, new com.martecyber.ares.tags.TagAqlRegistry(),
            new com.martecyber.ares.projects.ProjectAssetAccessAqlRegistry()));
        return findingRegistry;
    }

    @Test
    void ownPrimaryKeyIsRegisteredAsAPhysicalColumnField() {
        var registry = registryWithAllCatalogsSeeded();
        var field = registry.field("id").orElseThrow();
        assertEquals(AqlFieldKind.PHYSICAL_COLUMN, field.kind());
        assertEquals(AqlFieldType.NUMBER, field.type());
    }

    @Test
    void cveIdIsRegisteredAsARelationLeafWhenCatalogIsSeeded() {
        var registry = registryWithAllCatalogsSeeded();
        var field = registry.field("cve.id").orElseThrow();
        assertEquals(AqlFieldKind.RELATION, field.kind());
        assertEquals(AqlFieldType.STRING, field.type());
    }

    @Test
    void cveDescriptionPublishedAtAndLastModifiedAtAreAlsoRegistered() {
        var registry = registryWithAllCatalogsSeeded();
        assertTrue(registry.field("cve.description").isPresent());
        assertTrue(registry.field("cve.publishedAt").isPresent());
        assertTrue(registry.field("cve.lastModifiedAt").isPresent());
    }

    /** kevListed/cvssScore/severity/exploitCount used to be a separate KB_MATERIALIZED kind
     *  (pre-Phase-3) — now that CVE lives in Postgres, they're just ordinary leaves of the same
     *  "cve" RELATION, same as every other cve.* field, no special casing left. */
    @Test
    void formerlyMaterializedCveFieldsAreNowPlainRelationLeaves() {
        var registry = registryWithAllCatalogsSeeded();
        for (String name : List.of("cve.kevListed", "cve.cvssScore", "cve.severity", "cve.exploitCount")) {
            var field = registry.field(name).orElseThrow(() -> new AssertionError(name + " not registered"));
            assertEquals(AqlFieldKind.RELATION, field.kind());
        }
    }

    @Test
    void everyNamespaceExposesItsOwnIdentifierFieldMatchingTheStandaloneRegistry() {
        var registry = registryWithAllCatalogsSeeded();
        assertTrue(registry.field("cve.id").isPresent());
        assertTrue(registry.field("cwe.id").isPresent());
        assertTrue(registry.field("capec.id").isPresent());
        assertTrue(registry.field("owasp.id").isPresent());
        assertTrue(registry.field("attack.id").isPresent());
    }

    @Test
    void cweCapecOwaspAttackSubFieldsAreRegistered() {
        var registry = registryWithAllCatalogsSeeded();
        assertTrue(registry.field("cwe.name").isPresent());
        assertTrue(registry.field("cwe.likelihoodOfExploit").isPresent());
        assertTrue(registry.field("capec.typicalSeverity").isPresent());
        assertTrue(registry.field("owasp.rank").isPresent());
        assertTrue(registry.field("attack.subtechnique").isPresent());
    }

    @Test
    void relationalNestingFieldsFromPhase2AreAlsoExpanded() {
        var registry = registryWithAllCatalogsSeeded();
        assertTrue(registry.field("detections.title").isPresent());
        assertTrue(registry.field("affectedAssets.identifier").isPresent());
        assertTrue(registry.field("detectedAtAssets.identifier").isPresent());
    }

    @Test
    void bareCveRelationFieldIsAbsentWhenCveCatalogIsntSeeded() {
        var fieldDefinitionRepo = mock(FieldDefinitionRepository.class);
        when(fieldDefinitionRepo.findByEntityTypeAndOrganizationIdIsNullOrderByAssetTypeAscSortOrderAsc("finding"))
            .thenReturn(List.of());
        var statusRepo = mock(FindingStatusRepository.class);
        when(statusRepo.findAll()).thenReturn(List.of());
        var referenceCatalogRepo = mock(ReferenceCatalogRepository.class);
        when(referenceCatalogRepo.findByCode("CVE")).thenReturn(Optional.empty());

        var registry = new FindingAqlRegistry(fieldDefinitionRepo, statusRepo, referenceCatalogRepo);
        // Checked pre-expansion (the bare "cve" RelationAqlField, gated at construction time by
        // the catalog-seeded guard) rather than "cve.id" — the flat leaf only ever exists
        // after AqlRegistryLookup's expansion pass runs, so it'd be empty here regardless of
        // whether the catalog is seeded, which wouldn't actually be testing the guard.
        assertTrue(registry.field("cve").isEmpty());
    }
}
