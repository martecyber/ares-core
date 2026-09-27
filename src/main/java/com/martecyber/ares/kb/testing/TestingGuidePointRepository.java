package com.martecyber.ares.kb.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestingGuidePointRepository extends JpaRepository<TestingGuidePoint, Long> {

    List<TestingGuidePoint> findByGuideIdOrderBySortOrderAscIdAsc(Long guideId);

    long countByGuideId(Long guideId);

    void deleteByGuideId(Long guideId);
}
