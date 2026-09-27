package com.martecyber.ares.kb.attack;

import jakarta.persistence.*;

/** Join row for a STIX {@code relationship} object with {@code relationship_type: "mitigates"}
 *  (source_ref = the mitigation's stix_id, target_ref = the technique's stix_id) — new in Phase 5
 *  of the AQL-wide initiative. {@link AttackStixParser} previously discarded these entirely (no
 *  {@code case "relationship"} branch existed); it now parses them and {@link AttackService}
 *  resolves the stix_id pair to this table's real FK ids after saving techniques/mitigations. */
@Entity
@Table(name = "attack_technique_mitigation", schema = "ares")
@IdClass(AttackTechniqueMitigationId.class)
public class AttackTechniqueMitigation {

    @Id
    @Column(name = "technique_id", nullable = false)
    private Long techniqueId;

    @Id
    @Column(name = "mitigation_id", nullable = false)
    private Long mitigationId;

    public AttackTechniqueMitigation() {}
    public AttackTechniqueMitigation(Long techniqueId, Long mitigationId) {
        this.techniqueId = techniqueId;
        this.mitigationId = mitigationId;
    }

    public Long getTechniqueId() { return techniqueId; }
    public Long getMitigationId() { return mitigationId; }
}
