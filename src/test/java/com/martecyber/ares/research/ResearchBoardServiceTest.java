package com.martecyber.ares.research;

import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.detections.DetectionService;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.research.dto.*;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.users.User;
import com.martecyber.ares.users.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test for {@link ResearchBoardService} — Phase 2 (plain CRUD + assignment/
 *  membership; merge and escalate-to-finding are later phases and aren't covered here). */
class ResearchBoardServiceTest {

    private ResearchBoardRepository boardRepo;
    private ResearchBoardUserRepository boardUserRepo;
    private ResearchBoardDetectionRepository boardDetectionRepo;
    private DetectionRepository detectionRepo;
    private DetectionService detectionService;
    private AffectionRepository affectionRepo;
    private FindingRepository findingRepo;
    private UserRepository userRepo;
    private OrgScopeService orgScope;
    private ResearchBoardService service;

    private final AtomicLong nextBoardId = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        boardRepo = mock(ResearchBoardRepository.class);
        boardUserRepo = mock(ResearchBoardUserRepository.class);
        boardDetectionRepo = mock(ResearchBoardDetectionRepository.class);
        detectionRepo = mock(DetectionRepository.class);
        detectionService = mock(DetectionService.class);
        affectionRepo = mock(AffectionRepository.class);
        findingRepo = mock(FindingRepository.class);
        userRepo = mock(UserRepository.class);
        orgScope = mock(OrgScopeService.class);

        service = new ResearchBoardService(boardRepo, boardUserRepo, boardDetectionRepo,
            detectionRepo, detectionService, affectionRepo, findingRepo, userRepo, orgScope);
        when(affectionRepo.findById(anyLong())).thenReturn(Optional.empty());

        when(boardRepo.save(any())).thenAnswer(inv -> {
            ResearchBoard b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", nextBoardId.getAndIncrement());
            return b;
        });
        when(boardRepo.findById(anyLong())).thenAnswer(inv -> Optional.empty());
        when(boardUserRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(boardUserRepo.findByIdBoardId(anyLong())).thenReturn(List.of());
        when(boardDetectionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(anyLong())).thenReturn(List.of());
        when(boardDetectionRepo.findById(any())).thenReturn(Optional.empty());
        when(boardDetectionRepo.findByIdDetectionIdAndRemovedAtIsNull(anyLong())).thenReturn(Optional.empty());
        when(userRepo.findById(anyLong())).thenAnswer(inv -> Optional.of(user(inv.getArgument(0), "user")));
        setAuth("1");
        when(orgScope.isPlatformAdmin(any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication setAuth(String userId) {
        var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    private Detection detection(Long id, Long projectId, String status) {
        Detection d = new Detection();
        ReflectionTestUtils.setField(d, "id", id);
        d.setProjectId(projectId);
        d.setStatus(status);
        d.setTitle("Detection " + id);
        return d;
    }

    private User user(Long id, String name) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail(name + "@example.com");
        u.setDisplayName(name);
        return u;
    }

    private void stubBoard(ResearchBoard board) {
        when(boardRepo.findById(board.getId())).thenReturn(Optional.of(board));
    }

    private ResearchBoard activeBoard(Long id, Long projectId, Long leadUserId) {
        ResearchBoard b = new ResearchBoard();
        ReflectionTestUtils.setField(b, "id", id);
        b.setProjectId(projectId);
        b.setTitle("Board " + id);
        b.setLeadUserId(leadUserId);
        b.setStatus("active");
        b.setCreatedAt(OffsetDateTime.now());
        b.setUpdatedAt(OffsetDateTime.now());
        stubBoard(b);
        return b;
    }

    @Test
    void createLinksAddableDetectionsAndSetsUnderInvestigation() {
        when(detectionRepo.findById(10L)).thenReturn(Optional.of(detection(10L, 5L, "new")));

        var dto = service.create(5L, new CreateResearchBoardRequest("Investigate X", List.of(10L)));

        verify(detectionService).updateStatus(eq(10L), eq("under_investigation"), anyString());
        verify(boardDetectionRepo).save(argThat(l -> l.getId().getDetectionId().equals(10L)));
        // creator becomes lead and self-assigns
        assertEquals(1L, dto.leadUserId());
        verify(boardUserRepo, atLeastOnce()).save(argThat(bu -> bu.getId().getUserId().equals(1L)));
    }

    @Test
    void createRejectsDetectionNotNewOrReopened() {
        when(detectionRepo.findById(10L)).thenReturn(Optional.of(detection(10L, 5L, "affected")));

        assertThrows(IllegalArgumentException.class,
            () -> service.create(5L, new CreateResearchBoardRequest("Investigate X", List.of(10L))));
        verify(detectionService, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    void addDetectionsRejectsDetectionAlreadyOnAnotherActiveBoard() {
        ResearchBoard board = activeBoard(1L, 5L, 1L);
        when(detectionRepo.findById(10L)).thenReturn(Optional.of(detection(10L, 5L, "new")));
        var existingLink = new ResearchBoardDetection(999L, 10L);
        when(boardDetectionRepo.findByIdDetectionIdAndRemovedAtIsNull(10L)).thenReturn(Optional.of(existingLink));

        assertThrows(ConflictException.class,
            () -> service.addDetections(1L, new AddDetectionsRequest(List.of(10L))));
    }

    @Test
    void removeDetectionPlainRevertsStatusToReopened() {
        ResearchBoard board = activeBoard(1L, 5L, 1L);
        var link = new ResearchBoardDetection(1L, 10L);
        when(boardDetectionRepo.findById(new ResearchBoardDetectionId(1L, 10L))).thenReturn(Optional.of(link));

        service.removeDetection(1L, 10L, null, null);

        verify(detectionService).updateStatus(eq(10L), eq("reopened"), anyString());
        assertNotNull(link.getRemovedAt());
    }

    @Test
    void removeDetectionMoveToExistingBoardRelinksWithoutStatusChange() {
        ResearchBoard source = activeBoard(1L, 5L, 1L);
        ResearchBoard target = activeBoard(2L, 5L, 1L);
        var link = new ResearchBoardDetection(1L, 10L);
        when(boardDetectionRepo.findById(new ResearchBoardDetectionId(1L, 10L))).thenReturn(Optional.of(link));
        when(boardDetectionRepo.findById(new ResearchBoardDetectionId(2L, 10L))).thenReturn(Optional.empty());

        service.removeDetection(1L, 10L, 2L, null);

        verify(boardDetectionRepo).save(argThat(l ->
            l.getId().getBoardId().equals(2L) && l.getId().getDetectionId().equals(10L)));
        verify(detectionService, never()).updateStatus(eq(10L), eq("reopened"), anyString());
        verify(detectionService, never()).updateStatus(eq(10L), eq("under_investigation"), anyString());
    }

    @Test
    void removeDetectionMoveToDifferentProjectBoardRejected() {
        activeBoard(1L, 5L, 1L);
        activeBoard(2L, 6L, 1L); // different project
        when(boardDetectionRepo.findById(new ResearchBoardDetectionId(1L, 10L)))
            .thenReturn(Optional.of(new ResearchBoardDetection(1L, 10L)));

        assertThrows(IllegalArgumentException.class, () -> service.removeDetection(1L, 10L, 2L, null));
    }

    @Test
    void updateSelfAssignsCurrentUserAsMember() {
        activeBoard(1L, 5L, 2L); // someone else is lead

        service.update(1L, new UpdateResearchBoardRequest(null, "some notes"));

        verify(boardUserRepo).save(argThat(bu -> bu.getId().getUserId().equals(1L)));
    }

    @Test
    void addMemberSelfIsAlwaysAllowed() {
        activeBoard(1L, 5L, 2L); // current user (1) is not lead

        var dto = service.addMember(1L, new AddResearchBoardMemberRequest(1L));

        verify(boardUserRepo).save(argThat(bu -> bu.getId().getUserId().equals(1L)));
        assertNotNull(dto);
    }

    @Test
    void addMemberOfSomeoneElseByNonLeadNonAdminIsRejected() {
        activeBoard(1L, 5L, 2L); // current user (1) is not lead (2 is)

        assertThrows(AccessDeniedException.class,
            () -> service.addMember(1L, new AddResearchBoardMemberRequest(3L)));
    }

    @Test
    void addMemberOfSomeoneElseByLeadSucceeds() {
        activeBoard(1L, 5L, 1L); // current user (1) IS lead

        service.addMember(1L, new AddResearchBoardMemberRequest(3L));

        verify(boardUserRepo).save(argThat(bu -> bu.getId().getUserId().equals(3L)));
    }

    @Test
    void removeMemberSelfIsAlwaysAllowedEvenIfNotLead() {
        activeBoard(1L, 5L, 2L); // current user (1) is not lead

        service.removeMember(1L, 1L);

        verify(boardUserRepo).deleteById(new ResearchBoardUserId(1L, 1L));
    }

    @Test
    void deleteRejectsWhenBoardStillHasDetections() {
        activeBoard(1L, 5L, 1L);
        when(boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(1L))
            .thenReturn(List.of(new ResearchBoardDetection(1L, 10L)));

        assertThrows(ConflictException.class, () -> service.delete(1L));
        verify(boardRepo, never()).delete(any());
    }

    @Test
    void deleteSucceedsWhenEmpty() {
        ResearchBoard board = activeBoard(1L, 5L, 1L);

        service.delete(1L);

        verify(boardRepo).delete(board);
    }

    @Test
    void getUnknownBoardThrowsNotFound() {
        assertThrows(NotFoundException.class, () -> service.get(999L));
    }

    // ── merge ────────────────────────────────────────────────────────────

    @Test
    void mergeMovesDetectionsAndMembersOntoTargetAndDeletesSource() {
        ResearchBoard source = activeBoard(1L, 5L, 1L);
        ResearchBoard target = activeBoard(2L, 5L, 1L);
        when(boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(1L))
            .thenReturn(List.of(new ResearchBoardDetection(1L, 10L), new ResearchBoardDetection(1L, 11L)));
        when(boardDetectionRepo.findById(new ResearchBoardDetectionId(2L, 10L))).thenReturn(Optional.empty());
        when(boardDetectionRepo.findById(new ResearchBoardDetectionId(2L, 11L))).thenReturn(Optional.empty());
        when(boardUserRepo.findByIdBoardId(1L)).thenReturn(List.of(new ResearchBoardUser(1L, 3L)));

        var dto = service.merge(1L, 2L, "Combined investigation");

        verify(boardDetectionRepo).save(argThat(l -> l.getId().getBoardId().equals(2L) && l.getId().getDetectionId().equals(10L)));
        verify(boardDetectionRepo).save(argThat(l -> l.getId().getBoardId().equals(2L) && l.getId().getDetectionId().equals(11L)));
        verify(boardUserRepo).save(argThat(bu -> bu.getId().getBoardId().equals(2L) && bu.getId().getUserId().equals(3L)));
        verify(boardRepo).delete(source);
        assertEquals("Combined investigation", dto.title());
    }

    @Test
    void mergeConcatenatesNotesWithHeadersAndSeparator() {
        ResearchBoard source = activeBoard(1L, 5L, 1L);
        source.setNotes("source findings");
        ResearchBoard target = activeBoard(2L, 5L, 1L);
        target.setNotes("target findings");

        service.merge(1L, 2L, "New title");

        assertEquals("## Board 1\n\nsource findings\n\n---\n\n## Board 2\n\ntarget findings", target.getNotes());
    }

    @Test
    void mergeDefaultsTitleToBothTitlesWhenBlank() {
        activeBoard(1L, 5L, 1L);
        ResearchBoard target = activeBoard(2L, 5L, 1L);

        service.merge(1L, 2L, "  ");

        assertEquals("Board 1 / Board 2", target.getTitle());
    }

    @Test
    void mergeRejectsMergingABoardWithItself() {
        activeBoard(1L, 5L, 1L);

        assertThrows(IllegalArgumentException.class, () -> service.merge(1L, 1L, "x"));
    }

    @Test
    void mergeRejectsBoardsFromDifferentProjects() {
        activeBoard(1L, 5L, 1L);
        activeBoard(2L, 6L, 1L);

        assertThrows(IllegalArgumentException.class, () -> service.merge(1L, 2L, "x"));
    }

    // ── archiveAsAffected / notAffected ──────────────────────────────────

    private void stubAffection(Long affectionId, Long findingId, Long findingProjectId) {
        Affection aff = new Affection();
        ReflectionTestUtils.setField(aff, "id", affectionId);
        aff.setFindingId(findingId);
        when(affectionRepo.findById(affectionId)).thenReturn(Optional.of(aff));
        Finding finding = new Finding();
        ReflectionTestUtils.setField(finding, "id", findingId);
        finding.setProjectId(findingProjectId);
        when(findingRepo.findById(findingId)).thenReturn(Optional.of(finding));
    }

    @Test
    void archiveAsAffectedArchivesBoardWithVerdictAndAffectionOnceDetectionsAlreadyEscalated() {
        ResearchBoard board = activeBoard(1L, 5L, 1L);
        when(boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(1L))
            .thenReturn(List.of(new ResearchBoardDetection(1L, 10L), new ResearchBoardDetection(1L, 11L)));
        stubAffection(900L, 500L, 5L);

        var dto = service.archiveAsAffected(1L, 900L);

        assertEquals("archived", board.getStatus());
        assertEquals("affected", board.getVerdict());
        assertEquals(900L, board.getResultAffectionId());
        assertEquals("archived", dto.status());
        assertEquals("affected", dto.verdict());
    }

    @Test
    void archiveAsAffectedRejectsAnEmptyBoard() {
        activeBoard(1L, 5L, 1L);
        when(boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(1L)).thenReturn(List.of());
        stubAffection(900L, 500L, 5L);

        assertThrows(IllegalArgumentException.class, () -> service.archiveAsAffected(1L, 900L));
    }

    @Test
    void archiveAsAffectedRejectsAnAffectionFromAnotherProject() {
        ResearchBoard board = activeBoard(1L, 5L, 1L);
        when(boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(1L))
            .thenReturn(List.of(new ResearchBoardDetection(1L, 10L)));
        stubAffection(900L, 500L, 6L);

        assertThrows(IllegalArgumentException.class, () -> service.archiveAsAffected(1L, 900L));
        assertEquals("active", board.getStatus());
    }

    @Test
    void notAffectedMovesEveryDetectionAndArchivesWithVerdict() {
        ResearchBoard board = activeBoard(1L, 5L, 1L);
        when(boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(1L))
            .thenReturn(List.of(new ResearchBoardDetection(1L, 10L), new ResearchBoardDetection(1L, 11L)));

        var dto = service.notAffected(1L, "confirmed benign");

        verify(detectionService).updateStatus(10L, "not_affected", "confirmed benign");
        verify(detectionService).updateStatus(11L, "not_affected", "confirmed benign");
        assertEquals("archived", board.getStatus());
        assertEquals("not_affected", board.getVerdict());
        assertNull(board.getResultAffectionId());
        assertEquals("not_affected", dto.verdict());
    }

    @Test
    void notAffectedRejectsAnEmptyBoard() {
        activeBoard(1L, 5L, 1L);
        when(boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(1L)).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.notAffected(1L, null));
        verify(detectionService, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    void getResolvesResultFindingIdFromTheStoredAffection() {
        ResearchBoard board = activeBoard(1L, 5L, 1L);
        board.setStatus("archived");
        board.setVerdict("affected");
        board.setResultAffectionId(900L);
        Affection aff = new Affection();
        ReflectionTestUtils.setField(aff, "id", 900L);
        aff.setFindingId(777L);
        when(affectionRepo.findById(900L)).thenReturn(Optional.of(aff));

        var dto = service.get(1L);

        assertEquals(777L, dto.resultFindingId());
    }
}
