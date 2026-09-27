package com.martecyber.ares.projects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ProjectStatusTransitionId implements Serializable {

    @Column(name = "from_status_id")
    private Long fromStatusId;

    @Column(name = "to_status_id")
    private Long toStatusId;

    public ProjectStatusTransitionId() {}

    public ProjectStatusTransitionId(Long fromStatusId, Long toStatusId) {
        this.fromStatusId = fromStatusId;
        this.toStatusId = toStatusId;
    }

    public Long getFromStatusId() { return fromStatusId; }
    public void setFromStatusId(Long fromStatusId) { this.fromStatusId = fromStatusId; }

    public Long getToStatusId() { return toStatusId; }
    public void setToStatusId(Long toStatusId) { this.toStatusId = toStatusId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectStatusTransitionId that)) return false;
        return Objects.equals(fromStatusId, that.fromStatusId) && Objects.equals(toStatusId, that.toStatusId);
    }

    @Override
    public int hashCode() { return Objects.hash(fromStatusId, toStatusId); }
}
