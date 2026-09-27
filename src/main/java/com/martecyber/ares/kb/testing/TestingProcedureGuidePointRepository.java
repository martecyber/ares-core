package com.martecyber.ares.kb.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestingProcedureGuidePointRepository
        extends JpaRepository<TestingProcedureGuidePoint, TestingProcedureGuidePoint.Id> {

    List<TestingProcedureGuidePoint> findByProcedureId(Long procedureId);

    void deleteByProcedureId(Long procedureId);
}
