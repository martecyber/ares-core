package com.martecyber.ares.findings;

import com.martecyber.ares.common.NotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/finding-statuses")
public class FindingStatusController {

    private final FindingStatusRepository repo;
    private final FindingStatusTransitionRepository transitionRepo;

    public FindingStatusController(FindingStatusRepository repo,
                                   FindingStatusTransitionRepository transitionRepo) {
        this.repo = repo;
        this.transitionRepo = transitionRepo;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<FindingStatus> list() {
        return repo.findAll();
    }

    /** Returns the statuses that are valid transition targets from the given status. */
    @GetMapping("/{id}/transitions")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<FindingStatus> transitions(@PathVariable Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("finding_status", id);
        List<Long> toIds = transitionRepo.findToStatusIdsByFromStatusId(id);
        return repo.findAllById(toIds);
    }
}
