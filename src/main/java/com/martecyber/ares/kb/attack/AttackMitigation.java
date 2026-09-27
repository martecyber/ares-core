package com.martecyber.ares.kb.attack;

import jakarta.persistence.*;

import java.time.Instant;

/** Postgres-backed ATT&CK mitigation record (AQL-wide initiative, Phase 5 — migrated off
 *  MongoDB's {@code kb_attack_mitigations}). Natural key is {@code (attackId, matrix)}. */
@Entity
@Table(name = "attack_mitigation", schema = "ares")
public class AttackMitigation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stix_id")
    private String stixId;

    @Column(name = "attack_id", nullable = false)
    private String attackId;

    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private String matrix;

    @Column(nullable = false)
    private boolean deprecated;

    @Column(name = "synced_at")
    private Instant syncedAt;

    public Long getId() { return id; }
    public String getStixId() { return stixId; }
    public void setStixId(String stixId) { this.stixId = stixId; }
    public String getAttackId() { return attackId; }
    public void setAttackId(String attackId) { this.attackId = attackId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getMatrix() { return matrix; }
    public void setMatrix(String matrix) { this.matrix = matrix; }
    public boolean isDeprecated() { return deprecated; }
    public void setDeprecated(boolean deprecated) { this.deprecated = deprecated; }
    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
}
