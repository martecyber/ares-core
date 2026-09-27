package com.martecyber.ares.kb.attack;

import jakarta.persistence.*;

/** Join row normalizing {@link AttackTechnique#getTactics()}'s short-name array into a real FK
 *  relationship — new in Phase 5 of the AQL-wide initiative, additive alongside (not replacing)
 *  the text[] column, which stays for API/frontend compatibility. Populated during sync by
 *  matching each technique's short names against {@link AttackTactic#getShortName()} within the
 *  same matrix. Plain scalar id columns, no {@code @ManyToOne} objects needed — mirrors {@code
 *  AffectionAsset}'s own established shape for a simple two-column bridge table in this codebase. */
@Entity
@Table(name = "attack_technique_tactic", schema = "ares")
@IdClass(AttackTechniqueTacticId.class)
public class AttackTechniqueTactic {

    @Id
    @Column(name = "technique_id", nullable = false)
    private Long techniqueId;

    @Id
    @Column(name = "tactic_id", nullable = false)
    private Long tacticId;

    public AttackTechniqueTactic() {}
    public AttackTechniqueTactic(Long techniqueId, Long tacticId) {
        this.techniqueId = techniqueId;
        this.tacticId = tacticId;
    }

    public Long getTechniqueId() { return techniqueId; }
    public Long getTacticId() { return tacticId; }
}
