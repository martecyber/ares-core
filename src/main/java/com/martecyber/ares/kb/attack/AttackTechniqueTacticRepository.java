package com.martecyber.ares.kb.attack;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AttackTechniqueTacticRepository extends JpaRepository<AttackTechniqueTactic, AttackTechniqueTacticId> {

    /** Every technique in a matrix is re-resolved and re-inserted on each sync (see
     *  AttackService.saveMatrix) — simplest correct way to keep this join table consistent with
     *  whatever the current tactics text[] + tactic rows say, without a separate diff/reconcile
     *  step. Scoped to one matrix at a time so re-syncing matrix A never touches matrix B's rows.
     *  {@code @Transactional} is required here (unlike the plain save()/saveAll() calls elsewhere
     *  in this codebase, which Spring Data JPA already wraps transactionally inside {@code
     *  SimpleJpaRepository}) — a custom {@code @Modifying} query needs it explicitly, or calling it
     *  throws {@code TransactionRequiredException} at runtime (a real bug this session's live
     *  ATT&CK sync verification caught: "Executing an update/delete query" with no transaction). */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM ares.attack_technique_tactic att " +
        "USING ares.attack_technique t WHERE att.technique_id = t.id AND t.matrix = :matrix",
        nativeQuery = true)
    void deleteByMatrix(@Param("matrix") String matrix);
}
