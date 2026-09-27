package com.martecyber.ares.kb.attack;

import jakarta.persistence.*;

import java.time.Instant;

/** Postgres-backed ATT&CK tactic record (AQL-wide initiative, Phase 5 — migrated off MongoDB's
 *  {@code kb_attack_tactics}). Natural key is {@code (attackId, matrix)} — the same tactic code
 *  (e.g. "TA0001") is redefined per matrix (enterprise/mobile/ics). */
@Entity
@Table(name = "attack_tactic", schema = "ares")
public class AttackTactic {

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

    @Column(name = "short_name")
    private String shortName;

    @Column(nullable = false)
    private String matrix;

    @Column(name = "sort_order")
    private Integer order;

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
    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }
    public String getMatrix() { return matrix; }
    public void setMatrix(String matrix) { this.matrix = matrix; }
    public Integer getOrder() { return order; }
    public void setOrder(Integer order) { this.order = order; }
    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
}
