package com.martecyber.ares.security;

import com.martecyber.ares.agents.tasks.AgentTaskRepository;
import com.martecyber.ares.agents.tasks.AgentTaskService;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.GlobalTaskDto;
import com.martecyber.ares.integrations.Integration;
import com.martecyber.ares.integrations.IntegrationRepository;
import com.martecyber.ares.jobs.dto.JobDto;
import com.martecyber.ares.jobs.JobRepository;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.projects.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * The org-visibility filters behind the staff-wide listings (jobs, integrations, the global agent
 * task view) run as real queries against a fully-migrated Postgres — same out-of-band-container
 * convention as the sibling AQL / dashboard ITs. Fixtures: two fresh organizations with one project
 * each, plus platform-wide rows (no organization) that every staff member must keep seeing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OrgVisibilityFiltersIT {

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

    @Autowired private JobRepository jobRepo;
    @Autowired private IntegrationRepository integrationRepo;
    @Autowired private AgentTaskRepository taskRepo;
    @Autowired private ProjectRepository projectRepo;
    @PersistenceContext private EntityManager em;

    private long orgA, orgB, projectA, projectB;

    private long insert(String sql, Object... params) {
        var q = em.createNativeQuery(sql + " RETURNING id");
        for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
        return ((Number) q.getSingleResult()).longValue();
    }

    @BeforeEach
    void seed() {
        String suffix = Long.toString(System.nanoTime());
        orgA = insert("INSERT INTO ares.organization(name, slug) VALUES ('Org A', ?1)", "vis-a-" + suffix);
        orgB = insert("INSERT INTO ares.organization(name, slug) VALUES ('Org B', ?1)", "vis-b-" + suffix);
        projectA = insert("INSERT INTO ares.project(organization_id, name) VALUES (?1, 'Project A')", orgA);
        projectB = insert("INSERT INTO ares.project(organization_id, name) VALUES (?1, 'Project B')", orgB);
    }

    @Test
    void jobsShowPlatformJobsPlusTheCallersOrganizations() {
        String type = "vis-test-" + System.nanoTime();
        long platform = insert("INSERT INTO ares.job(type) VALUES (?1)", type);
        long jobA = insert("INSERT INTO ares.job(type, organization_id) VALUES (?1, ?2)", type, orgA);
        insert("INSERT INTO ares.job(type, organization_id) VALUES (?1, ?2)", type, orgB);
        JobService jobs = new JobService(jobRepo);

        List<Long> visible = jobs.list(null, null, List.of(type), null, "id", "ASC", 0, 50, Set.of(orgA))
            .getContent().stream().map(JobDto::id).toList();
        assertEquals(List.of(platform, jobA), visible);

        assertEquals(List.of(platform), jobs.list(null, null, List.of(type), null, "id", "ASC", 0, 50, Set.of())
            .getContent().stream().map(JobDto::id).toList(), "no orgs: only platform jobs");
        assertEquals(3, jobs.list(null, null, List.of(type), null, "id", "ASC", 0, 50, null)
            .getTotalElements(), "null = unrestricted (platform admin)");
    }

    @Test
    void integrationsShowPlatformOnesPlusTheCallersOrganizations() {
        String type = "vis-test-" + System.nanoTime();
        long tool = insert("INSERT INTO ares.detector_tool(name) VALUES ('vis-tool')");
        long platform = insert("INSERT INTO ares.integration(tool_id, name, type) VALUES (?1, 'p', ?2)", tool, type);
        long intA = insert("INSERT INTO ares.integration(tool_id, name, type, scope, organization_id) VALUES (?1, 'a', ?2, 'ORGANIZATION', ?3)", tool, type, orgA);
        insert("INSERT INTO ares.integration(tool_id, name, type, scope, organization_id) VALUES (?1, 'b', ?2, 'ORGANIZATION', ?3)", tool, type, orgB);

        List<Long> visible = integrationRepo.filterVisible(null, type, null, Set.of(orgA), PageRequest.of(0, 50))
            .getContent().stream().map(Integration::getId).sorted().toList();
        assertEquals(List.of(platform, intA), visible);
    }

    @Test
    void globalAgentTasksOnlyShowProjectsOfTheCallersOrganizations() {
        long pool = insert("INSERT INTO ares.agent_pool(name) VALUES (?1)", "vis-pool-" + System.nanoTime());
        String tool = "vis" + (System.nanoTime() % 100000);
        long taskA = insert("INSERT INTO ares.agent_task(project_id, pool_id, tool, status) VALUES (?1, ?2, ?3, 'pending')", projectA, pool, tool);
        insert("INSERT INTO ares.agent_task(project_id, pool_id, tool, status) VALUES (?1, ?2, ?3, 'pending')", projectB, pool, tool);
        AgentTaskService tasks = build(AgentTaskService.class, taskRepo, projectRepo);

        List<Long> visible = tasks.listAll(null, List.of(tool), null, "createdAt", "ASC", 0, 50, Set.of(orgA))
            .getContent().stream().map(GlobalTaskDto::id).toList();
        assertEquals(List.of(taskA), visible);

        assertTrue(tasks.listAll(null, List.of(tool), null, "createdAt", "ASC", 0, 50, Set.of()).isEmpty());
        assertEquals(2, tasks.listAll(null, List.of(tool), null, "createdAt", "ASC", 0, 50, null).getTotalElements());
    }

    /** Real repositories where given, mocks for every other constructor dependency. */
    private static <T> T build(Class<T> type, Object... given) {
        Constructor<?> ctor = type.getConstructors()[0];
        Object[] deps = Arrays.stream(ctor.getParameterTypes())
            .map(t -> Arrays.stream(given).filter(t::isInstance).findFirst().orElseGet(() -> mock(t)))
            .toArray();
        try {
            return type.cast(ctor.newInstance(deps));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
