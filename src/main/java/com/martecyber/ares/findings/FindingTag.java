package com.martecyber.ares.findings;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "finding_tag", schema = "ares")
@IdClass(FindingTagId.class)
public class FindingTag {

    @Id
    @Column(name = "finding_id")
    private Long findingId;

    @Id
    @Column(name = "tag_id")
    private Long tagId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getFindingId() { return findingId; }
    public void setFindingId(Long findingId) { this.findingId = findingId; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
