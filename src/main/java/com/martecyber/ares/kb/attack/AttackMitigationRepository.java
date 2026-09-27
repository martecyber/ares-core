package com.martecyber.ares.kb.attack;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttackMitigationRepository extends JpaRepository<AttackMitigation, Long>, JpaSpecificationExecutor<AttackMitigation> {

    Optional<AttackMitigation> findByAttackIdAndMatrix(String attackId, String matrix);

    Page<AttackMitigation> findByMatrix(String matrix, Pageable pageable);

    @Query("SELECT m FROM AttackMitigation m WHERE m.matrix = :matrix AND (" +
        "LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
        "LOWER(m.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<AttackMitigation> search(@Param("matrix") String matrix, @Param("keyword") String keyword, Pageable pageable);

    List<AttackMitigation> findAllByMatrixOrderByAttackIdAsc(String matrix);

    void deleteByMatrix(String matrix);
}
