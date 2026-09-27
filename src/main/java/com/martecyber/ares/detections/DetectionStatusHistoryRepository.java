package com.martecyber.ares.detections;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DetectionStatusHistoryRepository extends JpaRepository<DetectionStatusHistory, Long> {

    /** Newest first — page 0 is "what just happened," matching every other paginated audit trail
     *  in this codebase (see DetectionService#getHistory). A long-lived MONITOR project's detection
     *  can accumulate hundreds of "reseen" entries across rescans, so this is paginated rather than
     *  returning the whole history in one response. */
    Page<DetectionStatusHistory> findByDetectionIdOrderByChangedAtDesc(Long detectionId, Pageable pageable);

    /** Bulk fetch for backfilling DetectionIterationStat from history across a whole project's
     *  detections — one query instead of one per detection. Chronological (not paginated): this
     *  feeds a stat-recomputation pass that needs every row, not a page for display. */
    List<DetectionStatusHistory> findByDetectionIdInOrderByChangedAtAsc(List<Long> detectionIds);
}
