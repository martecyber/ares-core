package com.martecyber.ares.kb.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestingProcedureExternalRefRepository
        extends JpaRepository<TestingProcedureExternalRef, Long> {

    List<TestingProcedureExternalRef> findByProcedureId(Long procedureId);

    void deleteByProcedureId(Long procedureId);
}
