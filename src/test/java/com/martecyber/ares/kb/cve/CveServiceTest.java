package com.martecyber.ares.kb.cve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.aql.materialize.KbMaterializationService;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.kb.exploits.ExploitRepository;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for {@link CveService}'s new workflow-event dispatch call sites — the
 * ones that turn "a CVE's KEV status flipped" / "a CVE just got a new PoC" / "a CVE's own
 * exploitCount was recomputed" into an {@code onCveUpdated} dispatch, always scoped to exactly
 * the changed subset (never the whole matching set) so it's a genuine transition signal, not
 * "is currently true." {@code flushBatch} itself (the {@code MongoTemplate.bulkOps} path) isn't
 * covered here — {@code BulkOperations} is awkward to mock meaningfully; its created/updated
 * split (compare against a pre-fetched {@code lastModifiedAt} map) is simple enough to trust by
 * reading, and is exercised for real by the live dev-stack verification instead.
 */
class CveServiceTest {

    private CveRepository repo;
    private ExploitRepository exploitRepo;
    private WorkflowEventDispatcher workflowEventDispatcher;
    private CveService service;

    @BeforeEach
    void setUp() {
        repo = mock(CveRepository.class);
        CveSyncStateRepository stateRepo = mock(CveSyncStateRepository.class);
        JobService jobService = mock(JobService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        exploitRepo = mock(ExploitRepository.class);
        KbMaterializationService materializationService = mock(KbMaterializationService.class);
        CveAqlRegistry aqlRegistry = mock(CveAqlRegistry.class);
        workflowEventDispatcher = mock(WorkflowEventDispatcher.class);
        service = new CveService(repo, stateRepo, jobService, objectMapper, exploitRepo,
            materializationService, aqlRegistry, workflowEventDispatcher);
    }

    private CveEntry entryWithKev(String cveId, boolean kevListed, LocalDate kevDateAdded) {
        CveEntry e = new CveEntry();
        e.setCveId(cveId);
        e.setKevListed(kevListed);
        e.setKevDateAdded(kevDateAdded);
        e.setVulncheckKevListed(false);
        return e;
    }

    private CveEntry entryWithVulncheckKev(String cveId, boolean listed, LocalDate dateAdded) {
        CveEntry e = new CveEntry();
        e.setCveId(cveId);
        e.setVulncheckKevListed(listed);
        e.setVulncheckKevDateAdded(dateAdded);
        e.setKevListed(false);
        return e;
    }

    @Test
    void updateVulnCheckKevFlagsUsesTheVulncheckCatalogLabel() {
        CveEntry newlyListed = entryWithVulncheckKev("CVE-2024-0004", false, null);
        when(repo.findByVulncheckKevListedTrueAndCveIdNotIn(anyCollection())).thenReturn(List.of());
        when(repo.findAllByCveIdIn(anyCollection())).thenReturn(List.of(newlyListed));

        service.updateVulnCheckKevFlags(Map.of("CVE-2024-0004", LocalDate.of(2024, 7, 1)));

        verify(workflowEventDispatcher, times(1)).onCveKevAdded(newlyListed, "vulncheck");
    }

    @Test
    void updateKevFlagsDispatchesOnlyForEntriesThatActuallyFlipped() {
        // "CVE-2024-0001" is newly KEV-listed this sync; "CVE-2024-0002" was already KEV-listed
        // with the same date (no real change) and must NOT dispatch.
        CveEntry newlyListed = entryWithKev("CVE-2024-0001", false, null);
        CveEntry alreadyListedUnchanged = entryWithKev("CVE-2024-0002", true, LocalDate.of(2024, 1, 1));
        when(repo.findByKevListedTrueAndCveIdNotIn(anyCollection())).thenReturn(List.of());
        when(repo.findAllByCveIdIn(anyCollection())).thenReturn(List.of(newlyListed, alreadyListedUnchanged));

        service.updateKevFlags(Map.of(
            "CVE-2024-0001", LocalDate.of(2024, 6, 1),
            "CVE-2024-0002", LocalDate.of(2024, 1, 1)));

        verify(workflowEventDispatcher, times(1)).onCveUpdated(newlyListed);
        verify(workflowEventDispatcher, never()).onCveUpdated(alreadyListedUnchanged);
        // The concrete "added to KEV" event fires only for the strict false→true transition, not
        // for the date-only correction on an already-listed entry.
        verify(workflowEventDispatcher, times(1)).onCveKevAdded(newlyListed, "cisa");
        verify(workflowEventDispatcher, never()).onCveKevAdded(eq(alreadyListedUnchanged), any());
    }

    @Test
    void updateKevFlagsDispatchesForEntriesThatFellOffTheFeed() {
        CveEntry stale = entryWithKev("CVE-2024-0003", true, LocalDate.of(2023, 5, 1));
        when(repo.findByKevListedTrueAndCveIdNotIn(anyCollection())).thenReturn(List.of(stale));
        when(repo.findAllByCveIdIn(anyCollection())).thenReturn(List.of());

        service.updateKevFlags(Map.of());

        verify(workflowEventDispatcher, times(1)).onCveUpdated(stale);
        // Falling *out* of a KEV catalog is not "added to KEV catalog".
        verify(workflowEventDispatcher, never()).onCveKevAdded(eq(stale), any());
    }

    @Test
    void adjustExploitCountDispatchesForEveryMatchSinceDeltaIsNeverZero() {
        CveEntry a = new CveEntry(); a.setCveId("CVE-2024-0010");
        CveEntry b = new CveEntry(); b.setCveId("CVE-2024-0011");
        when(repo.findByCveIdIgnoreCaseIn(anyCollection())).thenReturn(List.of(a, b));

        service.adjustExploitCount(Set.of("CVE-2024-0010", "CVE-2024-0011"), 1);

        verify(workflowEventDispatcher).onCveUpdated(a);
        verify(workflowEventDispatcher).onCveUpdated(b);
    }

    /** Regression for the delete-flow bug this phase found live: ExploitEntry.getCveIds() returns
     *  lowercase-normalized IDs (HAS convention), so a same-case-only lookup here would silently
     *  match nothing and leave ares.cve.exploit_count stuck — same class of bug already fixed once
     *  for recomputeExploitCounts, but this call site was missed in the initial rewrite. */
    @Test
    void adjustExploitCountMatchesCaseInsensitivelyAgainstLowercaseCallerInput() {
        CveEntry cve = new CveEntry();
        cve.setCveId("CVE-2021-44228");
        cve.setExploitCount(1);
        when(repo.findByCveIdIgnoreCaseIn(Set.of("cve-2021-44228"))).thenReturn(List.of(cve));

        service.adjustExploitCount(List.of("cve-2021-44228"), -1);

        assertEquals(0, cve.getExploitCount());
        verify(workflowEventDispatcher).onCveUpdated(cve);
    }

    @Test
    void adjustExploitCountIsANoOpForAnEmptyIdSet() {
        service.adjustExploitCount(Set.of(), 1);
        verifyNoInteractions(workflowEventDispatcher);
        verify(repo, never()).findByCveIdIgnoreCaseIn(anyCollection());
    }

    /** Now genuinely unit-testable (Phase 6 of the AQL-wide initiative) — recomputeExploitCounts
     *  used to drive a MongoTemplate aggregation into a private nested record that couldn't be
     *  meaningfully mocked; it now calls a plain repository method instead. The core new-behavior
     *  regression this test guards: ares.exploit.cve_ids is lowercase-normalized at write time,
     *  ares.cve.cve_id is stored uppercase, so the match between them has to be case-insensitive —
     *  a same-case-only lookup (findAllByCveIdIn) would silently match nothing here. */
    @Test
    void recomputeExploitCountsMatchesCaseInsensitivelyAgainstLowercaseGroupedIds() {
        CveEntry cve = new CveEntry();
        cve.setCveId("CVE-2021-1234");
        cve.setExploitCount(0);

        when(repo.findByExploitCountGreaterThan(0)).thenReturn(List.of());
        when(repo.findByCveIdIgnoreCaseIn(Set.of("cve-2021-1234"))).thenReturn(List.of(cve));
        when(exploitRepo.countGroupedByCveId()).thenReturn(List.<Object[]>of(new Object[]{"cve-2021-1234", 3L}));

        service.recomputeExploitCounts();

        assertEquals(3, cve.getExploitCount());
        // toSave.values() is a Map.values() collection view, not a List — verify contents via
        // an ArgumentCaptor instead of List.of(cve).equals(...), which Mockito's exact-match
        // verify() would otherwise reject even though the elements are identical.
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(List.of(cve), List.copyOf(captor.getValue()));
        verify(workflowEventDispatcher).onCveUpdated(cve);
    }

    @Test
    void recomputeExploitCountsDoesNotDispatchWhenCountIsUnchanged() {
        CveEntry cve = new CveEntry();
        cve.setCveId("CVE-2021-1234");
        cve.setExploitCount(3);

        when(repo.findByExploitCountGreaterThan(0)).thenReturn(List.of(cve));
        when(repo.findByCveIdIgnoreCaseIn(Set.of("cve-2021-1234"))).thenReturn(List.of(cve));
        when(exploitRepo.countGroupedByCveId()).thenReturn(List.<Object[]>of(new Object[]{"cve-2021-1234", 3L}));

        service.recomputeExploitCounts();

        assertEquals(3, cve.getExploitCount());
        verifyNoInteractions(workflowEventDispatcher);
        verify(repo, never()).saveAll(any());
    }
}
