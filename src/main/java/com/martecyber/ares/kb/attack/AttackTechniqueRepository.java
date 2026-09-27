package com.martecyber.ares.kb.attack;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AttackTechniqueRepository extends JpaRepository<AttackTechnique, Long>, JpaSpecificationExecutor<AttackTechnique> {

    Optional<AttackTechnique> findByAttackIdAndMatrix(String attackId, String matrix);

    Page<AttackTechnique> findByMatrix(String matrix, Pageable pageable);

    /** Native query, not a JPA Criteria/HQL one — deliberately, so the bare "array_position" call
     *  is passed straight through to Postgres rather than routed through Hibernate's own
     *  registered "array_position" function (which only intercepts Criteria/HQL-compiled calls,
     *  not native SQL strings) — see PostgresSpecificationCompiler.arrayContainsPredicate's own
     *  doc comment for the collision this session's Phase 3 verification found and fixed. */
    @Query(value = "SELECT * FROM ares.attack_technique WHERE matrix = :matrix AND array_position(tactics, :tactic) IS NOT NULL",
           countQuery = "SELECT count(*) FROM ares.attack_technique WHERE matrix = :matrix AND array_position(tactics, :tactic) IS NOT NULL",
           nativeQuery = true)
    Page<AttackTechnique> findByMatrixAndTacticsContaining(@Param("matrix") String matrix, @Param("tactic") String tactic, Pageable pageable);

    Page<AttackTechnique> findByMatrixAndSubtechnique(String matrix, boolean subtechnique, Pageable pageable);

    @Query("SELECT t FROM AttackTechnique t WHERE t.matrix = :matrix AND (" +
        "LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
        "LOWER(t.attackId) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<AttackTechnique> search(@Param("matrix") String matrix, @Param("keyword") String keyword, Pageable pageable);

    List<AttackTechnique> findAllByMatrixOrderByAttackIdAsc(String matrix);

    List<AttackTechnique> findAllByAttackIdIn(Collection<String> ids);

    List<AttackTechnique> findByMatrixAndAttackIdStartingWith(String matrix, String attackIdPrefix);

    void deleteByMatrix(String matrix);
}
