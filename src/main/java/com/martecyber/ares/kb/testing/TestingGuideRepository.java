package com.martecyber.ares.kb.testing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestingGuideRepository extends JpaRepository<TestingGuide, Long> {

    Page<TestingGuide> findAllByOrderByNameAsc(Pageable pageable);
}
