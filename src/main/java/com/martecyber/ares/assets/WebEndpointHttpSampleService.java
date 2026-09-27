package com.martecyber.ares.assets;

import com.martecyber.ares.assets.dto.CreateWebEndpointHttpSampleRequest;
import com.martecyber.ares.assets.dto.WebEndpointHttpSampleDto;
import com.martecyber.ares.common.NotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class WebEndpointHttpSampleService {

    private final WebEndpointHttpSampleRepository repo;
    private final AssetRepository assetRepo;

    public WebEndpointHttpSampleService(WebEndpointHttpSampleRepository repo, AssetRepository assetRepo) {
        this.repo = repo;
        this.assetRepo = assetRepo;
    }

    public List<WebEndpointHttpSampleDto> listByEndpoint(Long assetId) {
        ensureEndpoint(assetId);
        return repo.findByAssetId(assetId).stream().map(WebEndpointHttpSampleDto::from).toList();
    }

    @Transactional
    public WebEndpointHttpSampleDto create(Long assetId, CreateWebEndpointHttpSampleRequest req) {
        ensureEndpoint(assetId);
        WebEndpointHttpSample s = new WebEndpointHttpSample();
        s.setAssetId(assetId);
        s.setLabel(req.label());
        s.setRequestContent(req.requestContent());
        s.setResponseContent(req.responseContent());
        s.setNotes(req.notes());
        OffsetDateTime now = OffsetDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return WebEndpointHttpSampleDto.from(repo.save(s));
    }

    @Transactional
    public WebEndpointHttpSampleDto update(Long assetId, Long sampleId, CreateWebEndpointHttpSampleRequest req) {
        WebEndpointHttpSample s = repo.findById(sampleId)
            .filter(x -> x.getAssetId().equals(assetId))
            .orElseThrow(() -> NotFoundException.of("http_sample", sampleId));
        if (req.label() != null)           s.setLabel(req.label());
        if (req.requestContent() != null)  s.setRequestContent(req.requestContent());
        if (req.responseContent() != null) s.setResponseContent(req.responseContent());
        if (req.notes() != null)            s.setNotes(req.notes());
        s.setUpdatedAt(OffsetDateTime.now());
        return WebEndpointHttpSampleDto.from(repo.save(s));
    }

    @Transactional
    public void delete(Long assetId, Long sampleId) {
        WebEndpointHttpSample s = repo.findById(sampleId)
            .filter(x -> x.getAssetId().equals(assetId))
            .orElseThrow(() -> NotFoundException.of("http_sample", sampleId));
        repo.delete(s);
    }

    private void ensureEndpoint(Long assetId) {
        Asset asset = assetRepo.findById(assetId)
            .orElseThrow(() -> NotFoundException.of("asset", assetId));
        if (!AssetType.WEB_ENDPOINT.equals(asset.getType())) {
            throw new IllegalArgumentException(
                "Asset " + assetId + " is not a web_endpoint — HTTP samples only apply to endpoints");
        }
    }
}
