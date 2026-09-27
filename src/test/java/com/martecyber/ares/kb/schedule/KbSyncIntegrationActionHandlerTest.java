package com.martecyber.ares.kb.schedule;

import com.martecyber.ares.jobs.Job;
import com.martecyber.ares.jobs.JobRepository;
import com.martecyber.ares.kb.attack.AttackService;
import com.martecyber.ares.kb.capec.CapecService;
import com.martecyber.ares.kb.cve.CveService;
import com.martecyber.ares.kb.cwe.CweService;
import com.martecyber.ares.kb.exploits.ExploitService;
import com.martecyber.ares.kb.kev.CisaKevService;
import com.martecyber.ares.kb.kev.VulnCheckKevService;
import com.martecyber.ares.kb.owasp.OwaspService;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KbSyncIntegrationActionHandlerTest {

    private CveService cveService;
    private CweService cweService;
    private CapecService capecService;
    private AttackService attackService;
    private OwaspService owaspService;
    private CisaKevService cisaKevService;
    private VulnCheckKevService vulnCheckKevService;
    private ExploitService exploitService;
    private JobRepository jobRepo;
    private KbSyncIntegrationActionHandler handler;

    @BeforeEach
    void setUp() {
        cveService = mock(CveService.class);
        cweService = mock(CweService.class);
        capecService = mock(CapecService.class);
        attackService = mock(AttackService.class);
        owaspService = mock(OwaspService.class);
        cisaKevService = mock(CisaKevService.class);
        vulnCheckKevService = mock(VulnCheckKevService.class);
        exploitService = mock(ExploitService.class);
        jobRepo = mock(JobRepository.class);
        handler = new KbSyncIntegrationActionHandler(cveService, cweService, capecService, attackService,
            owaspService, cisaKevService, vulnCheckKevService, exploitService, jobRepo);
    }

    @Test
    void supportsOnlyPlatformScope() {
        assertEquals(Set.of("platform"), handler.supportedScopes());
    }

    @Test
    void describesAllNineSyncTypes() {
        var codes = handler.describeActions().stream().map(a -> a.code()).toList();
        assertEquals(List.of("cve_update", "cwe", "capec", "attack", "owasp", "cisa_kev", "vulncheck_kev", "exploitdb", "vulncheck_xdb"), codes);
    }

    @Test
    void listInstancesReturnsOneSyntheticEntry() {
        var instances = handler.listInstances("platform", 0L);
        assertEquals(1, instances.size());
    }

    @Test
    void startDispatchesEachActionToItsOwnServiceAndReturnsTheJobId() {
        when(cveService.triggerSync()).thenReturn(11L);
        when(cweService.triggerSync()).thenReturn(12L);
        when(capecService.triggerSync()).thenReturn(13L);
        when(attackService.triggerSync()).thenReturn(14L);
        when(owaspService.triggerSync()).thenReturn(15L);
        when(cisaKevService.triggerSync()).thenReturn(16L);
        when(vulnCheckKevService.triggerSync()).thenReturn(17L);
        when(exploitService.triggerExploitDbSync()).thenReturn(18L);
        when(exploitService.triggerVulnCheckXdbSync()).thenReturn(19L);

        assertEquals(11L, handler.start("cve_update", 0L, "platform", 0L, Map.of()));
        assertEquals(12L, handler.start("cwe", 0L, "platform", 0L, Map.of()));
        assertEquals(13L, handler.start("capec", 0L, "platform", 0L, Map.of()));
        assertEquals(14L, handler.start("attack", 0L, "platform", 0L, Map.of()));
        assertEquals(15L, handler.start("owasp", 0L, "platform", 0L, Map.of()));
        assertEquals(16L, handler.start("cisa_kev", 0L, "platform", 0L, Map.of()));
        assertEquals(17L, handler.start("vulncheck_kev", 0L, "platform", 0L, Map.of()));
        assertEquals(18L, handler.start("exploitdb", 0L, "platform", 0L, Map.of()));
        assertEquals(19L, handler.start("vulncheck_xdb", 0L, "platform", 0L, Map.of()));
    }

    @Test
    void startRejectsUnknownAction() {
        assertThrows(IllegalArgumentException.class, () -> handler.start("bogus", 0L, "platform", 0L, Map.of()));
    }

    @Test
    void checkStatusMapsCompletedJobToCompletedResult() {
        Job job = mock(Job.class);
        when(job.getStatus()).thenReturn("completed");
        when(job.getResult()).thenReturn("{\"upserted\":42}");
        when(jobRepo.findById(11L)).thenReturn(Optional.of(job));

        IntegrationActionResult result = handler.checkStatus(11L);

        assertEquals(IntegrationActionResult.COMPLETED, result.state());
        assertEquals("{\"upserted\":42}", result.outputJson());
    }

    @Test
    void checkStatusMapsFailedJobToFailedResult() {
        Job job = mock(Job.class);
        when(job.getStatus()).thenReturn("failed");
        when(job.getError()).thenReturn("feed unreachable");
        when(jobRepo.findById(11L)).thenReturn(Optional.of(job));

        IntegrationActionResult result = handler.checkStatus(11L);

        assertEquals(IntegrationActionResult.FAILED, result.state());
        assertEquals("feed unreachable", result.error());
    }

    @Test
    void checkStatusMapsRunningJobToRunningResult() {
        Job job = mock(Job.class);
        when(job.getStatus()).thenReturn("running");
        when(jobRepo.findById(11L)).thenReturn(Optional.of(job));

        assertEquals(IntegrationActionResult.RUNNING, handler.checkStatus(11L).state());
    }
}
