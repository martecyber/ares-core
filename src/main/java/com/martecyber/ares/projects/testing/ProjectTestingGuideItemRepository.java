package com.martecyber.ares.projects.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectTestingGuideItemRepository extends JpaRepository<ProjectTestingGuideItem, Long> {

    List<ProjectTestingGuideItem> findByProjectTestingGuideIdOrderBySortOrderAscIdAsc(Long projectTestingGuideId);

    List<ProjectTestingGuideItem> findByProjectTestingGuideIdIn(List<Long> ptgIds);
}
