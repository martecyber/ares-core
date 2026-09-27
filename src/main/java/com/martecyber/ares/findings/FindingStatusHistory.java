package com.martecyber.ares.findings;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "finding_status_history", schema = "ares")
public class FindingStatusHistory {

    @EmbeddedId
    private FindingStatusHistoryId id;

    public FindingStatusHistory() {}

    public FindingStatusHistory(Long findingId, Long findingStatusId, OffsetDateTime changedAt) {
        this.id = new FindingStatusHistoryId(findingId, findingStatusId, changedAt);
    }

    public FindingStatusHistoryId getId() { return id; }
    public void setId(FindingStatusHistoryId id) { this.id = id; }

    @Embeddable
    public static class FindingStatusHistoryId implements Serializable {

        @Column(name = "finding_id")
        private Long findingId;

        @Column(name = "finding_status_id")
        private Long findingStatusId;

        @Column(name = "changed_at")
        private OffsetDateTime changedAt;

        public FindingStatusHistoryId() {}

        public FindingStatusHistoryId(Long findingId, Long findingStatusId, OffsetDateTime changedAt) {
            this.findingId = findingId;
            this.findingStatusId = findingStatusId;
            this.changedAt = changedAt;
        }

        public Long getFindingId() { return findingId; }
        public void setFindingId(Long findingId) { this.findingId = findingId; }

        public Long getFindingStatusId() { return findingStatusId; }
        public void setFindingStatusId(Long findingStatusId) { this.findingStatusId = findingStatusId; }

        public OffsetDateTime getChangedAt() { return changedAt; }
        public void setChangedAt(OffsetDateTime changedAt) { this.changedAt = changedAt; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FindingStatusHistoryId that)) return false;
            return Objects.equals(findingId, that.findingId) &&
                   Objects.equals(findingStatusId, that.findingStatusId) &&
                   Objects.equals(changedAt, that.changedAt);
        }

        @Override
        public int hashCode() { return Objects.hash(findingId, findingStatusId, changedAt); }
    }
}
