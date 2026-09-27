package com.martecyber.ares.research;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ResearchBoardRepository extends JpaRepository<ResearchBoard, Long> {
    List<ResearchBoard> findByProjectIdAndStatusOrderByUpdatedAtDesc(Long projectId, String status);
}
