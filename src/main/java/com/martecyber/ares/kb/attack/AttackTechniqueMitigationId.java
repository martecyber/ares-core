package com.martecyber.ares.kb.attack;

import java.io.Serializable;
import java.util.Objects;

public class AttackTechniqueMitigationId implements Serializable {
    private Long techniqueId;
    private Long mitigationId;

    public AttackTechniqueMitigationId() {}
    public AttackTechniqueMitigationId(Long techniqueId, Long mitigationId) {
        this.techniqueId = techniqueId;
        this.mitigationId = mitigationId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AttackTechniqueMitigationId that)) return false;
        return Objects.equals(techniqueId, that.techniqueId) && Objects.equals(mitigationId, that.mitigationId);
    }
    @Override public int hashCode() { return Objects.hash(techniqueId, mitigationId); }
}
