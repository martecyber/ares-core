package com.martecyber.ares.kb.attack;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AttackTechniqueMitigationRepository extends JpaRepository<AttackTechniqueMitigation, AttackTechniqueMitigationId> {

    @Query("SELECT atm.mitigationId FROM AttackTechniqueMitigation atm WHERE atm.techniqueId = :techniqueId")
    java.util.List<Long> findMitigationIdsByTechniqueId(@Param("techniqueId") Long techniqueId);

    /** Same re-resolve-and-reinsert-per-matrix-sync approach as AttackTechniqueTacticRepository —
     *  see its own doc comment. Scoped via the mitigation side (equally valid via technique;
     *  either FK column identifies rows belonging to one matrix's sync since neither mitigation
     *  nor technique in this table is ever shared across matrices — attack_id/matrix pairs are
     *  matrix-specific by construction). {@code @Transactional} required — see
     *  AttackTechniqueTacticRepository.deleteByMatrix's own doc comment for why. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM ares.attack_technique_mitigation atm " +
        "USING ares.attack_mitigation m WHERE atm.mitigation_id = m.id AND m.matrix = :matrix",
        nativeQuery = true)
    void deleteByMatrix(@Param("matrix") String matrix);
}
