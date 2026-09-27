package com.martecyber.ares.assets;

import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import com.martecyber.ares.organizations.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs the one-time network/domain topology repair as a tracked Job — the async counterpart to
 * {@link AssetService#recomputeNetworkTopology}/{@link AssetService#recomputeDomainTopology},
 * mirroring how {@code ScopeClassifyScheduler.runThirdPartyReclassifyJobAsync} runs the "Force
 * reclassify" button's work: runs immediately (no debounce), reports progress/result via
 * {@link JobService}.
 */
@Service
public class AssetRepairScheduler {

    private static final Logger log = LoggerFactory.getLogger(AssetRepairScheduler.class);

    private final AssetService assetService;
    private final OrganizationRepository orgRepo;
    private final JobService jobService;

    public AssetRepairScheduler(AssetService assetService, OrganizationRepository orgRepo, JobService jobService) {
        this.assetService = assetService;
        this.orgRepo = orgRepo;
        this.jobService = jobService;
    }

    @Async
    public void runTopologyRepairJobAsync(Long jobId) {
        jobService.update(jobId, new UpdateJobRequest("running", 0, null, null));
        try {
            for (var org : orgRepo.findAll()) {
                assetService.recomputeNetworkTopology(org.getId());
                assetService.recomputeDomainTopology(org.getId());
            }
            jobService.update(jobId, new UpdateJobRequest("completed", 100, null, null));
        } catch (Exception e) {
            log.error("Asset topology repair job {} failed: {}", jobId, e.getMessage(), e);
            jobService.update(jobId, new UpdateJobRequest("failed", null, null, e.getMessage()));
        }
    }
}
