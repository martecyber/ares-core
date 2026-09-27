package com.martecyber.ares.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "platform_setting", schema = "ares")
public class PlatformSetting {

    @Id
    @Column(name = "key", length = 100)
    private String key;

    @Column(name = "value", nullable = false)
    private String value;

    public String getKey()              { return key; }
    public void   setKey(String key)    { this.key = key; }
    public String getValue()            { return value; }
    public void   setValue(String v)    { this.value = v; }
}
