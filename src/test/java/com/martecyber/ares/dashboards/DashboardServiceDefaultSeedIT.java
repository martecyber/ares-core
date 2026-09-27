package com.martecyber.ares.dashboards;

import com.martecyber.ares.dashboards.dto.DashboardDtos.CreateDashboardRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for the dashboards remodel against the real dev Postgres (same
 * instance/port convention as the other AQL/dashboards IT tests — override via
 * -DAQL_IT_PG_PORT). Uses org id 1 / project id 2, which already exist in the dev seed data.
 * Fakes a platform-admin Authentication directly (via SecurityContextHolder for direct service
 * calls, or MockMvc's `authentication()` request post-processor for HTTP-level tests) so
 * DashboardService's own access checks pass without needing real login credentials. Grew from
 * one initial repro ("default dashboards are empty") to cover two further live incidents found
 * the same way: a stale-enum-type 500 (V170) and a default-switching flush-order 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DashboardServiceDefaultSeedIT {

    @Autowired
    private MockMvc mockMvc;

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
    private DashboardService service;

    @PersistenceContext
    private EntityManager em;

    private void authenticateAsPlatformAdmin() {
        var auth = new TestingAuthenticationToken("1", null, List.of(new SimpleGrantedAuthority("ROLE_MSSP_ADMIN")));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void organizationDefaultDashboardHasWidgets() {
        authenticateAsPlatformAdmin();
        var summaries = service.list(DashboardLevel.ORGANIZATION, 1L);
        var dashboard = service.get(summaries.get(0).id());
        System.out.println("ORG widget count = " + dashboard.widgets().size());
        assertFalse(dashboard.widgets().isEmpty(), "organization default dashboard has no widgets");
    }

    @Test
    void projectDefaultDashboardHasWidgets() {
        authenticateAsPlatformAdmin();
        var summaries = service.list(DashboardLevel.PROJECT, 2L);
        var dashboard = service.get(summaries.get(0).id());
        System.out.println("PROJECT widget count = " + dashboard.widgets().size());
        assertFalse(dashboard.widgets().isEmpty(), "project default dashboard has no widgets");
    }

    /** Covers the real controller + Jackson serialization + Spring Security filter chain path —
     *  not just the service layer {@link #organizationDefaultDashboardHasWidgets} already checks —
     *  since a live "GET /api/v1/dashboards/{id}" 500 was reported from the browser that the
     *  service-level test alone couldn't rule out or reproduce. */
    @Test
    void getOrganizationDashboardOverHttp() throws Exception {
        authenticateAsPlatformAdmin();
        Long dashboardId = service.list(DashboardLevel.ORGANIZATION, 1L).get(0).id();

        var auth = new TestingAuthenticationToken("1", null, List.of(new SimpleGrantedAuthority("ROLE_MSSP_ADMIN")));
        auth.setAuthenticated(true);
        mockMvc.perform(get("/api/v1/dashboards/" + dashboardId).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets").isNotEmpty());
    }

    /** Reproduces the live "PUT /api/v1/dashboards/{id} -> 500 when switching an org's default
     *  dashboard from one to another" report. ux_dashboard_default_per_scope is a non-deferred
     *  partial unique index; DashboardService.update() used to clear the previous default with a
     *  plain save() (deferred to flush) before setting the new one, and Hibernate's flush order
     *  for two already-managed entities isn't guaranteed to match code order — momentarily leaving
     *  two rows with is_default=true and failing the UPDATE with a unique violation. Exercises the
     *  switch in both directions to confirm the saveAndFlush fix holds regardless of which
     *  dashboard was loaded first in a given request. */
    @Test
    void switchingOrganizationDefaultDashboardSucceeds() throws Exception {
        authenticateAsPlatformAdmin();
        Long firstId = service.list(DashboardLevel.ORGANIZATION, 1L).get(0).id();
        Long secondId = service.create(new CreateDashboardRequest(DashboardLevel.ORGANIZATION, 1L, "Second")).id();

        var auth = new TestingAuthenticationToken("1", null, List.of(new SimpleGrantedAuthority("ROLE_MSSP_ADMIN")));
        auth.setAuthenticated(true);
        try {
            mockMvc.perform(put("/api/v1/dashboards/" + secondId).with(authentication(auth))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"isDefault\":true}"))
                .andExpect(status().isOk());
            mockMvc.perform(put("/api/v1/dashboards/" + firstId).with(authentication(auth))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"isDefault\":true}"))
                .andExpect(status().isOk());
        } finally {
            // MockMvc's per-request `.with(authentication(auth))` doesn't leave the outer test
            // thread's SecurityContextHolder populated afterward — re-authenticate before this
            // direct (non-HTTP) service call.
            authenticateAsPlatformAdmin();
            service.delete(secondId);
        }
    }

    /** Reproduces the live "IllegalArgumentException: No enum constant ...FINDING_SLA_DUE" 500
     *  (the same incident V170 cleans up historically) by inserting a widget row with a type that
     *  no longer exists directly via SQL — bypassing DashboardWidget's own writes, which can only
     *  ever persist a currently-valid type — then confirms the dashboard now loads successfully
     *  with every *other* widget intact, and the broken one surfaced as a null-typed entry instead
     *  of aborting the whole response. {@code @Transactional} here (not on the rest of the class,
     *  which relies on real commits across separate MockMvc requests) so the inserted row is rolled
     *  back automatically instead of needing manual cleanup — MockMvc dispatches synchronously on
     *  this same thread/transaction, so it still sees the uncommitted insert. */
    @Test
    @Transactional
    void dashboardWithStaleWidgetTypeStillLoads() throws Exception {
        authenticateAsPlatformAdmin();
        Long dashboardId = service.list(DashboardLevel.ORGANIZATION, 1L).get(0).id();
        int goodWidgetCount = service.get(dashboardId).widgets().size();

        em.createNativeQuery("""
            insert into ares.dashboard_widget (dashboard_id, type, config, pos_x, pos_y, width, height)
            values (:dashboardId, 'FINDING_SLA_DUE', '{}', 0, 99, 2, 2)
            """)
            .setParameter("dashboardId", dashboardId)
            .executeUpdate();

        var auth = new TestingAuthenticationToken("1", null, List.of(new SimpleGrantedAuthority("ROLE_MSSP_ADMIN")));
        auth.setAuthenticated(true);
        mockMvc.perform(get("/api/v1/dashboards/" + dashboardId).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets", org.hamcrest.Matchers.hasSize(goodWidgetCount + 1)))
            .andExpect(jsonPath("$.widgets[?(@.typeName == 'FINDING_SLA_DUE')].type").value(org.hamcrest.Matchers.contains((Object) null)));

        mockMvc.perform(get("/api/v1/dashboards/" + dashboardId + "/data").with(authentication(auth)))
            .andExpect(status().isOk());
    }
}
