package com.martecyber.ares.jobs;

import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.JobDto;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import org.springframework.stereotype.Component;

/** Thin adapter exposing {@link JobService} to plugins as the {@code ares-sdk}-owned {@link
 *  JobFacade} — no new business logic, just DTO translation so a plugin never needs {@code
 *  JobRepository}/{@code Job} (ares-core-internal JPA types) on its classpath. */
@Component
class JobFacadeImpl implements JobFacade {

    private final JobService jobService;

    JobFacadeImpl(JobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public boolean existsActive(Long projectId, String type) {
        return jobService.existsActiveByProjectAndType(projectId, type);
    }

    @Override
    public JobView create(String type, Long organizationId, Long projectId, String payload) {
        return toView(jobService.create(new CreateJobRequest(type, organizationId, payload, projectId)));
    }

    @Override
    public JobView update(Long jobId, String status, Integer progress, String result, String error) {
        return toView(jobService.update(jobId, new UpdateJobRequest(status, progress, result, error)));
    }

    @Override
    public JobView get(Long jobId) {
        return toView(jobService.get(jobId));
    }

    @Override
    public boolean isCancelled(Long jobId) {
        return jobService.isCancelled(jobId);
    }

    private static JobView toView(JobDto dto) {
        return new JobView(dto.id(), dto.status(), dto.result(), dto.error(), dto.progress());
    }
}
