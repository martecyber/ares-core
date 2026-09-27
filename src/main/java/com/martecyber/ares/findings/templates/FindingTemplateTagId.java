package com.martecyber.ares.findings.templates;

import java.io.Serializable;
import java.util.Objects;

public class FindingTemplateTagId implements Serializable {
    private Long findingTemplateId;
    private Long tagId;

    public FindingTemplateTagId() {}
    public FindingTemplateTagId(Long findingTemplateId, Long tagId) {
        this.findingTemplateId = findingTemplateId;
        this.tagId = tagId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FindingTemplateTagId that)) return false;
        return Objects.equals(findingTemplateId, that.findingTemplateId) && Objects.equals(tagId, that.tagId);
    }
    @Override public int hashCode() { return Objects.hash(findingTemplateId, tagId); }
}
