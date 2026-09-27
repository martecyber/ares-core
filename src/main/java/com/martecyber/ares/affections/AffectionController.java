package com.martecyber.ares.affections;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.findings.dto.FindingDto;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/affections")
public class AffectionController {

    private final AffectionService service;

    public AffectionController(AffectionService service) { this.service = service; }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto update(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        return service.update(id, body.get("title"), body.get("description"));
    }

    @PostMapping("/{id}/detected-at/{assetId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto addDetectedAt(@PathVariable Long id, @PathVariable Long assetId) {
        return service.addDetectedAt(id, assetId);
    }

    @DeleteMapping("/{id}/detected-at/{assetId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto removeDetectedAt(@PathVariable Long id, @PathVariable Long assetId) {
        return service.removeDetectedAt(id, assetId);
    }

    @PostMapping("/{id}/affects/{assetId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto addAffects(@PathVariable Long id, @PathVariable Long assetId) {
        return service.addAffects(id, assetId);
    }

    @DeleteMapping("/{id}/affects/{assetId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto removeAffects(@PathVariable Long id, @PathVariable Long assetId) {
        return service.removeAffects(id, assetId);
    }

    /**
     * Replaces the affects assets linked to a specific {@code detected_at} of
     * an affection. Body: {@code { "assetIds": [..] }} — empty list to clear.
     */
    @PutMapping("/{id}/detected-at/{detectedAssetId}/affects")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto setAffectsForDetected(@PathVariable Long id,
                                             @PathVariable Long detectedAssetId,
                                             @RequestBody Map<String, List<Long>> body) {
        return service.setAffectsForDetected(id, detectedAssetId, body.getOrDefault("assetIds", List.of()));
    }

    @PostMapping("/{id}/detections/{detectionId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto addDetection(@PathVariable Long id, @PathVariable Long detectionId) {
        return service.addDetection(id, detectionId);
    }

    @DeleteMapping("/{id}/detections/{detectionId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto removeDetection(@PathVariable Long id, @PathVariable Long detectionId) {
        return service.removeDetection(id, detectionId);
    }

    @PatchMapping("/{id}/affects/{assetId}/status")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto updateAffectStatus(@PathVariable Long id, @PathVariable Long assetId,
                                         @RequestBody Map<String, String> body) {
        return service.updateAffectStatus(id, assetId, body.get("status"), body.get("note"));
    }

    @GetMapping("/{id}/affects/{assetId}/history")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<AffectStatusHistoryDto> getAffectHistory(
            @PathVariable Long id, @PathVariable Long assetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.getAffectHistory(id, assetId, page, size);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
