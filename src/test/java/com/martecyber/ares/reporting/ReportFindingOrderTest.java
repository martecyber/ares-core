package com.martecyber.ares.reporting;

import com.martecyber.ares.findings.Finding;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFindingOrderTest {

    private static Finding finding(long id, OffsetDateTime createdAt, OffsetDateTime reportedAt) {
        Finding f = new Finding();
        ReflectionTestUtils.setField(f, "id", id);
        f.setCreatedAt(createdAt);
        f.setReportedAt(reportedAt);
        return f;
    }

    @Test
    void ordersByPublicationNotByCreation() {
        OffsetDateTime t = OffsetDateTime.parse("2026-10-01T10:00:00Z");
        // Created first, published last: it must come last in the report.
        Finding createdFirst = finding(1, t, t.plusDays(3));
        Finding publishedFirst = finding(2, t.plusDays(1), t.plusDays(1));
        Finding publishedSecond = finding(3, t.plusDays(2), t.plusDays(2));

        assertThat(ReportGenerationService.inPublicationOrder(List.of(createdFirst, publishedSecond, publishedFirst)))
            .extracting(Finding::getId).containsExactly(2L, 3L, 1L);
    }

    @Test
    void breaksTiesByIdAndPutsUnpublishedLast() {
        OffsetDateTime t = OffsetDateTime.parse("2026-10-01T10:00:00Z");
        assertThat(ReportGenerationService.inPublicationOrder(
            List.of(finding(9, t, null), finding(5, t, t), finding(4, t, t))))
            .extracting(Finding::getId).containsExactly(4L, 5L, 9L);
    }
}
