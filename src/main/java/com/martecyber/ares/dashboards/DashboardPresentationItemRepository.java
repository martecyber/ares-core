package com.martecyber.ares.dashboards;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DashboardPresentationItemRepository extends JpaRepository<DashboardPresentationItem, Long> {

    List<DashboardPresentationItem> findByPresentationIdOrderBySortOrderAsc(Long presentationId);

    // A plain derived `deleteBy...` method defers its removes to the next flush, same as any other
    // JPA remove() — saveItems() immediately re-inserts fresh rows for the SAME (presentation_id,
    // dashboard_id) pairs right after calling this, and without an immediate flush here those
    // inserts can execute before the deferred deletes during the transaction's single end-of-method
    // flush, tripping the (presentation_id, dashboard_id) unique constraint. @Modifying with
    // flushAutomatically forces the delete to hit the DB right away instead.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DashboardPresentationItem i where i.presentationId = :presentationId")
    void deleteByPresentationId(@Param("presentationId") Long presentationId);

    long countByPresentationId(Long presentationId);
}
