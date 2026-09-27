package com.martecyber.ares.kb.wordlists;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kb/wordlists/repos")
public class KbWordlistRepoController {

    private final KbWordlistRepoRepository repoRepo;
    private final GithubRepoSyncService syncService;

    public KbWordlistRepoController(KbWordlistRepoRepository repoRepo,
                                     GithubRepoSyncService syncService) {
        this.repoRepo = repoRepo;
        this.syncService = syncService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<KbWordlistRepoDto> list() {
        return repoRepo.findAll().stream().map(KbWordlistRepoDto::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public KbWordlistRepoDto create(@RequestBody CreateRepoRequest req) {
        KbWordlistRepo r = new KbWordlistRepo();
        r.setRepoUrl(req.repoUrl());
        r.setBranch(req.branch() != null ? req.branch() : "main");
        r.setPathFilter(req.pathFilter());
        r.setGithubToken(req.githubToken());
        r.setImportAllTypes(req.importAllTypes());
        r.setAutoSync(req.autoSync());
        r.setSyncIntervalHours(req.syncIntervalHours() > 0 ? req.syncIntervalHours() : 24);
        r.setLastSyncStatus("idle");
        r.setCreatedAt(OffsetDateTime.now());
        return KbWordlistRepoDto.from(repoRepo.save(r));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public KbWordlistRepoDto update(@PathVariable Long id, @RequestBody UpdateRepoRequest req) {
        KbWordlistRepo r = repoRepo.findById(id).orElseThrow();
        if (req.branch() != null)           r.setBranch(req.branch());
        if (req.pathFilter() != null)        r.setPathFilter(req.pathFilter());
        if (req.githubToken() != null)       r.setGithubToken(req.githubToken().isBlank() ? null : req.githubToken());
        if (req.importAllTypes() != null)    r.setImportAllTypes(req.importAllTypes());
        if (req.autoSync() != null)          r.setAutoSync(req.autoSync());
        if (req.syncIntervalHours() != null) r.setSyncIntervalHours(req.syncIntervalHours());
        return KbWordlistRepoDto.from(repoRepo.save(r));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repoRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public Map<String, Object> sync(@PathVariable Long id) {
        Long jobId = syncService.syncAsync(id);
        return Map.of("status", "sync_started", "jobId", jobId);
    }

    public record CreateRepoRequest(
        String repoUrl, String branch, String pathFilter,
        String githubToken, boolean importAllTypes, boolean autoSync, int syncIntervalHours
    ) {}

    public record UpdateRepoRequest(
        String branch, String pathFilter, String githubToken,
        Boolean importAllTypes, Boolean autoSync, Integer syncIntervalHours
    ) {}
}
