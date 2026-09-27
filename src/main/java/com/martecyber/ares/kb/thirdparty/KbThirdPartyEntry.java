package com.martecyber.ares.kb.thirdparty;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "kb_third_party_entry", schema = "ares")
public class KbThirdPartyEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(nullable = false, length = 500)
    private String value;

    @Column(nullable = false, length = 50)
    private String category = "other";

    @Column(columnDefinition = "text")
    private String notes;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId()                     { return id; }
    public String getKind()                 { return kind; }
    public void setKind(String kind)        { this.kind = kind; }
    public String getValue()                { return value; }
    public void setValue(String value)      { this.value = value; }
    public String getCategory()             { return category; }
    public void setCategory(String category){ this.category = category; }
    public String getNotes()                { return notes; }
    public void setNotes(String notes)      { this.notes = notes; }
    public boolean isEnabled()              { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getCreatedAt()    { return createdAt; }
    public OffsetDateTime getUpdatedAt()    { return updatedAt; }
}
