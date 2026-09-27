package com.martecyber.ares.jobs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;

public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {

    long countByStatusIn(Collection<String> statuses);

    boolean existsByIdAndStatus(Long id, String status);

    boolean existsByProjectIdAndTypeAndStatusIn(Long projectId, String type, Collection<String> statuses);

    java.util.List<Job> findByStatusIn(java.util.Collection<String> statuses);

    @Modifying
    @Query("DELETE FROM Job j WHERE j.createdAt < :cutoff AND j.status IN ('completed', 'failed', 'cancelled')")
    int deleteTerminalOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
