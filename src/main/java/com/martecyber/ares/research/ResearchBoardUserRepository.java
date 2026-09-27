package com.martecyber.ares.research;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ResearchBoardUserRepository extends JpaRepository<ResearchBoardUser, ResearchBoardUserId> {
    List<ResearchBoardUser> findByIdBoardId(Long boardId);
}
