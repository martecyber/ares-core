package com.martecyber.ares.detections;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.detections.dto.CreateDetectionRequest;
import com.martecyber.ares.detections.dto.CreateDetectionHttpSampleRequest;
import com.martecyber.ares.detections.dto.DetectionDto;
import com.martecyber.ares.detections.dto.DetectionAffectionDto;
import com.martecyber.ares.detections.dto.DetectionHttpSampleDto;
import com.martecyber.ares.detections.dto.EscalateBatchRequest;
import com.martecyber.ares.detections.dto.EscalateDetectionRequest;
import com.martecyber.ares.detections.dto.EscalationResultDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/detections")
public class DetectionController {

    private final DetectionService svc;
    private final DetectionHttpSampleService httpSampleSvc;

    public DetectionController(DetectionService svc, DetectionHttpSampleService httpSampleSvc) {
        this.svc = svc;
        this.httpSampleSvc = httpSampleSvc;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public PagedResponse<DetectionDto> list(
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) java.util.List<Long> assetId,
        @RequestParam(required = false) java.util.List<String> severity,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.List<String> sourceType,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String aql,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int size
    ) {
        // When aql is present, discrete filter params (assetId/severity/status/sourceType/q) are
        // ignored rather than merged — combining two independent filtering modes invites
        // confusing double-filtering bugs (AQL implementation plan, Phase 1 API design).
        if (aql != null && !aql.isBlank()) {
            return PagedResponse.of(svc.listByAql(projectId, aql, sortBy, sortDir, page, size));
        }
        return PagedResponse.of(svc.list(projectId, assetId, severity, status, sourceType, q, sortBy, sortDir, page, size));
    }

    @GetMapping("/sources")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public java.util.List<String> sources(@RequestParam Long projectId) {
        return svc.listSources(projectId);
    }

    @GetMapping("/assets")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public java.util.List<DetectionService.AssetRef> detectionAssets(@RequestParam Long projectId) {
        return svc.listDetectionAssets(projectId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public DetectionDto get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DetectionDto create(@Valid @RequestBody CreateDetectionRequest req) { return svc.create(req); }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DetectionDto updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return svc.updateStatus(id, body.get("status"), body.get("note"));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public PagedResponse<com.martecyber.ares.detections.dto.DetectionStatusHistoryDto> history(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return svc.getHistory(id, page, size);
    }

    /**
     * Replaces the full list of affected assets for the detection. Body:
     * {@code { "assetIds": [1, 2, 3] }} — pass an empty list to clear.
     * Assets must belong to the same project as the detection.
     */
    @PutMapping("/{id}/affected-assets")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DetectionDto setAffectedAssets(@PathVariable Long id,
                                           @RequestBody Map<String, List<Long>> body) {
        return svc.replaceAffectedAssets(id, body.getOrDefault("assetIds", List.of()));
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DetectionDto escalate(@PathVariable Long id,
                                  @Valid @RequestBody EscalateDetectionRequest req) {
        return svc.escalate(id, req);
    }

    /** Multi-detection, existing-or-new-affection escalation — see {@link EscalateBatchRequest}. */
    @PostMapping("/escalate-batch")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public EscalationResultDto escalateBatch(@Valid @RequestBody EscalateBatchRequest req) {
        return svc.escalateBatch(req);
    }

    @GetMapping("/{id}/affections")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<DetectionAffectionDto> affections(@PathVariable Long id) {
        return svc.getAffections(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void delete(@PathVariable Long id) { svc.delete(id); }

    // ── Tags ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/tags/{tagId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DetectionDto assignTag(@PathVariable Long id, @PathVariable Long tagId) {
        return svc.assignTag(id, tagId);
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DetectionDto unassignTag(@PathVariable Long id, @PathVariable Long tagId) {
        return svc.unassignTag(id, tagId);
    }

    // ── HTTP request/response samples ───────────────────────────────────────────

    @GetMapping("/{id}/http-samples")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<DetectionHttpSampleDto> listHttpSamples(@PathVariable Long id) {
        return httpSampleSvc.listByDetection(id);
    }

    @PostMapping("/{id}/http-samples")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DetectionHttpSampleDto createHttpSample(@PathVariable Long id,
                                                   @RequestBody CreateDetectionHttpSampleRequest req) {
        return httpSampleSvc.create(id, req);
    }

    @PatchMapping("/{id}/http-samples/{sampleId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DetectionHttpSampleDto updateHttpSample(@PathVariable Long id,
                                                   @PathVariable Long sampleId,
                                                   @RequestBody CreateDetectionHttpSampleRequest req) {
        return httpSampleSvc.update(id, sampleId, req);
    }

    @DeleteMapping("/{id}/http-samples/{sampleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void deleteHttpSample(@PathVariable Long id, @PathVariable Long sampleId) {
        httpSampleSvc.delete(id, sampleId);
    }
}
