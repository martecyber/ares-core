package com.martecyber.ares.dashboards;

import com.martecyber.ares.dashboards.dto.DashboardPresentationDtos.CreatePresentationRequest;
import com.martecyber.ares.dashboards.dto.DashboardPresentationDtos.PresentationItemDto;
import com.martecyber.ares.dashboards.dto.DashboardPresentationDtos.SavePresentationItemsRequest;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises DashboardPresentationService (and the DashboardScopeLabels helper it shares with
 * DashboardService's dashboard-browse picker) against a real, fully-migrated Postgres — same
 * out-of-band-container convention as the sibling AQL IT tests (see their own class javadocs).
 * Constructed directly (like AqlVariableExpanderIT constructs its expander) rather than via
 * @SpringBootTest: DashboardPresentationService has zero non-JPA dependencies, so a full
 * application context isn't needed. Self-contained fixtures (platform + a fresh org + a fresh
 * project, all created here) rather than depending on dev-seeded ids — a presentation must mix
 * dashboards from every level, so the fixtures deliberately do too.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DashboardPresentationServiceIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        int port = Integer.getInteger("AQL_IT_PG_PORT", 15432);
        String jdbcUrl = "jdbc:postgresql://localhost:" + port + "/ares";
        registry.add("spring.datasource.url", () -> jdbcUrl + "?currentSchema=ares");
        registry.add("spring.datasource.username", () -> "ares");
        registry.add("spring.datasource.password", () -> "ares");
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", () -> "ares");
        registry.add("spring.flyway.password", () -> "ares");
    }

    @Autowired
    private DashboardPresentationRepository presentationRepo;
    @Autowired
    private DashboardPresentationItemRepository itemRepo;
    @Autowired
    private DashboardRepository dashboardRepo;
    @Autowired
    private OrganizationRepository orgRepo;
    @Autowired
    private ProjectRepository projectRepo;

    @PersistenceContext
    private EntityManager em;

    private DashboardPresentationService service;
    private Long platformDashboardId;
    private Long orgDashboardId;
    private Long projectDashboardId;

    @BeforeEach
    void seed() {
        service = new DashboardPresentationService(presentationRepo, itemRepo, dashboardRepo, orgRepo, projectRepo);

        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('Presentation Test Org', 'presentation-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        Number projectId = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'Presentation Test Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();

        // presentable=true on all three: this fixture exists to test presentation behavior, and
        // saveItems()/get() both now enforce the opt-in flag — see savingItemsRejectsANonPresentableDashboard
        // below for the negative case.
        platformDashboardId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.dashboard(level, scope_id, name, is_default, presentable) VALUES ('PLATFORM', NULL, 'Platform Dash', true, true) RETURNING id")
            .getSingleResult()).longValue();
        orgDashboardId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.dashboard(level, scope_id, name, is_default, presentable) VALUES ('ORGANIZATION', :scopeId, 'Org Dash', true, true) RETURNING id")
            .setParameter("scopeId", orgId).getSingleResult()).longValue();
        projectDashboardId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.dashboard(level, scope_id, name, is_default, presentable) VALUES ('PROJECT', :scopeId, 'Project Dash', true, true) RETURNING id")
            .setParameter("scopeId", projectId).getSingleResult()).longValue();
    }

    @Test
    void createsAndListsAPresentation() {
        var created = service.create(new CreatePresentationRequest("SOC Rotation", 45));
        assertEquals("SOC Rotation", created.name());
        assertEquals(45, created.rotationSeconds());
        assertEquals(0, created.dashboardCount());

        var listed = service.list();
        assertTrue(listed.stream().anyMatch(p -> p.id().equals(created.id())));
    }

    @Test
    void rotationSecondsIsClampedToTheAllowedRange() {
        assertEquals(5, service.create(new CreatePresentationRequest("Too fast", 1)).rotationSeconds());
        assertEquals(3600, service.create(new CreatePresentationRequest("Too slow", 999999)).rotationSeconds());
        assertEquals(30, service.create(new CreatePresentationRequest("Default", null)).rotationSeconds());
    }

    /** The core requirement this whole feature exists for: a presentation must freely mix
     *  dashboards from every level in one ordered rotation, each with a resolved, readable scope
     *  label. */
    @Test
    void presentationMixesDashboardsFromEveryLevelInOrder() {
        var created = service.create(new CreatePresentationRequest("Mixed levels", 30));
        service.saveItems(created.id(), new SavePresentationItemsRequest(
            List.of(orgDashboardId, platformDashboardId, projectDashboardId)));

        var dto = service.get(created.id());
        assertEquals(3, dto.items().size());
        List<PresentationItemDto> items = dto.items();

        assertEquals(orgDashboardId, items.get(0).dashboardId());
        assertEquals(DashboardLevel.ORGANIZATION, items.get(0).level());
        assertEquals("Presentation Test Org", items.get(0).scopeLabel());

        assertEquals(platformDashboardId, items.get(1).dashboardId());
        assertEquals(DashboardLevel.PLATFORM, items.get(1).level());
        assertEquals("Platform", items.get(1).scopeLabel());

        assertEquals(projectDashboardId, items.get(2).dashboardId());
        assertEquals(DashboardLevel.PROJECT, items.get(2).level());
        assertEquals("Presentation Test Org / Presentation Test Project", items.get(2).scopeLabel());
    }

    /** saveItems is a bulk replace, not an incremental add — re-saving with a different order (and
     *  one fewer item) must fully reflect the new list, not merge with the old one. */
    @Test
    void savingItemsAgainReplacesTheWholeOrderedList() {
        var created = service.create(new CreatePresentationRequest("Reorder me", 30));
        service.saveItems(created.id(), new SavePresentationItemsRequest(List.of(platformDashboardId, orgDashboardId, projectDashboardId)));
        assertEquals(List.of(platformDashboardId, orgDashboardId, projectDashboardId),
            service.get(created.id()).items().stream().map(PresentationItemDto::dashboardId).toList());

        service.saveItems(created.id(), new SavePresentationItemsRequest(List.of(projectDashboardId, platformDashboardId)));
        assertEquals(List.of(projectDashboardId, platformDashboardId),
            service.get(created.id()).items().stream().map(PresentationItemDto::dashboardId).toList());
    }

    @Test
    void savingItemsRejectsANonexistentDashboardId() {
        var created = service.create(new CreatePresentationRequest("Bad item", 30));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> service.saveItems(created.id(), new SavePresentationItemsRequest(List.of(platformDashboardId, 999_999_999L))));
    }

    /** The opt-in gate a dashboard's own owner sets from its edit view — saveItems() must refuse
     *  a dashboard nobody has enabled for presentations, even one that otherwise exists and is
     *  perfectly valid, so a presentation can never surface content its owner hasn't chosen to
     *  expose that way. */
    @Test
    void savingItemsRejectsANonPresentableDashboard() {
        Long notPresentableId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.dashboard(level, scope_id, name, is_default, presentable) VALUES ('PLATFORM', NULL, 'Unopted Dash', false, false) RETURNING id")
            .getSingleResult()).longValue();
        var created = service.create(new CreatePresentationRequest("Bad item", 30));

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> service.saveItems(created.id(), new SavePresentationItemsRequest(List.of(platformDashboardId, notPresentableId))));
    }

    /** Un-flagging a dashboard after it was already added must take effect immediately at read
     *  time, not just be checked once when it was first added — otherwise the opt-in flag would
     *  only ever gate the initial add, not actually control what a live presentation can show. */
    @Test
    void unFlaggingAnAlreadyAddedDashboardDropsItOutOfThePresentationImmediately() {
        var created = service.create(new CreatePresentationRequest("Live un-flag", 30));
        service.saveItems(created.id(), new SavePresentationItemsRequest(List.of(platformDashboardId, orgDashboardId)));
        assertEquals(2, service.get(created.id()).items().size());

        em.createNativeQuery("UPDATE ares.dashboard SET presentable = false WHERE id = :id")
            .setParameter("id", orgDashboardId).executeUpdate();
        // The org dashboard's own entity is already managed in this persistence context (loaded by
        // saveItems()'s own findAllById above) — a native UPDATE bypasses Hibernate's first-level
        // cache entirely, so without clearing it the next findAllById below would hand back that
        // same stale in-memory instance (presentable still true) instead of re-querying. Unlike the
        // sibling "deleted dashboard" test below, an UPDATE can't be caught by a row simply being
        // absent from the result set — the row's still there, just with a cache-shadowed value.
        em.clear();

        var dto = service.get(created.id());
        assertEquals(1, dto.items().size());
        assertEquals(platformDashboardId, dto.items().get(0).dashboardId());
    }

    @Test
    void deletingAPresentationCascadesItsItems() {
        var created = service.create(new CreatePresentationRequest("To delete", 30));
        service.saveItems(created.id(), new SavePresentationItemsRequest(List.of(platformDashboardId)));
        assertEquals(1, itemRepo.countByPresentationId(created.id()));

        service.delete(created.id());
        assertEquals(0, itemRepo.countByPresentationId(created.id()));
    }

    /** A dashboard being deleted out from under a presentation must silently drop it (ON DELETE
     *  CASCADE on dashboard_presentation_item.dashboard_id), not break the rest of the rotation. */
    @Test
    void deletingADashboardDropsItOutOfThePresentationWithoutBreakingIt() {
        var created = service.create(new CreatePresentationRequest("Survives a deleted dashboard", 30));
        service.saveItems(created.id(), new SavePresentationItemsRequest(List.of(platformDashboardId, orgDashboardId)));

        em.createNativeQuery("DELETE FROM ares.dashboard WHERE id = :id").setParameter("id", orgDashboardId).executeUpdate();

        var dto = service.get(created.id());
        assertEquals(1, dto.items().size());
        assertEquals(platformDashboardId, dto.items().get(0).dashboardId());
    }
}
