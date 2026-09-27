package com.martecyber.ares.kb.testing;

import jakarta.persistence.*;

/**
 * A testing procedure linked to an external-database entry. Polymorphic:
 * {@code refType} is one of cve|cwe|capec|attack|owasp and {@code refKey} is the
 * external identifier (e.g. CVE-2021-1234, 79, T1059, A01:2021).
 */
@Entity
@Table(name = "testing_procedure_external_ref", schema = "ares")
public class TestingProcedureExternalRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "procedure_id", nullable = false)
    private Long procedureId;

    @Column(name = "ref_type", nullable = false, length = 20)
    private String refType;

    @Column(name = "ref_key", nullable = false, length = 120)
    private String refKey;

    @Column(name = "ref_label", length = 300)
    private String refLabel;

    public TestingProcedureExternalRef() {}

    public TestingProcedureExternalRef(Long procedureId, String refType, String refKey, String refLabel) {
        this.procedureId = procedureId;
        this.refType = refType;
        this.refKey = refKey;
        this.refLabel = refLabel;
    }

    public Long getId() { return id; }

    public Long getProcedureId() { return procedureId; }
    public void setProcedureId(Long v) { this.procedureId = v; }

    public String getRefType() { return refType; }
    public void setRefType(String v) { this.refType = v; }

    public String getRefKey() { return refKey; }
    public void setRefKey(String v) { this.refKey = v; }

    public String getRefLabel() { return refLabel; }
    public void setRefLabel(String v) { this.refLabel = v; }
}
