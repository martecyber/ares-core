package com.martecyber.ares.kb.testing;

import jakarta.persistence.*;

/** Junction: a testing procedure linked to a testing-guide point. */
@Entity
@Table(name = "testing_procedure_guide_point", schema = "ares")
@IdClass(TestingProcedureGuidePoint.Id.class)
public class TestingProcedureGuidePoint {

    @jakarta.persistence.Id
    @Column(name = "procedure_id", nullable = false)
    private Long procedureId;

    @jakarta.persistence.Id
    @Column(name = "guide_point_id", nullable = false)
    private Long guidePointId;

    public TestingProcedureGuidePoint() {}

    public TestingProcedureGuidePoint(Long procedureId, Long guidePointId) {
        this.procedureId = procedureId;
        this.guidePointId = guidePointId;
    }

    public Long getProcedureId() { return procedureId; }
    public void setProcedureId(Long v) { this.procedureId = v; }

    public Long getGuidePointId() { return guidePointId; }
    public void setGuidePointId(Long v) { this.guidePointId = v; }

    public static class Id implements java.io.Serializable {
        private Long procedureId;
        private Long guidePointId;

        public Id() {}
        public Id(Long procedureId, Long guidePointId) {
            this.procedureId = procedureId;
            this.guidePointId = guidePointId;
        }

        public Long getProcedureId() { return procedureId; }
        public Long getGuidePointId() { return guidePointId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id that)) return false;
            return java.util.Objects.equals(procedureId, that.procedureId)
                && java.util.Objects.equals(guidePointId, that.guidePointId);
        }

        @Override
        public int hashCode() { return java.util.Objects.hash(procedureId, guidePointId); }
    }
}
