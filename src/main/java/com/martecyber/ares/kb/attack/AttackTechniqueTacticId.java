package com.martecyber.ares.kb.attack;

import java.io.Serializable;
import java.util.Objects;

public class AttackTechniqueTacticId implements Serializable {
    private Long techniqueId;
    private Long tacticId;

    public AttackTechniqueTacticId() {}
    public AttackTechniqueTacticId(Long techniqueId, Long tacticId) {
        this.techniqueId = techniqueId;
        this.tacticId = tacticId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AttackTechniqueTacticId that)) return false;
        return Objects.equals(techniqueId, that.techniqueId) && Objects.equals(tacticId, that.tacticId);
    }
    @Override public int hashCode() { return Objects.hash(techniqueId, tacticId); }
}
