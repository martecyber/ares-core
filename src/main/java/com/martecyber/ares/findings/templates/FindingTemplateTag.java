package com.martecyber.ares.findings.templates;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "finding_template_tag", schema = "ares")
@IdClass(FindingTemplateTagId.class)
public class FindingTemplateTag {

    @Id
    @Column(name = "finding_template_id")
    private Long findingTemplateId;

    @Id
    @Column(name = "tag_id")
    private Long tagId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getFindingTemplateId() { return findingTemplateId; }
    public void setFindingTemplateId(Long findingTemplateId) { this.findingTemplateId = findingTemplateId; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
