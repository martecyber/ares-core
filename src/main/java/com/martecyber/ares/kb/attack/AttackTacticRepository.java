package com.martecyber.ares.kb.attack;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttackTacticRepository extends JpaRepository<AttackTactic, Long> {

    Optional<AttackTactic> findByAttackIdAndMatrix(String attackId, String matrix);

    List<AttackTactic> findByMatrixOrderByOrderAsc(String matrix);

    Page<AttackTactic> findByMatrix(String matrix, Pageable pageable);

    void deleteByMatrix(String matrix);
}
