package com.martecyber.ares.assets;

import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.JobDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manual, admin-triggered one-time repairs — see {@link AssetRepairScheduler}. */
@RestController
@RequestMapping("/api/v1/admin/assets/repair")
public class AssetRepairController {

    private final JobService jobService;
    private final AssetRepairScheduler scheduler;

    public AssetRepairController(JobService jobService, AssetRepairScheduler scheduler) {
        this.jobService = jobService;
        this.scheduler = scheduler;
    }

    /** Recomputes NETWORK/IP and DOMAIN containment hierarchies (all organizations) so any
     *  intermediate networks/domains inserted out of order end up with the correct
     *  nearest-container relationships instead of a stale direct link. */
    @PostMapping("/topology")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public Long repairTopology() {
        JobDto job = jobService.create(new CreateJobRequest("ASSET_TOPOLOGY_REPAIR", null, null, null));
        scheduler.runTopologyRepairJobAsync(job.id());
        return job.id();
    }
}
