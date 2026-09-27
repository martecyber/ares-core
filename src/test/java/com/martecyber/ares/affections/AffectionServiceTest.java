package com.martecyber.ares.affections;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingService;
import com.martecyber.ares.findings.dto.FindingDto;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.users.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test for {@link AffectionService} — the detected_at/affects asset-link
 *  bookkeeping (including the "III model" per-detected_at affects links and the legacy
 *  affection_asset rows derived from them), affect-status history recording, and detection
 *  linking. No Spring context; {@code SecurityContextHolder} carries a real authentication
 *  token so {@code currentUserId()}/{@code currentUserName()} exercise their real parsing. */
class AffectionServiceTest {

    private AffectionRepository repo;
    private AffectionAssetRepository assetLinkRepo;
    private AffectionAffectsLinkRepository affectsLinkRepo;
    private AffectStatusHistoryRepository historyRepo;
    private FindingRepository findingRepo;
    private DetectionRepository detectionRepo;
    private AssetRepository assetRepo;
    private UserRepository userRepo;
    private FindingService findingService;
    private OrgScopeService orgScope;
    private AffectionService service;

    @BeforeEach
    void setUp() {
        repo = mock(AffectionRepository.class);
        assetLinkRepo = mock(AffectionAssetRepository.class);
        affectsLinkRepo = mock(AffectionAffectsLinkRepository.class);
        historyRepo = mock(AffectStatusHistoryRepository.class);
        findingRepo = mock(FindingRepository.class);
        detectionRepo = mock(DetectionRepository.class);
        assetRepo = mock(AssetRepository.class);
        userRepo = mock(UserRepository.class);
        findingService = mock(FindingService.class);
        orgScope = mock(OrgScopeService.class);
        service = new AffectionService(repo, assetLinkRepo, affectsLinkRepo, historyRepo,
            findingRepo, detectionRepo, assetRepo, userRepo, findingService, orgScope);

        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(findingService.get(anyLong())).thenReturn(mock(FindingDto.class));

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("7", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Affection affection(Long id, Long findingId) {
        Affection a = new Affection();
        ReflectionTestUtils.setField(a, "id", id);
        a.setFindingId(findingId);
        a.setCode("F1-A1");
        a.setCreatedAt(OffsetDateTime.now());
        a.setUpdatedAt(OffsetDateTime.now());
        when(repo.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    private Finding finding(Long id, Long projectId, String code) {
        Finding f = new Finding();
        ReflectionTestUtils.setField(f, "id", id);
        f.setProjectId(projectId);
        f.setCode(code);
        when(findingRepo.findById(id)).thenReturn(Optional.of(f));
        return f;
    }

    private Asset asset(Long id) {
        Asset a = new Asset();
        ReflectionTestUtils.setField(a, "id", id);
        when(assetRepo.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    // ── update / create ──────────────────────────────────────────────

    @Test
    void updateBlankTitleClearsItInsteadOfStoringWhitespace() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        a.setTitle("Old title");

        service.update(1L, "   ", null);

        assertNull(a.getTitle());
    }

    @Test
    void updateLeavesFieldsUntouchedWhenNullIsPassed() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        a.setTitle("Keep me");
        a.setDescription("Keep me too");

        service.update(1L, null, null);

        assertEquals("Keep me", a.getTitle());
        assertEquals("Keep me too", a.getDescription());
    }

    @Test
    void createGeneratesACodeAndAssertsProjectAccess() {
        finding(100L, 5L, "F-100");
        when(findingService.generateAffectionCode(100L, "F-100")).thenReturn("F-100-A1");

        service.create(100L, "New affection", "desc");

        verify(orgScope).assertProjectAccess(any(), eq(5L));
        ArgumentCaptor<Affection> captor = ArgumentCaptor.forClass(Affection.class);
        verify(repo).save(captor.capture());
        assertEquals("F-100-A1", captor.getValue().getCode());
        assertEquals("New affection", captor.getValue().getTitle());
    }

    @Test
    void createThrowsNotFoundForAnUnknownFinding() {
        when(findingRepo.findById(999L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.create(999L, null, null));
    }

    // ── detected_at links ────────────────────────────────────────────

    @Test
    void addDetectedAtRequiresAnExistingAsset() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        when(assetRepo.findById(50L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.addDetectedAt(1L, 50L));
    }

    @Test
    void addDetectedAtAddsALinkWithTheDetectedAtRole() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        asset(50L);

        service.addDetectedAt(1L, 50L);

        assertTrue(a.getAssetLinks().stream().anyMatch(l -> l.getAssetId().equals(50L) && "detected_at".equals(l.getRole())));
    }

    @Test
    void removeDetectedAtCleansUpDependentAffectsLinksAndRecomputesStatus() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        when(affectsLinkRepo.findByAffectionId(1L)).thenReturn(List.of());

        service.removeDetectedAt(1L, 50L);

        verify(affectsLinkRepo).deleteByAffectionAndDetected(1L, 50L);
        verify(assetLinkRepo).deleteLink(1L, 50L, "detected_at");
        verify(repo).recomputeStatus(1L);
        verify(repo).touchUpdatedAt(eq(1L), any());
    }

    // ── setAffectsForDetected (the "III model") ─────────────────────

    @Test
    void setAffectsForDetectedRejectsAnAssetThatIsNotARealDetectedAtOfThisAffection() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        // No detected_at link registered on the affection at all.
        assertThrows(IllegalArgumentException.class, () -> service.setAffectsForDetected(1L, 999L, List.of(2L)));
    }

    @Test
    void setAffectsForDetectedRejectsAffectIdsThatDoNotExist() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        a.addAssetLink(50L, "detected_at", OffsetDateTime.now());
        when(assetRepo.findAllById(List.of(2L, 3L))).thenReturn(List.of(new Asset())); // only 1 of 2 found

        assertThrows(IllegalArgumentException.class, () -> service.setAffectsForDetected(1L, 50L, List.of(2L, 3L)));
    }

    @Test
    void setAffectsForDetectedDedupesNullAndDuplicateIds() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        a.addAssetLink(50L, "detected_at", OffsetDateTime.now());
        when(assetRepo.findAllById(List.of(2L))).thenReturn(List.of(new Asset()));
        when(affectsLinkRepo.findByAffectionId(1L)).thenReturn(List.of());

        service.setAffectsForDetected(1L, 50L, java.util.Arrays.asList(2L, 2L, null));

        verify(affectsLinkRepo, times(1)).save(any());
    }

    @Test
    void setAffectsForDetectedReplacesExistingLinksAndSyncsAffectionAssetRows() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        a.addAssetLink(50L, "detected_at", OffsetDateTime.now());
        when(assetRepo.findAllById(List.of(2L))).thenReturn(List.of(new Asset()));
        // After the delete+save, the sync pass re-reads the current links — simulate the new link now existing.
        when(affectsLinkRepo.findByAffectionId(1L)).thenReturn(List.of(new AffectionAffectsLink(1L, 50L, 2L)));

        service.setAffectsForDetected(1L, 50L, List.of(2L));

        verify(affectsLinkRepo).deleteByAffectionAndDetected(1L, 50L);
        verify(affectsLinkRepo).save(argThat(l -> l.getAffectsAssetId().equals(2L) && l.getDetectedAssetId().equals(50L)));
        // syncAffectsAssetRows must have added the legacy affection_asset row for the newly-linked affect.
        assertTrue(a.getAssetLinks().stream().anyMatch(l -> l.getAssetId().equals(2L) && "affects".equals(l.getRole())));
        verify(historyRepo).save(argThat(h -> h.getAssetId().equals(2L) && "open".equals(h.getToStatus())));
    }

    @Test
    void syncRemovesLegacyAffectsRowsNoLongerReferencedByAnyLink() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        a.addAssetLink(50L, "detected_at", OffsetDateTime.now());
        a.addAssetLink(2L, "affects", null); // pre-existing legacy row, no link will reference it after this call
        when(assetRepo.findAllById(List.of())).thenReturn(List.of());
        when(affectsLinkRepo.findByAffectionId(1L)).thenReturn(List.of()); // no links left at all

        service.setAffectsForDetected(1L, 50L, List.of());

        verify(assetLinkRepo).deleteLink(1L, 2L, "affects");
    }

    // ── addAffects / removeAffects ──────────────────────────────────

    @Test
    void addAffectsRequiresAnExistingAssetAndRecordsOpenHistory() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        asset(50L);

        service.addAffects(1L, 50L);

        assertTrue(a.getAssetLinks().stream().anyMatch(l -> l.getAssetId().equals(50L) && "affects".equals(l.getRole())));
        verify(repo).recomputeStatus(1L);
        verify(historyRepo).save(argThat(h -> h.getFromStatus() == null && "open".equals(h.getToStatus()) && h.getChangedBy().equals(7L)));
    }

    @Test
    void addAffectsThrowsNotFoundForAnUnknownAsset() {
        affection(1L, 100L);
        finding(100L, 5L, "F-100");
        when(assetRepo.findById(999L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.addAffects(1L, 999L));
    }

    @Test
    void removeAffectsDeletesTheLinkAndRecomputesStatus() {
        affection(1L, 100L);
        finding(100L, 5L, "F-100");
        service.removeAffects(1L, 50L);
        verify(assetLinkRepo).deleteLink(1L, 50L, "affects");
        verify(repo).recomputeStatus(1L);
    }

    // ── updateAffectStatus ───────────────────────────────────────────

    @Test
    void updateAffectStatusThrowsNotFoundWhenTheAssetHasNoCurrentAffectStatus() {
        affection(1L, 100L);
        finding(100L, 5L, "F-100");
        when(assetLinkRepo.getCurrentAffectStatus(1L, 50L)).thenReturn(null);
        assertThrows(NotFoundException.class, () -> service.updateAffectStatus(1L, 50L, "fixed", null));
    }

    @Test
    void updateAffectStatusRecordsTheTransitionWithATrimmedNote() {
        affection(1L, 100L);
        finding(100L, 5L, "F-100");
        when(assetLinkRepo.getCurrentAffectStatus(1L, 50L)).thenReturn("open");

        service.updateAffectStatus(1L, 50L, "fixed", "  patched  ");

        verify(assetLinkRepo).updateAffectStatus(1L, 50L, "fixed");
        verify(historyRepo).save(argThat(h -> "open".equals(h.getFromStatus()) && "fixed".equals(h.getToStatus()) && "patched".equals(h.getNote())));
    }

    @Test
    void updateAffectStatusStoresNullNoteWhenBlank() {
        affection(1L, 100L);
        finding(100L, 5L, "F-100");
        when(assetLinkRepo.getCurrentAffectStatus(1L, 50L)).thenReturn("open");

        service.updateAffectStatus(1L, 50L, "fixed", "   ");

        verify(historyRepo).save(argThat(h -> h.getNote() == null));
    }

    @Test
    void getAffectHistoryMapsEntitiesToDtos() {
        when(historyRepo.findByAffectionIdAndAssetId(eq(1L), eq(50L), any())).thenReturn(new PageImpl<>(List.of(
            new AffectStatusHistory(1L, 50L, "open", "fixed", 7L, "Alice", "note", OffsetDateTime.now()))));
        var result = service.getAffectHistory(1L, 50L, 0, 20);
        assertEquals(1, result.items().size());
        assertEquals("fixed", result.items().get(0).toStatus());
        assertEquals("Alice", result.items().get(0).changedByName());
    }

    @Test
    void getAffectHistoryClampsPageAndSizeBeforeQuerying() {
        when(historyRepo.findByAffectionIdAndAssetId(eq(1L), eq(50L), any())).thenReturn(new PageImpl<>(List.of()));
        var captor = ArgumentCaptor.forClass(Pageable.class);

        service.getAffectHistory(1L, 50L, -5, 999);

        verify(historyRepo).findByAffectionIdAndAssetId(eq(1L), eq(50L), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(100, captor.getValue().getPageSize());
    }

    // ── Detection links ──────────────────────────────────────────────

    @Test
    void addDetectionInheritsTheDetectionsCreatedAtForTheDetectedAtLink() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        Detection d = new Detection();
        ReflectionTestUtils.setField(d, "id", 9L);
        ReflectionTestUtils.setField(d, "assetId", 50L);
        OffsetDateTime firstSeen = OffsetDateTime.now().minusDays(3);
        ReflectionTestUtils.setField(d, "createdAt", firstSeen);
        when(detectionRepo.findById(9L)).thenReturn(Optional.of(d));

        service.addDetection(1L, 9L);

        assertTrue(a.getDetections().contains(d));
        var link = a.getAssetLinks().stream().filter(l -> l.getAssetId().equals(50L)).findFirst().orElseThrow();
        assertEquals("detected_at", link.getRole());
        assertEquals(firstSeen, link.getObservedAt());
    }

    @Test
    void addDetectionThrowsNotFoundForAnUnknownDetection() {
        affection(1L, 100L);
        finding(100L, 5L, "F-100");
        when(detectionRepo.findById(9L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.addDetection(1L, 9L));
    }

    @Test
    void removeDetectionRemovesOnlyTheMatchingDetection() {
        Affection a = affection(1L, 100L);
        finding(100L, 5L, "F-100");
        Detection kept = new Detection();
        ReflectionTestUtils.setField(kept, "id", 1L);
        Detection removed = new Detection();
        ReflectionTestUtils.setField(removed, "id", 2L);
        a.getDetections().add(kept);
        a.getDetections().add(removed);

        service.removeDetection(1L, 2L);

        assertEquals(1, a.getDetections().size());
        assertTrue(a.getDetections().contains(kept));
    }

    // ── delete ────────────────────────────────────────────────────────

    @Test
    void deleteThrowsNotFoundWhenTheAffectionDoesNotExist() {
        when(repo.existsById(1L)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> service.delete(1L));
        verify(repo, never()).deleteById(any());
    }

    @Test
    void deleteRemovesAnExistingAffection() {
        when(repo.existsById(1L)).thenReturn(true);
        service.delete(1L);
        verify(repo).deleteById(1L);
    }

    // ── access control ───────────────────────────────────────────────

    @Test
    void gettingAnAffectionForAnUnknownFindingThrowsNotFound() {
        Affection a = new Affection();
        ReflectionTestUtils.setField(a, "id", 1L);
        a.setFindingId(999L);
        when(repo.findById(1L)).thenReturn(Optional.of(a));
        when(findingRepo.findById(999L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.update(1L, "x", null));
    }
}
