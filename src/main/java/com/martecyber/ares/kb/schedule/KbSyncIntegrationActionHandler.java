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
import com.martecyber.ares.workflows.integrations.IntegrationActionDescriptor;
import com.martecyber.ares.workflows.integrations.IntegrationActionHandler;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import com.martecyber.ares.workflows.integrations.IntegrationInstanceDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wires the platform's KB sync services into {@code ACTION_INTEGRATION_CALL} — the exact same 9
 * sync types {@link KbSyncScheduleService}'s own recurring poller already dispatches (kept in
 * sync with its {@code dispatch(String)} switch deliberately, not reinvented), just reachable
 * one-shot from a platform-scoped workflow instead of only from a standalone cron schedule. Every
 * {@code triggerSync()} call already returns a {@code Job} id (the same async mechanism {@code
 * ACTION_SYNC} waits on), so this handler needs no new execution machinery — {@link #checkStatus}
 * just reads the {@link Job} row directly, mirroring {@code WorkflowStepPoller.pollJobs}.
 */
@Component
public class KbSyncIntegrationActionHandler implements IntegrationActionHandler {

    // KB sync has no "configured instance" concept (unlike Caido's per-connector model) — every
    // action targets the one built-in platform KB, so listInstances always returns this single
    // synthetic entry rather than requiring a real integration row to pick from.
    private static final IntegrationInstanceDescriptor SINGLE_INSTANCE = new IntegrationInstanceDescriptor(0L, "Knowledge Base (platform)");

    private final CveService cveService;
    private final CweService cweService;
    private final CapecService capecService;
    private final AttackService attackService;
    private final OwaspService owaspService;
    private final CisaKevService cisaKevService;
    private final VulnCheckKevService vulnCheckKevService;
    private final ExploitService exploitService;
    private final JobRepository jobRepo;

    public KbSyncIntegrationActionHandler(CveService cveService, CweService cweService, CapecService capecService,
                                           AttackService attackService, OwaspService owaspService,
                                           CisaKevService cisaKevService, VulnCheckKevService vulnCheckKevService,
                                           ExploitService exploitService, JobRepository jobRepo) {
        this.cveService = cveService;
        this.cweService = cweService;
        this.capecService = capecService;
        this.attackService = attackService;
        this.owaspService = owaspService;
        this.cisaKevService = cisaKevService;
        this.vulnCheckKevService = vulnCheckKevService;
        this.exploitService = exploitService;
        this.jobRepo = jobRepo;
    }

    @Override
    public String integrationType() { return "kb-sync"; }

    @Override
    public String integrationTypeLabel() { return "Knowledge Base Sync"; }

    @Override
    public boolean isDataSourceIntegration() { return false; }

    @Override
    public Set<String> supportedScopes() { return Set.of("platform"); }

    @Override
    public List<IntegrationActionDescriptor> describeActions() {
        return List.of(
            new IntegrationActionDescriptor("cve_update", "CVE sync"),
            new IntegrationActionDescriptor("cwe", "CWE sync"),
            new IntegrationActionDescriptor("capec", "CAPEC sync"),
            new IntegrationActionDescriptor("attack", "ATT&CK sync"),
            new IntegrationActionDescriptor("owasp", "OWASP sync"),
            new IntegrationActionDescriptor("cisa_kev", "CISA KEV sync"),
            new IntegrationActionDescriptor("vulncheck_kev", "VulnCheck KEV sync"),
            new IntegrationActionDescriptor("exploitdb", "ExploitDB sync"),
            new IntegrationActionDescriptor("vulncheck_xdb", "VulnCheck XDB sync"));
    }

    @Override
    public List<IntegrationInstanceDescriptor> listInstances(String scopeKind, Long scopeId) {
        return List.of(SINGLE_INSTANCE);
    }

    @Override
    public Long start(String actionCode, Long integrationInstanceId, String scopeKind, Long scopeId, Map<String, Object> params) {
        return switch (actionCode) {
            case "cve_update" -> cveService.triggerSync();
            case "cwe" -> cweService.triggerSync();
            case "capec" -> capecService.triggerSync();
            case "attack" -> attackService.triggerSync();
            case "owasp" -> owaspService.triggerSync();
            case "cisa_kev" -> cisaKevService.triggerSync();
            case "vulncheck_kev" -> vulnCheckKevService.triggerSync();
            case "exploitdb" -> exploitService.triggerExploitDbSync();
            case "vulncheck_xdb" -> exploitService.triggerVulnCheckXdbSync();
            default -> throw new IllegalArgumentException("Unknown KB sync action: " + actionCode);
        };
    }

    @Override
    public IntegrationActionResult checkStatus(Long refId) {
        Job job = jobRepo.findById(refId).orElse(null);
        if (job == null) {
            return new IntegrationActionResult(IntegrationActionResult.FAILED, null, "KB sync job " + refId + " no longer exists");
        }
        return switch (job.getStatus()) {
            case "completed" -> new IntegrationActionResult(IntegrationActionResult.COMPLETED, job.getResult() != null ? job.getResult() : "{}", null);
            case "failed", "cancelled" -> new IntegrationActionResult(IntegrationActionResult.FAILED, null,
                job.getError() != null ? job.getError() : "KB sync job " + job.getStatus());
            default -> new IntegrationActionResult(IntegrationActionResult.RUNNING, null, null);
        };
    }
}
