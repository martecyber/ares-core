package com.martecyber.ares.profile;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_out_of_office", schema = "ares")
public class UserOutOfOffice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UserOutOfOffice() {}

    public UserOutOfOffice(Long userId, LocalDate startDate, LocalDate endDate, String reason) {
        this.userId = userId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.reason = reason;
    }

    public Long getId()                     { return id; }
    public Long getUserId()                 { return userId; }
    public LocalDate getStartDate()         { return startDate; }
    public LocalDate getEndDate()           { return endDate; }
    public String getReason()               { return reason; }
    public OffsetDateTime getCreatedAt()    { return createdAt; }

    public void setUserId(Long userId)            { this.userId = userId; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public void setEndDate(LocalDate endDate)     { this.endDate = endDate; }
    public void setReason(String reason)          { this.reason = reason; }
}
