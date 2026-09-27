package com.martecyber.ares.profile;

import java.time.LocalDate;

public record UserOutOfOfficeDto(
    Long id,
    Long userId,
    String startDate,
    String endDate,
    String reason
) {
    static UserOutOfOfficeDto from(UserOutOfOffice o) {
        return new UserOutOfOfficeDto(
            o.getId(),
            o.getUserId(),
            o.getStartDate().toString(),
            o.getEndDate().toString(),
            o.getReason()
        );
    }
}
