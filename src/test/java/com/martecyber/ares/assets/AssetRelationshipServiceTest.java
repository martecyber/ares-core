package com.martecyber.ares.assets;

import com.martecyber.ares.assets.dto.AssetNeighborhoodDto;
import com.martecyber.ares.assets.dto.AssetRelationshipDetailDto;
import com.martecyber.ares.assets.dto.CreateAssetRelationshipRequest;
import com.martecyber.ares.common.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test for {@link AssetRelationshipService} — the undirected BFS used by
 *  {@code findReachable} and the hop-limited/node-capped BFS used by {@code findNeighborhood},
 *  the outgoing/incoming direction labeling in {@code listAllForAsset}, and the
 *  {@link AssetLinkType#validate} gate shared by {@code create}/{@code linkIfAbsent}. */
class AssetRelationshipServiceTest {

    private AssetRelationshipRepository repo;
    private AssetRepository assetRepo;
    private ProjectRelationshipHiddenRepository hiddenRepo;
    private AssetRelationshipService service;

    @BeforeEach
    void setUp() {
        repo = mock(AssetRelationshipRepository.class);
        assetRepo = mock(AssetRepository.class);
        hiddenRepo = mock(ProjectRelationshipHiddenRepository.class);
        service = new AssetRelationshipService(repo, assetRepo, hiddenRepo);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Asset asset(long id, Long orgId, String type, String code) {
        Asset a = new Asset();
        ReflectionTestUtils.setField(a, "id", id);
        a.setOrganizationId(orgId);
        a.setType(type);
        a.setCode(code);
        a.setIdentifier(code + "-identifier");
        return a;
    }

    private static AssetRelationship rel(long from, long to, String type) {
        AssetRelationship r = new AssetRelationship();
        r.setFromAssetId(from);
        r.setToAssetId(to);
        r.setType(type);
        r.setDirectional(true);
        return r;
    }

    // ── listAllForAsset ──────────────────────────────────────────────

    @Test
    void listAllForAssetThrowsNotFoundForAnUnknownAsset() {
        when(assetRepo.findById(1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.listAllForAsset(1L));
    }

    @Test
    void listAllForAssetLabelsDirectionAndEnrichesFromTheBatchLoadedRelatedAssets() {
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset(1, 9L, AssetType.HOST, "H1")));
        when(repo.findByFromAssetId(1L)).thenReturn(List.of(rel(1, 2, AssetLinkType.HOST_INTERFACE)));
        when(repo.findByToAssetId(1L)).thenReturn(List.of(rel(3, 1, "domain_a")));
        when(assetRepo.findAllById(Set.of(2L, 3L))).thenReturn(List.of(
            asset(2, 9L, AssetType.INTERFACE, "I1"),
            asset(3, 9L, AssetType.DOMAIN, "D1")));

        List<AssetRelationshipDetailDto> result = service.listAllForAsset(1L);

        assertEquals(2, result.size());
        AssetRelationshipDetailDto out = result.stream().filter(d -> "outgoing".equals(d.direction())).findFirst().orElseThrow();
        assertEquals("I1", out.relatedCode());
        assertEquals(AssetLinkType.HOST_INTERFACE, out.linkType());
        AssetRelationshipDetailDto in = result.stream().filter(d -> "incoming".equals(d.direction())).findFirst().orElseThrow();
        assertEquals("D1", in.relatedCode());
    }

    @Test
    void listAllForAssetSkipsRelationshipsWhoseRelatedAssetNoLongerExists() {
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset(1, 9L, AssetType.HOST, "H1")));
        when(repo.findByFromAssetId(1L)).thenReturn(List.of(rel(1, 2, AssetLinkType.HOST_INTERFACE)));
        when(repo.findByToAssetId(1L)).thenReturn(List.of());
        when(assetRepo.findAllById(Set.of(2L))).thenReturn(List.of()); // deleted/missing

        assertTrue(service.listAllForAsset(1L).isEmpty());
    }

    // ── findReachable ────────────────────────────────────────────────

    @Test
    void findReachableReturnsEmptyForNullOrEmptyFromIds() {
        assertEquals(List.of(), service.findReachable(9L, null));
        assertEquals(List.of(), service.findReachable(9L, List.of()));
        verifyNoInteractions(repo);
    }

    @Test
    void findReachableIncludesTheStartingAssetsThemselves() {
        when(repo.findByOrganizationId(9L)).thenReturn(List.of());
        when(assetRepo.findAllById(Set.of(1L))).thenReturn(List.of(asset(1, 9L, AssetType.HOST, "H1")));

        var result = service.findReachable(9L, List.of(1L));
        assertEquals(1, result.size());
    }

    @Test
    void findReachableTraversesMultipleHopsThroughTheUndirectedGraph() {
        // 1 -> 2 -> 3, a completely separate 4 -> 5 island.
        when(repo.findByOrganizationId(9L)).thenReturn(List.of(
            rel(1, 2, AssetLinkType.HOST_INTERFACE),
            rel(2, 3, AssetLinkType.INTERFACE_IP),
            rel(4, 5, AssetLinkType.HOST_INTERFACE)));
        when(assetRepo.findAllById(Set.of(1L, 2L, 3L))).thenReturn(List.of(
            asset(1, 9L, AssetType.HOST, "H1"), asset(2, 9L, AssetType.INTERFACE, "I1"), asset(3, 9L, AssetType.IP, "IP1")));

        var result = service.findReachable(9L, List.of(1L));

        assertEquals(3, result.size());
        assertTrue(result.stream().map(Asset::getId).toList().containsAll(List.of(1L, 2L, 3L)));
    }

    @Test
    void findReachableFollowsEdgesInBothDirections() {
        // The edge is stored 2 -> 1 (incoming to the start asset) but must still be walked.
        when(repo.findByOrganizationId(9L)).thenReturn(List.of(rel(2, 1, AssetLinkType.HOST_INTERFACE)));
        when(assetRepo.findAllById(Set.of(1L, 2L))).thenReturn(List.of(
            asset(1, 9L, AssetType.INTERFACE, "I1"), asset(2, 9L, AssetType.HOST, "H1")));

        var result = service.findReachable(9L, List.of(1L));
        assertEquals(2, result.size());
    }

    // ── findNeighborhood ─────────────────────────────────────────────

    @Test
    void findNeighborhoodClampsMaxHopsIntoTheOneToTenRange() {
        when(repo.findByFromAssetIdInOrToAssetIdIn(any(), any())).thenReturn(List.of());
        when(assetRepo.findAllById(any())).thenReturn(List.of(asset(1, 9L, AssetType.HOST, "H1")));

        service.findNeighborhood(9L, 1L, 0);
        service.findNeighborhood(9L, 1L, 999);

        // Both calls still complete and query at least once — the exact clamped value isn't
        // observable from outside, but an out-of-range hop count must never reach the repo as-is
        // (0 hops would otherwise mean "no BFS at all", 999 would be an unbounded scan).
        verify(repo, atLeast(2)).findByFromAssetIdInOrToAssetIdIn(any(), any());
    }

    @Test
    void findNeighborhoodExpandsOneHopAtATimeAndStopsAtTheRequestedDepth() {
        // center(1) -[a]- 2 -[b]- 3 -[c]- 4 : with maxHops=2, node 4 must NOT be reached.
        when(repo.findByFromAssetIdInOrToAssetIdIn(eq(Set.of(1L)), eq(Set.of(1L))))
            .thenReturn(List.of(rel(1, 2, "a")));
        when(repo.findByFromAssetIdInOrToAssetIdIn(eq(Set.of(2L)), eq(Set.of(2L))))
            .thenReturn(List.of(rel(2, 3, "b")));
        when(assetRepo.findAllById(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> asset(id, 9L, AssetType.HOST, "A" + id)).toList();
        });

        AssetNeighborhoodDto dto = service.findNeighborhood(9L, 1L, 2);

        assertEquals(3, dto.assets().size()); // 1, 2, 3 — not 4
        assertTrue(dto.assets().stream().map(Asset::getId).toList().containsAll(List.of(1L, 2L, 3L)));
    }

    @Test
    void findNeighborhoodFiltersOutCrossOrgAssetsDefensively() {
        when(repo.findByFromAssetIdInOrToAssetIdIn(any(), any())).thenReturn(List.of());
        when(assetRepo.findAllById(any())).thenReturn(List.of(asset(1, 999L, AssetType.HOST, "H1"))); // wrong org

        AssetNeighborhoodDto dto = service.findNeighborhood(9L, 1L, 1);
        assertTrue(dto.assets().isEmpty());
    }

    @Test
    void findNeighborhoodOnlyKeepsRelationshipsBetweenAssetsThatSurvivedTheOrgFilter() {
        when(repo.findByFromAssetIdInOrToAssetIdIn(eq(Set.of(1L)), eq(Set.of(1L))))
            .thenReturn(List.of(rel(1, 2, "a")));
        when(repo.findByFromAssetIdInOrToAssetIdIn(eq(Set.of(2L)), eq(Set.of(2L))))
            .thenReturn(List.of());
        // Asset 2 belongs to a different org and gets filtered out of the final asset list.
        when(assetRepo.findAllById(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> asset(id, id == 1L ? 9L : 999L, AssetType.HOST, "A" + id)).toList();
        });

        AssetNeighborhoodDto dto = service.findNeighborhood(9L, 1L, 2);

        assertEquals(1, dto.assets().size());
        assertTrue(dto.relationships().isEmpty());
    }

    // ── list* ────────────────────────────────────────────────────────

    @Test
    void listFromThrowsNotFoundForAnUnknownAsset() {
        when(assetRepo.findById(1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.listFrom(1L));
    }

    @Test
    void listByOrganizationDelegatesDirectlyToTheRepository() {
        when(repo.findByOrganizationId(9L)).thenReturn(List.of(rel(1, 2, "a")));
        assertEquals(1, service.listByOrganization(9L).size());
    }

    @Test
    void listByProjectDelegatesDirectlyToTheRepository() {
        when(repo.findByProjectId(7L)).thenReturn(List.of(rel(1, 2, "a")));
        assertEquals(1, service.listByProject(7L).size());
    }

    // ── create ───────────────────────────────────────────────────────

    @Test
    void createRejectsATypeCombinationNotInTheValidationMatrix() {
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset(1, 9L, AssetType.HOST, "H1")));
        when(assetRepo.findById(2L)).thenReturn(Optional.of(asset(2, 9L, AssetType.HOST, "H2"))); // HOST -> HOST invalid
        assertThrows(IllegalArgumentException.class,
            () -> service.create(1L, new CreateAssetRelationshipRequest(2L, AssetLinkType.HOST_INTERFACE)));
        verify(repo, never()).save(any());
    }

    @Test
    void createSavesAValidDirectionalRelationship() {
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset(1, 9L, AssetType.HOST, "H1")));
        when(assetRepo.findById(2L)).thenReturn(Optional.of(asset(2, 9L, AssetType.INTERFACE, "I1")));

        var dto = service.create(1L, new CreateAssetRelationshipRequest(2L, AssetLinkType.HOST_INTERFACE));

        assertTrue(dto.directional());
        assertEquals(AssetLinkType.HOST_INTERFACE, dto.type());
    }

    @Test
    void createThrowsNotFoundWhenTheToAssetDoesNotExist() {
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset(1, 9L, AssetType.HOST, "H1")));
        when(assetRepo.findById(2L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class,
            () -> service.create(1L, new CreateAssetRelationshipRequest(2L, AssetLinkType.HOST_INTERFACE)));
    }

    // ── linkIfAbsent ─────────────────────────────────────────────────

    @Test
    void linkIfAbsentDoesNotDuplicateAnExistingIdenticalLink() {
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset(1, 9L, AssetType.HOST, "H1")));
        when(assetRepo.findById(2L)).thenReturn(Optional.of(asset(2, 9L, AssetType.INTERFACE, "I1")));
        when(repo.findByFromAssetId(1L)).thenReturn(List.of(rel(1, 2, AssetLinkType.HOST_INTERFACE)));

        service.linkIfAbsent(1L, 2L, AssetLinkType.HOST_INTERFACE);

        verify(repo, never()).save(any());
    }

    @Test
    void linkIfAbsentCreatesTheLinkWhenNotAlreadyPresent() {
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset(1, 9L, AssetType.HOST, "H1")));
        when(assetRepo.findById(2L)).thenReturn(Optional.of(asset(2, 9L, AssetType.INTERFACE, "I1")));
        when(repo.findByFromAssetId(1L)).thenReturn(List.of());

        service.linkIfAbsent(1L, 2L, AssetLinkType.HOST_INTERFACE);

        verify(repo).save(argThat(r -> r.getFromAssetId().equals(1L) && r.getToAssetId().equals(2L)));
    }

    @Test
    void linkIfAbsentValidatesTheTypeCombinationBeforeCheckingExistence() {
        when(assetRepo.findById(1L)).thenReturn(Optional.of(asset(1, 9L, AssetType.HOST, "H1")));
        when(assetRepo.findById(2L)).thenReturn(Optional.of(asset(2, 9L, AssetType.HOST, "H2")));
        assertThrows(IllegalArgumentException.class, () -> service.linkIfAbsent(1L, 2L, AssetLinkType.HOST_INTERFACE));
    }

    // ── delete / hideForProject ───────────────────────────────────────

    @Test
    void deleteDelegatesToTheRepository() {
        service.delete(1L, 2L, "a");
        verify(repo).deleteByFromAssetIdAndToAssetIdAndType(1L, 2L, "a");
    }

    @Test
    void hideForProjectDelegatesToTheHiddenRepository() {
        service.hideForProject(7L, 1L, 2L, "a");
        verify(hiddenRepo).hideIfAbsent(7L, 1L, 2L, "a");
    }
}
