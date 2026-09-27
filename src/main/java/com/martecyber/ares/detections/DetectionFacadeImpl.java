package com.martecyber.ares.detections;

import com.martecyber.ares.detections.dto.CreateDetectionHttpSampleRequest;
import org.springframework.stereotype.Component;

/** Thin adapter exposing {@link DetectionService}/{@link DetectionHttpSampleService} to plugins
 *  as the {@code ares-sdk}-owned {@link DetectionFacade}. */
@Component
class DetectionFacadeImpl implements DetectionFacade {

    private final DetectionService detectionService;
    private final DetectionHttpSampleService detectionHttpSampleService;

    DetectionFacadeImpl(DetectionService detectionService, DetectionHttpSampleService detectionHttpSampleService) {
        this.detectionService = detectionService;
        this.detectionHttpSampleService = detectionHttpSampleService;
    }

    @Override
    public ExternalIngestResult ingestExternal(Long projectId, String sourceType, String externalId,
                                               Long assetId, String severity, String title,
                                               String description, String rawData) {
        DetectionService.IngestResult r = detectionService.ingestExternal(
            projectId, sourceType, externalId, assetId, severity, title, description, rawData);
        return new ExternalIngestResult(r.detection().getId(), r.created());
    }

    @Override
    public void attachHttpSample(Long detectionId, String label, String requestContent, String responseContent, String notes) {
        detectionHttpSampleService.create(detectionId,
            new CreateDetectionHttpSampleRequest(label, requestContent, responseContent, notes));
    }
}
