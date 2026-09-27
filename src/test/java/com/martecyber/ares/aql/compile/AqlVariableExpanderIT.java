package com.martecyber.ares.aql.compile;

import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.MonitorIterationHelper;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises {@link AqlVariableExpander} against a real, fully-migrated Postgres instance (same
 * out-of-band-container convention as the sibling PostgresSpecificationCompiler*IT tests — see
 * their own class javadoc for why). Constructs the expander directly (like those tests construct
 * their registries directly) rather than autowiring it, since {@code @DataJpaTest} only
 * autoconfigures JPA infrastructure — plain {@code @Component} beans aren't in that slice.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AqlVariableExpanderIT {

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
    private ProjectRepository projectRepo;

    @Autowired
    private OrganizationRepository organizationRepo;

    @PersistenceContext
    private EntityManager em;

    private AqlVariableExpander expander;
    private long orgId;
    private long monitorProjectId;
    private long plainProjectId;

    @BeforeEach
    void seed() {
        expander = new AqlVariableExpander(projectRepo, organizationRepo);

        String slaJson = "{\"sla\":{\"critical\":3,\"high\":7,\"medium\":14,\"low\":30,\"info\":0}}";
        Number org = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug, settings) VALUES "
                    + "('AQL Var Test Org', 'aql-var-test-org-' || floor(random()*1e9)::text, CAST(:settings AS jsonb)) RETURNING id")
            .setParameter("settings", slaJson)
            .getSingleResult();
        orgId = org.longValue();

        Number monitorProj = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name, iteration_cadence) VALUES (:orgId, 'Monitor Project', 'weekly') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        monitorProjectId = monitorProj.longValue();

        Number plainProj = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'Plain Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        plainProjectId = plainProj.longValue();
    }

    @Test
    void queryWithNoVariablesPassesThroughUnchanged() {
        assertEquals("priority == P0", expander.expand("priority == P0", null, null));
    }

    @Test
    void nowExpandsToAResolvableEpochMillisToken() {
        String expanded = expander.expand("createdAt < {{now}}", null, null);
        assertTrue(expanded.matches("createdAt < \\d{13}"), expanded);
        long millis = Long.parseLong(expanded.substring("createdAt < ".length()));
        assertTrue(Math.abs(System.currentTimeMillis() - millis) < 5000);
    }

    @Test
    void nowWithOffsetAppliesTheSameArithmeticAsPlainNow() {
        String expanded = expander.expand("createdAt < {{now}}-7d", null, null);
        long millis = Long.parseLong(expanded.substring("createdAt < ".length()));
        long expected = OffsetDateTime.now().minusDays(7).toInstant().toEpochMilli();
        assertTrue(Math.abs(expected - millis) < 5000);
    }

    @Test
    void iterationVariablesResolveToTheCurrentWeeklyBounds() {
        String expanded = expander.expand(
            "createdAt >= {{current_iteration_start}} AND createdAt < {{current_iteration_end}}", monitorProjectId, null);
        LocalDate[] bounds = MonitorIterationHelper.iterationBounds("weekly", LocalDate.now());
        long expectedStart = bounds[0].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long expectedEnd = bounds[1].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals("createdAt >= " + expectedStart + " AND createdAt < " + expectedEnd, expanded);
    }

    /** Proves the 'i' unit shifts by a whole cadence period (recomputing that iteration's own
     *  boundary), not by a fixed number of calendar days. */
    @Test
    void iterationVariableWithIterationsOffsetShiftsByAWholeIteration() {
        String expanded = expander.expand("createdAt >= {{current_iteration_start}}-1i", monitorProjectId, null);
        LocalDate shifted = MonitorIterationHelper.shiftReferenceDate("weekly", LocalDate.now(), -1);
        LocalDate[] bounds = MonitorIterationHelper.iterationBounds("weekly", shifted);
        long expectedStart = bounds[0].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals("createdAt >= " + expectedStart, expanded);
    }

    @Test
    void iterationVariableRejectedWithoutProjectScope() {
        assertThrows(AqlCompileException.class,
            () -> expander.expand("createdAt >= {{current_iteration_start}}", null, null));
    }

    @Test
    void iterationVariableRejectedForProjectWithNoCadence() {
        assertThrows(AqlCompileException.class,
            () -> expander.expand("createdAt >= {{current_iteration_start}}", plainProjectId, null));
    }

    @Test
    void iterationsUnitRejectedForNow() {
        assertThrows(AqlCompileException.class,
            () -> expander.expand("createdAt < {{now}}-1i", monitorProjectId, null));
    }

    /** p0 == critical severity per PriorityThresholds.severityForPriority — seeded org settings
     *  set critical=3 days. */
    @Test
    void slaPeriodVariableSubstitutesTheOrgsConfiguredDays() {
        String expanded = expander.expand("reportedAt < {{now}}-{{p0_sla_period}}", null, orgId);
        long millis = Long.parseLong(expanded.substring("reportedAt < ".length()));
        long expected = OffsetDateTime.now().minusDays(3).toInstant().toEpochMilli();
        assertTrue(Math.abs(expected - millis) < 5000);
    }

    /** No organizationId given directly — must resolve it by looking the project up. p1 == high,
     *  seeded as 7 days. */
    @Test
    void slaPeriodVariableResolvesOrgFromProjectWhenOrgNotGivenDirectly() {
        String expanded = expander.expand("reportedAt < {{now}}-{{p1_sla_period}}", monitorProjectId, null);
        long millis = Long.parseLong(expanded.substring("reportedAt < ".length()));
        long expected = OffsetDateTime.now().minusDays(7).toInstant().toEpochMilli();
        assertTrue(Math.abs(expected - millis) < 5000);
    }

    @Test
    void slaPeriodVariableRejectedWithNoResolvableOrgScope() {
        assertThrows(AqlCompileException.class,
            () -> expander.expand("reportedAt < {{now}}-{{p0_sla_period}}", null, null));
    }

    @Test
    void unknownVariableIsRejected() {
        assertThrows(AqlCompileException.class, () -> expander.expand("createdAt < {{bogus}}", null, null));
    }
}
