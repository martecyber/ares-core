package com.martecyber.ares.findings;

import java.io.Serializable;
import java.util.Objects;

public class FindingTagId implements Serializable {
    private Long findingId;
    private Long tagId;

    public FindingTagId() {}
    public FindingTagId(Long findingId, Long tagId) {
        this.findingId = findingId;
        this.tagId = tagId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FindingTagId that)) return false;
        return Objects.equals(findingId, that.findingId) && Objects.equals(tagId, that.tagId);
    }
    @Override public int hashCode() { return Objects.hash(findingId, tagId); }
}
