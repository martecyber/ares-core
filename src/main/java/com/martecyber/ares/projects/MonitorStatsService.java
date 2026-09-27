package com.martecyber.ares.projects;

import com.martecyber.ares.detections.DetectionIterationStat;
import com.martecyber.ares.detections.DetectionIterationStatRepository;
import com.martecyber.ares.findings.FindingRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Provides monitoring statistics for MONITOR-type projects:
 *   - Findings by current status
 *   - Findings past their SLA due date
 *   - Per-iteration breakdown (reported / resolved / still open)
 *   - Per-iteration detection breakdown (opened / escalated / closed)
 */
@Service
public class MonitorStatsService {

    private final ProjectRepository projectRepo;
    private final FindingRepository findingRepo;
    private final DetectionIterationStatRepository detectionStatRepo;

    public MonitorStatsService(ProjectRepository projectRepo, FindingRepository findingRepo,
                               DetectionIterationStatRepository detectionStatRepo) {
        this.projectRepo = projectRepo;
        this.findingRepo = findingRepo;
        this.detectionStatRepo = detectionStatRepo;
    }

    public record IterationRow(
        String label,
        long reported,
        long resolved,
        long openAtEnd
    ) {}

    /** Detections that entered each area during this iteration — a detection can be counted in
     *  more than one column if it transitioned through multiple areas within the same iteration
     *  (e.g. opened then closed), see DetectionIterationStat's migration comment. */
    public record DetectionIterationRow(
        String label,
        long opened,
        long escalated,
        long closed
    ) {}

    public record MonitorStats(
        Map<String, Long> byStatus,
        long outOfSla,
        String currentIterationLabel,
        String pendingIterationLabel,
        boolean awaitingApproval,
        boolean autoAdvanceIterations,
        List<IterationRow> byIteration,
        List<DetectionIterationRow> detectionsByIteration
    ) {}

    public MonitorStats compute(Long projectId) {
        var project = projectRepo.findById(projectId).orElseThrow();

        // 1. Status breakdown
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : findingRepo.countByProjectGroupByStatus(projectId)) {
            byStatus.put((String) row[0], ((Number) row[1]).longValue());
        }

        // 2. Out-of-SLA count
        long outOfSla = findingRepo.findOutOfSlaByProject(projectId, LocalDate.now()).size();

        // 3. Current (active/authoritative) iteration label, plus what the calendar
        // currently says — they differ only when a manual-approval project's iteration
        // has run long and nobody has approved the jump to the next one yet.
        String currentLabel = MonitorIterationHelper.resolveActiveLabel(project, projectRepo);
        String pendingLabel = MonitorIterationHelper.computeLabel(
            project.getIterationCadence(), LocalDate.now());
        boolean awaitingApproval = !project.isAutoAdvanceIterations()
            && pendingLabel != null && !pendingLabel.equals(currentLabel);

        // 4. Per-iteration breakdown
        List<String> labels = findingRepo.findDistinctIterationLabels(projectId);
        List<IterationRow> rows = new ArrayList<>();
        for (String label : labels) {
            Map<String, Long> statusMap = new LinkedHashMap<>();
            for (Object[] row : findingRepo.countByProjectAndLabelGroupByStatus(projectId, label)) {
                statusMap.put((String) row[0], ((Number) row[1]).longValue());
            }
            long reported = statusMap.values().stream().mapToLong(l -> l).sum();
            long resolved = statusMap.getOrDefault("resolved", 0L)
                + statusMap.getOrDefault("accepted_risk", 0L)
                + statusMap.getOrDefault("false_positive", 0L);
            long open = statusMap.getOrDefault("open", 0L)
                + statusMap.getOrDefault("in_review", 0L)
                + statusMap.getOrDefault("pending_review", 0L);
            rows.add(new IterationRow(label, reported, resolved, open));
        }

        // 5. Per-iteration detection breakdown (opened / escalated / closed)
        List<String> detectionLabels = detectionStatRepo.findDistinctIterationLabels(projectId);
        List<DetectionIterationRow> detectionRows = new ArrayList<>();
        for (String label : detectionLabels) {
            Map<String, Long> areaMap = new LinkedHashMap<>();
            for (Object[] row : detectionStatRepo.countByProjectAndLabelGroupByArea(projectId, label)) {
                areaMap.put((String) row[0], ((Number) row[1]).longValue());
            }
            detectionRows.add(new DetectionIterationRow(
                label,
                areaMap.getOrDefault(DetectionIterationStat.AREA_OPEN, 0L),
                areaMap.getOrDefault(DetectionIterationStat.AREA_ESCALATED, 0L),
                areaMap.getOrDefault(DetectionIterationStat.AREA_CLOSED, 0L)));
        }

        return new MonitorStats(byStatus, outOfSla, currentLabel,
            awaitingApproval ? pendingLabel : null, awaitingApproval,
            project.isAutoAdvanceIterations(), rows, detectionRows);
    }
}
