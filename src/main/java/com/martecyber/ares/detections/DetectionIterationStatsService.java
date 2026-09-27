package com.martecyber.ares.detections;

import com.martecyber.ares.projects.MonitorIterationHelper;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Records, for MONITOR-type projects only, a snapshot of which detections entered which
 * monitoring "area" (open / escalated / closed) during which iteration — see V122's migration
 * comment for the exact semantics (a detection can land in more than one area within the same
 * iteration; re-entering the same area again in the same iteration is a no-op).
 *
 * Hooked into the single choke point every detection status transition already passes through
 * (DetectionService.recordHistory / ImportService.recordHistory), so no call site needs to know
 * this exists — it's a no-op for any project without an iteration cadence configured.
 */
@Service
public class DetectionIterationStatsService {

    private static final Set<String> OPEN_STATUSES = Set.of("new", "reopened");
    // Detection status "escalated" was renamed to "affected" — this constant still names the
    // AREA_ESCALATED bucket (see DetectionIterationStat), a distinct, intentionally-unrenamed
    // vocabulary; only the status value it matches against changed.
    private static final String ESCALATED_STATUS = "affected";
    private static final Set<String> CLOSED_STATUSES = Set.of("fixed", "not_affected", "ignored");

    private final DetectionIterationStatRepository statRepo;
    private final DetectionRepository detectionRepo;
    private final ProjectRepository projectRepo;

    public DetectionIterationStatsService(DetectionIterationStatRepository statRepo,
                                           DetectionRepository detectionRepo,
                                           ProjectRepository projectRepo) {
        this.statRepo = statRepo;
        this.detectionRepo = detectionRepo;
        this.projectRepo = projectRepo;
    }

    /** Maps a detection status to the monitoring area it represents, or null if the status
     *  isn't tracked (there currently aren't any, but this stays defensive for future statuses). */
    private static String areaFor(String status) {
        if (status == null) return null;
        if (OPEN_STATUSES.contains(status)) return DetectionIterationStat.AREA_OPEN;
        if (ESCALATED_STATUS.equals(status)) return DetectionIterationStat.AREA_ESCALATED;
        if (CLOSED_STATUSES.contains(status)) return DetectionIterationStat.AREA_CLOSED;
        return null;
    }

    public void record(Long detectionId, String toStatus, OffsetDateTime changedAt) {
        String area = areaFor(toStatus);
        if (area == null || changedAt == null) return;

        Detection detection = detectionRepo.findById(detectionId).orElse(null);
        if (detection == null) return;
        Project project = projectRepo.findById(detection.getProjectId()).orElse(null);
        if (project == null || project.getIterationCadence() == null) return;

        String label = MonitorIterationHelper.computeLabel(project.getIterationCadence(), changedAt.toLocalDate());
        if (label == null) return;

        if (statRepo.existsByProjectIdAndDetectionIdAndIterationLabelAndArea(
                project.getId(), detectionId, label, area)) {
            return;
        }
        statRepo.save(new DetectionIterationStat(
            project.getId(), detectionId, label, area, detection.getSeverity(), changedAt));
    }
}
