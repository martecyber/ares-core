package com.martecyber.ares.priorization.ssvc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SsvcTreeNodeRepository extends JpaRepository<SsvcTreeNode, Long> {
    @Query("SELECT n FROM SsvcTreeNode n WHERE n.roleId = :roleId ORDER BY n.sortOrder ASC")
    List<SsvcTreeNode> findByRoleId(@Param("roleId") Long roleId);

    @Query("SELECT n FROM SsvcTreeNode n WHERE n.roleId = :roleId AND n.parentNodeId IS NULL")
    Optional<SsvcTreeNode> findRootByRoleId(@Param("roleId") Long roleId);

    /** A plain derived delete method removes every matching row individually (one DELETE
     *  per entity, deferred to flush time) — but parent_node_id is self-referencing with
     *  ON DELETE CASCADE, so deleting the root row at flush time cascades away every
     *  descendant at the DB level, and Hibernate's own already-queued individual DELETE for
     *  one of those now-gone children then fails with "0 rows affected"
     *  (ObjectOptimisticLockingFailureException). A single bulk statement deletes the whole
     *  role's subtree atomically instead, so no follow-up DELETE can ever target an
     *  already-cascaded-away row. clearAutomatically evicts the now-stale managed node
     *  entities isLocked()'s earlier read may have loaded into the persistence context. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SsvcTreeNode n WHERE n.roleId = :roleId")
    void deleteByRoleId(@Param("roleId") Long roleId);
}
