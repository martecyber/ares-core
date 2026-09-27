package com.martecyber.ares.research;

import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.detections.DetectionService;
import com.martecyber.ares.detections.dto.DetectionDto;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.research.dto.*;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.users.User;
import com.martecyber.ares.users.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Research Boards: the intermediate area between Detections and Findings (see the Research
 * Boards plan) — CRUD, assignment/membership, merge, and resolution (escalate to a finding, or
 * "no affection") that archives the board with a verdict.
 */
@Service
public class ResearchBoardService {

    private static final Set<String> ADDABLE_DETECTION_STATUSES = Set.of("new", "reopened");

    private final ResearchBoardRepository boardRepo;
    private final ResearchBoardUserRepository boardUserRepo;
    private final ResearchBoardDetectionRepository boardDetectionRepo;
    private final DetectionRepository detectionRepo;
    private final DetectionService detectionService;
    private final AffectionRepository affectionRepo;
    private final FindingRepository findingRepo;
    private final UserRepository userRepo;
    private final OrgScopeService orgScope;

    public ResearchBoardService(ResearchBoardRepository boardRepo,
                                 ResearchBoardUserRepository boardUserRepo,
                                 ResearchBoardDetectionRepository boardDetectionRepo,
                                 DetectionRepository detectionRepo,
                                 DetectionService detectionService,
                                 AffectionRepository affectionRepo,
                                 FindingRepository findingRepo,
                                 UserRepository userRepo,
                                 OrgScopeService orgScope) {
        this.boardRepo = boardRepo;
        this.boardUserRepo = boardUserRepo;
        this.boardDetectionRepo = boardDetectionRepo;
        this.detectionRepo = detectionRepo;
        this.detectionService = detectionService;
        this.affectionRepo = affectionRepo;
        this.findingRepo = findingRepo;
        this.userRepo = userRepo;
        this.orgScope = orgScope;
    }

    // ── Reads ────────────────────────────────────────────────────────────

    public List<ResearchBoardSummaryDto> list(Long projectId, boolean archived) {
        assertAccess(projectId);
        String status = archived ? "archived" : "active";
        return boardRepo.findByProjectIdAndStatusOrderByUpdatedAtDesc(projectId, status)
            .stream().map(this::toSummary).toList();
    }

    public ResearchBoardDetailDto get(Long id) {
        ResearchBoard board = require(id);
        assertAccess(board.getProjectId());
        return toDetail(board);
    }

    // ── Writes ───────────────────────────────────────────────────────────

    @Transactional
    public ResearchBoardDetailDto create(Long projectId, CreateResearchBoardRequest req) {
        assertAccess(projectId);
        Long uid = currentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        ResearchBoard board = new ResearchBoard();
        board.setProjectId(projectId);
        board.setTitle(req.title());
        board.setLeadUserId(uid);
        board.setCreatedByUserId(uid);
        board.setCreatedAt(now);
        board.setUpdatedAt(now);
        board = boardRepo.save(board);
        touchAssignment(board);
        if (req.detectionIds() != null && !req.detectionIds().isEmpty()) {
            addDetectionsInternal(board, req.detectionIds());
        }
        return toDetail(board);
    }

    @Transactional
    public ResearchBoardDetailDto update(Long id, UpdateResearchBoardRequest req) {
        ResearchBoard board = require(id);
        assertAccess(board.getProjectId());
        assertActive(board);
        touchAssignment(board);
        if (req.title() != null && !req.title().isBlank()) board.setTitle(req.title());
        if (req.notes() != null) board.setNotes(req.notes());
        board.setUpdatedAt(OffsetDateTime.now());
        boardRepo.save(board);
        return toDetail(board);
    }

    @Transactional
    public ResearchBoardDetailDto addDetections(Long id, AddDetectionsRequest req) {
        ResearchBoard board = require(id);
        assertAccess(board.getProjectId());
        assertActive(board);
        touchAssignment(board);
        addDetectionsInternal(board, req.detectionIds());
        board.setUpdatedAt(OffsetDateTime.now());
        boardRepo.save(board);
        return toDetail(board);
    }

    /** Plain removal (no target) reverts the detection to "reopened" — back in the queue, same
     *  as every other status's only manual exit. With moveToBoardId/newBoardTitle it instead
     *  relinks the detection to that board, keeping it "under_investigation" throughout (moving
     *  boards is not a status change). */
    @Transactional
    public ResearchBoardDetailDto removeDetection(Long id, Long detectionId, Long moveToBoardId, String newBoardTitle) {
        ResearchBoard board = require(id);
        assertAccess(board.getProjectId());
        assertActive(board);
        touchAssignment(board);

        var key = new ResearchBoardDetectionId(id, detectionId);
        ResearchBoardDetection link = boardDetectionRepo.findById(key)
            .filter(l -> l.getRemovedAt() == null)
            .orElseThrow(() -> NotFoundException.of("research board detection", detectionId));
        link.setRemovedAt(OffsetDateTime.now());
        boardDetectionRepo.save(link);

        if (moveToBoardId != null) {
            ResearchBoard target = require(moveToBoardId);
            assertAccess(target.getProjectId());
            assertActive(target);
            if (!target.getProjectId().equals(board.getProjectId())) {
                throw new IllegalArgumentException("Cannot move a detection to a board in a different project");
            }
            linkDetection(target, detectionId);
            touchAssignment(target);
            target.setUpdatedAt(OffsetDateTime.now());
            boardRepo.save(target);
        } else if (newBoardTitle != null && !newBoardTitle.isBlank()) {
            OffsetDateTime now = OffsetDateTime.now();
            Long uid = currentUserId();
            ResearchBoard target = new ResearchBoard();
            target.setProjectId(board.getProjectId());
            target.setTitle(newBoardTitle);
            target.setLeadUserId(uid);
            target.setCreatedByUserId(uid);
            target.setCreatedAt(now);
            target.setUpdatedAt(now);
            target = boardRepo.save(target);
            touchAssignment(target);
            linkDetection(target, detectionId);
        } else {
            detectionService.updateStatus(detectionId, "reopened",
                "Removed from Research Board \"" + board.getTitle() + "\"");
        }

        board.setUpdatedAt(OffsetDateTime.now());
        boardRepo.save(board);
        return toDetail(board);
    }

    /** Only an empty board (no current detections) can be deleted outright — one with detections
     *  must be resolved (merge/escalate/no-affection, later phases) instead, so a detection's
     *  under_investigation status is never left orphaned by its board disappearing. */
    @Transactional
    public void delete(Long id) {
        ResearchBoard board = require(id);
        assertAccess(board.getProjectId());
        if (!boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(id).isEmpty()) {
            throw new ConflictException("Cannot delete a board that still has detections — remove them first");
        }
        boardRepo.delete(board);
    }

    @Transactional
    public ResearchBoardDetailDto addMember(Long id, AddResearchBoardMemberRequest req) {
        ResearchBoard board = require(id);
        assertAccess(board.getProjectId());
        Authentication auth = currentAuth();
        Long uid = currentUserId(auth);
        boolean self = uid != null && uid.equals(req.userId());
        if (!self && !isLeadOrAdmin(board, auth)) {
            throw new AccessDeniedException("Only the board's lead can add other users");
        }
        userRepo.findById(req.userId()).orElseThrow(() -> NotFoundException.of("user", req.userId()));
        var key = new ResearchBoardUserId(id, req.userId());
        if (!boardUserRepo.existsById(key)) boardUserRepo.save(new ResearchBoardUser(id, req.userId()));
        return toDetail(board);
    }

    @Transactional
    public ResearchBoardDetailDto removeMember(Long id, Long userId) {
        ResearchBoard board = require(id);
        assertAccess(board.getProjectId());
        Authentication auth = currentAuth();
        Long uid = currentUserId(auth);
        boolean self = uid != null && uid.equals(userId);
        if (!self && !isLeadOrAdmin(board, auth)) {
            throw new AccessDeniedException("Only the board's lead can remove other users");
        }
        boardUserRepo.deleteById(new ResearchBoardUserId(id, userId));
        return toDetail(board);
    }

    /** Merges {@code sourceId} (the URL path board — deleted) into {@code targetId} (survives) —
     *  same source/target direction {@code AssetService.merge} uses. Detections and members are
     *  unioned onto the target; notes are concatenated (source's own section first, then the
     *  target's, each under a header naming that board's original title, separated by a rule) —
     *  see the plan's exact format. */
    @Transactional
    public ResearchBoardDetailDto merge(Long sourceId, Long targetId, String title) {
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("Cannot merge a board with itself");
        }
        ResearchBoard source = require(sourceId);
        ResearchBoard target = require(targetId);
        assertAccess(source.getProjectId());
        assertAccess(target.getProjectId());
        assertActive(source);
        assertActive(target);
        if (!source.getProjectId().equals(target.getProjectId())) {
            throw new IllegalArgumentException("Cannot merge boards from different projects");
        }
        touchAssignment(target);

        for (ResearchBoardDetection link : boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(sourceId)) {
            Long detId = link.getId().getDetectionId();
            link.setRemovedAt(OffsetDateTime.now());
            boardDetectionRepo.save(link);
            linkDetection(target, detId);
        }
        for (ResearchBoardUser bu : boardUserRepo.findByIdBoardId(sourceId)) {
            var key = new ResearchBoardUserId(targetId, bu.getId().getUserId());
            if (!boardUserRepo.existsById(key)) boardUserRepo.save(new ResearchBoardUser(targetId, bu.getId().getUserId()));
        }

        // Notes headers name each board's ORIGINAL title — compute before renaming target.
        String mergedNotes = mergeNotes(source, target);
        target.setTitle((title != null && !title.isBlank()) ? title.trim() : source.getTitle() + " / " + target.getTitle());
        target.setNotes(mergedNotes);
        target.setUpdatedAt(OffsetDateTime.now());
        boardRepo.save(target);

        boardRepo.delete(source);
        return toDetail(target);
    }

    private static String mergeNotes(ResearchBoard source, ResearchBoard target) {
        String sourceNotes = source.getNotes() != null ? source.getNotes() : "";
        String targetNotes = target.getNotes() != null ? target.getNotes() : "";
        return "## " + source.getTitle() + "\n\n" + sourceNotes + "\n\n---\n\n## " + target.getTitle() + "\n\n" + targetNotes;
    }

    // ── Resolution ───────────────────────────────────────────────────────

    /** The escalation wizard has already resolved (created or picked) a Finding/Affection and
     *  linked+transitioned the board's detections to it itself, via the generic finding/affection
     *  endpoints plus {@link DetectionService#escalateBatch} in existing_finding/existing_affection
     *  mode — this call is only the board's own bookkeeping: archive with verdict "affected" and
     *  record the resulting affection. Superseded escalateToFinding (which did the finding/affection
     *  creation server-side, with no fields/scores/references support) is gone — the wizard needs
     *  that richness, which already lives in FindingService.create/addAffection. */
    @Transactional
    public ResearchBoardDetailDto archiveAsAffected(Long id, Long affectionId) {
        ResearchBoard board = require(id);
        assertAccess(board.getProjectId());
        assertActive(board);
        touchAssignment(board);

        com.martecyber.ares.affections.Affection aff = affectionRepo.findById(affectionId)
            .orElseThrow(() -> NotFoundException.of("affection", affectionId));
        Finding finding = findingRepo.findById(aff.getFindingId())
            .orElseThrow(() -> NotFoundException.of("finding", aff.getFindingId()));
        if (!finding.getProjectId().equals(board.getProjectId())) {
            throw new IllegalArgumentException("Affection " + affectionId + " does not belong to this project");
        }

        if (boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(id).isEmpty()) {
            throw new IllegalArgumentException("Cannot resolve an empty Research Board — add detections first");
        }

        OffsetDateTime now = OffsetDateTime.now();
        board.setStatus("archived");
        board.setVerdict("affected");
        board.setResultAffectionId(affectionId);
        board.setArchivedAt(now);
        board.setUpdatedAt(now);
        boardRepo.save(board);
        return toDetail(board);
    }

    /** Resolves the board with no affection: every current detection becomes "not_affected", the
     *  board archives with that verdict. */
    @Transactional
    public ResearchBoardDetailDto notAffected(Long id, String note) {
        ResearchBoard board = require(id);
        assertAccess(board.getProjectId());
        assertActive(board);
        touchAssignment(board);

        List<Long> detectionIds = boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(id).stream()
            .map(link -> link.getId().getDetectionId()).toList();
        if (detectionIds.isEmpty()) {
            throw new IllegalArgumentException("Cannot resolve an empty Research Board — add detections first");
        }
        String resolvedNote = (note != null && !note.isBlank()) ? note.trim()
            : "Resolved with no affection via Research Board \"" + board.getTitle() + "\"";
        for (Long detId : detectionIds) {
            detectionService.updateStatus(detId, "not_affected", resolvedNote);
        }

        OffsetDateTime now = OffsetDateTime.now();
        board.setStatus("archived");
        board.setVerdict("not_affected");
        board.setArchivedAt(now);
        board.setUpdatedAt(now);
        boardRepo.save(board);
        return toDetail(board);
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private void addDetectionsInternal(ResearchBoard board, List<Long> detectionIds) {
        for (Long detId : detectionIds) {
            Detection d = detectionRepo.findById(detId).orElseThrow(() -> NotFoundException.of("detection", detId));
            if (!board.getProjectId().equals(d.getProjectId())) {
                throw new IllegalArgumentException("Detection " + detId + " does not belong to this project");
            }
            boardDetectionRepo.findByIdDetectionIdAndRemovedAtIsNull(detId).ifPresent(existing -> {
                if (!existing.getId().getBoardId().equals(board.getId())) {
                    throw new ConflictException("Detection " + detId + " is already on another active Research Board");
                }
            });
            if (!ADDABLE_DETECTION_STATUSES.contains(d.getStatus())) {
                throw new IllegalArgumentException("Detection " + detId + " has status '" + d.getStatus()
                    + "' — only 'new'/'reopened' detections can be added to a Research Board");
            }
            linkDetection(board, detId);
            detectionService.updateStatus(detId, "under_investigation",
                "Added to Research Board \"" + board.getTitle() + "\"");
        }
    }

    private void linkDetection(ResearchBoard board, Long detectionId) {
        var key = new ResearchBoardDetectionId(board.getId(), detectionId);
        ResearchBoardDetection link = boardDetectionRepo.findById(key).orElse(null);
        if (link == null) {
            boardDetectionRepo.save(new ResearchBoardDetection(board.getId(), detectionId));
        } else {
            link.setRemovedAt(null);
            link.setAddedAt(OffsetDateTime.now());
            boardDetectionRepo.save(link);
        }
    }

    /** Any user who mutates a board self-assigns to it — see the plan's "auto-assign on edit". */
    private void touchAssignment(ResearchBoard board) {
        Long uid = currentUserId();
        if (uid == null) return;
        var key = new ResearchBoardUserId(board.getId(), uid);
        if (!boardUserRepo.existsById(key)) boardUserRepo.save(new ResearchBoardUser(board.getId(), uid));
    }

    private boolean isLeadOrAdmin(ResearchBoard board, Authentication auth) {
        if (orgScope.isPlatformAdmin(auth)) return true;
        Long uid = currentUserId(auth);
        return uid != null && uid.equals(board.getLeadUserId());
    }

    private void assertAccess(Long projectId) {
        orgScope.assertProjectAccess(currentAuth(), projectId);
    }

    private void assertActive(ResearchBoard board) {
        if (!"active".equals(board.getStatus())) {
            throw new IllegalStateException("Research Board \"" + board.getTitle() + "\" is archived");
        }
    }

    private ResearchBoard require(Long id) {
        return boardRepo.findById(id).orElseThrow(() -> NotFoundException.of("research board", id));
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private Long currentUserId() { return currentUserId(currentAuth()); }

    private static Long currentUserId(Authentication auth) {
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    private ResearchBoardSummaryDto toSummary(ResearchBoard b) {
        List<ResearchBoardMemberDto> members = members(b.getId());
        int detectionCount = boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(b.getId()).size();
        return new ResearchBoardSummaryDto(b.getId(), b.getProjectId(), b.getTitle(), b.getLeadUserId(),
            leadName(b.getLeadUserId()), b.getStatus(), b.getVerdict(), b.getResultAffectionId(),
            resultFindingId(b), members, detectionCount, b.getArchivedAt(), b.getUpdatedAt());
    }

    private ResearchBoardDetailDto toDetail(ResearchBoard b) {
        List<ResearchBoardMemberDto> members = members(b.getId());
        List<DetectionDto> detections = boardDetectionRepo.findByIdBoardIdAndRemovedAtIsNull(b.getId()).stream()
            .map(link -> detectionService.get(link.getId().getDetectionId()))
            .toList();
        return new ResearchBoardDetailDto(b.getId(), b.getProjectId(), b.getTitle(), b.getNotes(), b.getLeadUserId(),
            leadName(b.getLeadUserId()), b.getStatus(), b.getVerdict(), b.getResultAffectionId(), resultFindingId(b),
            members, detections, b.getArchivedAt(), b.getCreatedAt(), b.getUpdatedAt());
    }

    /** Denormalized from the affection at read time — see the DTO field's own javadoc. */
    private Long resultFindingId(ResearchBoard b) {
        if (b.getResultAffectionId() == null) return null;
        return affectionRepo.findById(b.getResultAffectionId()).map(com.martecyber.ares.affections.Affection::getFindingId).orElse(null);
    }

    private List<ResearchBoardMemberDto> members(Long boardId) {
        return boardUserRepo.findByIdBoardId(boardId).stream()
            .map(bu -> {
                User user = userRepo.findById(bu.getId().getUserId()).orElse(null);
                return new ResearchBoardMemberDto(bu.getId().getUserId(),
                    user != null ? user.getEmail() : null,
                    user != null ? user.getDisplayName() : null,
                    bu.getAddedAt());
            })
            .toList();
    }

    private String leadName(Long leadUserId) {
        if (leadUserId == null) return null;
        return userRepo.findById(leadUserId).map(User::getDisplayName).orElse(null);
    }
}
