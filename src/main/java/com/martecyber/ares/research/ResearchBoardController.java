package com.martecyber.ares.research;

import com.martecyber.ares.research.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/research-boards")
@PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
public class ResearchBoardController {

    private final ResearchBoardService service;

    public ResearchBoardController(ResearchBoardService service) {
        this.service = service;
    }

    @GetMapping
    public List<ResearchBoardSummaryDto> list(@PathVariable Long projectId,
                                               @RequestParam(defaultValue = "false") boolean archived) {
        return service.list(projectId, archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchBoardDetailDto create(@PathVariable Long projectId,
                                          @Valid @RequestBody CreateResearchBoardRequest req) {
        return service.create(projectId, req);
    }

    @GetMapping("/{id}")
    public ResearchBoardDetailDto get(@PathVariable Long projectId, @PathVariable Long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public ResearchBoardDetailDto update(@PathVariable Long projectId, @PathVariable Long id,
                                          @RequestBody UpdateResearchBoardRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long projectId, @PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/detections")
    public ResearchBoardDetailDto addDetections(@PathVariable Long projectId, @PathVariable Long id,
                                                 @Valid @RequestBody AddDetectionsRequest req) {
        return service.addDetections(id, req);
    }

    /** Plain removal when neither param is given; moves the detection to another board (existing
     *  or brand new) when one is. */
    @DeleteMapping("/{id}/detections/{detectionId}")
    public ResearchBoardDetailDto removeDetection(@PathVariable Long projectId, @PathVariable Long id,
                                                   @PathVariable Long detectionId,
                                                   @RequestParam(required = false) Long moveToBoardId,
                                                   @RequestParam(required = false) String newBoardTitle) {
        return service.removeDetection(id, detectionId, moveToBoardId, newBoardTitle);
    }

    @PostMapping("/{id}/members")
    public ResearchBoardDetailDto addMember(@PathVariable Long projectId, @PathVariable Long id,
                                             @Valid @RequestBody AddResearchBoardMemberRequest req) {
        return service.addMember(id, req);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResearchBoardDetailDto removeMember(@PathVariable Long projectId, @PathVariable Long id,
                                                @PathVariable Long userId) {
        return service.removeMember(id, userId);
    }

    /** {@code id} is the source board (deleted); {@code req.targetBoardId} survives with the
     *  merged title/notes/detections/members. */
    @PostMapping("/{id}/merge")
    public ResearchBoardDetailDto merge(@PathVariable Long projectId, @PathVariable Long id,
                                         @Valid @RequestBody MergeResearchBoardsRequest req) {
        return service.merge(id, req.targetBoardId(), req.title());
    }

    /** Called after the escalation wizard has already created/picked the finding+affection and
     *  linked the board's detections to it — see {@link ResearchBoardService#archiveAsAffected}. */
    @PostMapping("/{id}/escalate")
    public ResearchBoardDetailDto escalate(@PathVariable Long projectId, @PathVariable Long id,
                                            @Valid @RequestBody ArchiveAsAffectedRequest req) {
        return service.archiveAsAffected(id, req.affectionId());
    }

    @PostMapping("/{id}/not-affected")
    public ResearchBoardDetailDto notAffected(@PathVariable Long projectId, @PathVariable Long id,
                                               @RequestBody(required = false) NotAffectedRequest req) {
        return service.notAffected(id, req != null ? req.note() : null);
    }
}
