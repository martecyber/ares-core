package com.martecyber.ares.projects;

import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ScopeClassifyScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScopeClassifyScheduler.class);

    private final AssetScopeClassifier classifier;
    private final JobService jobService;

    private final ConcurrentHashMap<Long, Semaphore>    semaphores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicBoolean> needsRerun = new ConcurrentHashMap<>();

    // Platform-wide third-party sweep isn't project-scoped, so it gets its own single
    // semaphore/flag pair instead of the per-project maps above.
    private final Semaphore thirdPartySemaphore = new Semaphore(1);
    private final AtomicBoolean thirdPartyNeedsRerun = new AtomicBoolean();

    public ScopeClassifyScheduler(AssetScopeClassifier classifier, JobService jobService) {
        this.classifier = classifier;
        this.jobService = jobService;
    }

    /**
     * Schedules an async scope reclassification for the project.
     * At most one run is queued per project — if already running, a re-run flag is set
     * so the current run loops once more after finishing.
     */
    @Async
    public void schedule(Long projectId) {
        needsRerun.computeIfAbsent(projectId, k -> new AtomicBoolean()).set(true);
        Semaphore sem = semaphores.computeIfAbsent(projectId, k -> new Semaphore(1));
        if (!sem.tryAcquire()) {
            return;
        }
        try {
            while (needsRerun.getOrDefault(projectId, new AtomicBoolean(false)).getAndSet(false)) {
                try {
                    classifier.classify(projectId);
                } catch (Exception e) {
                    log.error("Scope classification failed for project {}: {}", projectId, e.getMessage(), e);
                }
            }
        } finally {
            sem.release();
        }
    }

    /** Schedule a full reclassify for every project that contains the given asset. */
    @Async
    public void scheduleForAsset(Long assetId) {
        try {
            classifier.classifyForAsset(assetId);
        } catch (Exception e) {
            log.error("Scope classification for asset {} failed: {}", assetId, e.getMessage(), e);
        }
    }

    /**
     * Schedules an async platform-wide third-party reclassification (every project, every
     * organization). Triggered whenever a KB third-party entry is added, edited, or enabled,
     * since that's a global pattern change, not scoped to one project. Same debounce
     * behavior as {@link #schedule(Long)} — at most one run in flight, re-runs once more
     * if requested again while already running.
     */
    @Async
    public void scheduleThirdPartyReclassify() {
        thirdPartyNeedsRerun.set(true);
        if (!thirdPartySemaphore.tryAcquire()) {
            return;
        }
        try {
            while (thirdPartyNeedsRerun.getAndSet(false)) {
                try {
                    int updated = classifier.reclassifyThirdPartyPlatformWide();
                    if (updated > 0) {
                        log.info("Reclassified {} asset(s) as third-party platform-wide", updated);
                    }
                } catch (Exception e) {
                    log.error("Platform-wide third-party reclassification failed: {}", e.getMessage(), e);
                }
            }
        } finally {
            thirdPartySemaphore.release();
        }
    }

    /**
     * Runs the platform-wide third-party sweep once, immediately, tracked as a Job — used by
     * the manual "Force reclassify" button. Distinct from {@link #scheduleThirdPartyReclassify()}
     * (the debounced sweep fired automatically on KB entry create/update/toggle): this always
     * runs right away and reports progress/result via {@link JobService}, no debounce.
     */
    @Async
    public void runThirdPartyReclassifyJobAsync(Long jobId) {
        jobService.update(jobId, new UpdateJobRequest("running", 0, null, null));
        try {
            int updated = classifier.reclassifyThirdPartyPlatformWide();
            jobService.update(jobId, new UpdateJobRequest("completed", 100,
                "{\"updated\":" + updated + "}", null));
        } catch (Exception e) {
            log.error("Manual third-party reclassify job {} failed: {}", jobId, e.getMessage(), e);
            jobService.update(jobId, new UpdateJobRequest("failed", null, null, e.getMessage()));
        }
    }

    /** True while a classify is actively running for this project. */
    public boolean isClassifying(Long projectId) {
        Semaphore sem = semaphores.get(projectId);
        return sem != null && sem.availablePermits() == 0;
    }
}
