package com.martecyber.ares.imports;

import org.springframework.stereotype.Component;

/** Thin adapter exposing {@link ImportService#processParseResult} to plugins as the {@code
 *  ares-sdk}-owned {@link IngestFacade}. */
@Component
class IngestFacadeImpl implements IngestFacade {

    private final ImportService importService;

    IngestFacadeImpl(ImportService importService) {
        this.importService = importService;
    }

    @Override
    public IngestResult ingest(Long projectId, Long organizationId, String sourceType, ParseResult parsed) {
        ImportResult r = importService.processParseResult(projectId, organizationId, sourceType, parsed);
        return new IngestResult(r.getAssetsCreated(), r.getDetectionsCreated(), r.getDetectionsUpdated(), r.getWarnings());
    }
}
