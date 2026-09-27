package com.martecyber.ares.research;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ResearchBoardDetectionRepository extends JpaRepository<ResearchBoardDetection, ResearchBoardDetectionId> {
    List<ResearchBoardDetection> findByIdBoardIdAndRemovedAtIsNull(Long boardId);

    /** At most one active-board membership per detection — enforced in ResearchBoardService, not
     *  the schema; this is how it's checked before adding a detection to a different board. */
    Optional<ResearchBoardDetection> findByIdDetectionIdAndRemovedAtIsNull(Long detectionId);
}
