package com.martecyber.ares.aql.materialize;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class KbMaterializedRefId implements Serializable {

    @Column(name = "catalog_id")
    private Long catalogId;

    @Column(name = "code")
    private String code;

    public KbMaterializedRefId() { }

    public KbMaterializedRefId(Long catalogId, String code) {
        this.catalogId = catalogId;
        this.code = code;
    }

    public Long getCatalogId() { return catalogId; }
    public void setCatalogId(Long catalogId) { this.catalogId = catalogId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KbMaterializedRefId that)) return false;
        return Objects.equals(catalogId, that.catalogId) && Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() { return Objects.hash(catalogId, code); }
}
