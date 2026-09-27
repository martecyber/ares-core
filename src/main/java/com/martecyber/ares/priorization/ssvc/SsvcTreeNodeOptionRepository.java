package com.martecyber.ares.priorization.ssvc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SsvcTreeNodeOptionRepository extends JpaRepository<SsvcTreeNodeOption, Long> {
    @Query("""
        SELECT o FROM SsvcTreeNodeOption o
        WHERE o.treeNodeId IN (SELECT n.id FROM SsvcTreeNode n WHERE n.roleId = :roleId)
        ORDER BY o.sortOrder ASC
        """)
    List<SsvcTreeNodeOption> findByRoleId(@Param("roleId") Long roleId);

    @Query("SELECT o FROM SsvcTreeNodeOption o WHERE o.treeNodeId = :nodeId ORDER BY o.sortOrder ASC")
    List<SsvcTreeNodeOption> findByTreeNodeId(@Param("nodeId") Long nodeId);
}
