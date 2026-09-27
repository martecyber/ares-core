package com.martecyber.ares.projects;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Records that an existing finding (owned by its own, non-RETEST project) is in scope
 * for a RETEST project's verification pass. Purely a membership link — the finding's
 * content, affected-asset status and detections are never duplicated here; they're
 * edited through the finding's own existing endpoints, same as in its origin project.
 */
@Entity
@Table(name = "project_retest_finding", schema = "ares")
public class ProjectRetestFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "retest_project_id", nullable = false)
    private Long retestProjectId;

    @Column(name = "finding_id", nullable = false)
    private Long findingId;

    @Column(name = "linked_by")
    private Long linkedBy;

    @Column(name = "linked_at", nullable = false)
    private OffsetDateTime linkedAt;

    public Long getId() { return id; }

    public Long getRetestProjectId() { return retestProjectId; }
    public void setRetestProjectId(Long retestProjectId) { this.retestProjectId = retestProjectId; }

    public Long getFindingId() { return findingId; }
    public void setFindingId(Long findingId) { this.findingId = findingId; }

    public Long getLinkedBy() { return linkedBy; }
    public void setLinkedBy(Long linkedBy) { this.linkedBy = linkedBy; }

    public OffsetDateTime getLinkedAt() { return linkedAt; }
    public void setLinkedAt(OffsetDateTime linkedAt) { this.linkedAt = linkedAt; }
}
