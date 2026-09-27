package com.martecyber.ares.kb.cwe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.jobs.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for {@link CweService} — specifically the id-prefix-stripping in {@link
 * CweService#findById} and {@link CweService#resolveNames}, which used to be case-sensitive
 * ({@code startsWith("CWE-")}) and silently failed to strip a lowercase-prefixed id (e.g.
 * "cwe-770", the exact form {@code CveEntry.cwes} stores — see CveEntry#setCwes) — the id never
 * matched {@code CweEntry.cweId} (bare, e.g. "770"), so a CVE's CWE reference resolved to no name
 * at all in the UI. No Spring context.
 */
class CweServiceTest {

    private CweRepository repo;
    private CweService service;

    @BeforeEach
    void setUp() {
        repo = mock(CweRepository.class);
        service = new CweService(repo, mock(CweXmlParser.class), mock(JobService.class),
            new ObjectMapper(), mock(CweAqlRegistry.class));
    }

    private CweEntry entry(String cweId, String name) {
        CweEntry e = new CweEntry();
        e.setCweId(cweId);
        e.setName(name);
        return e;
    }

    // ── findById() ───────────────────────────────────────────────────────────

    @Test
    void findByIdStripsAnUppercasePrefix() {
        when(repo.findByCweId("770")).thenReturn(Optional.of(entry("770", "Missing Reference")));
        assertTrue(service.findById("CWE-770").isPresent());
        verify(repo).findByCweId("770");
    }

    @Test
    void findByIdStripsALowercasePrefix() {
        when(repo.findByCweId("770")).thenReturn(Optional.of(entry("770", "Missing Reference")));
        assertTrue(service.findById("cwe-770").isPresent());
        verify(repo).findByCweId("770");
    }

    @Test
    void findByIdPassesThroughABareId() {
        when(repo.findByCweId("770")).thenReturn(Optional.of(entry("770", "Missing Reference")));
        assertTrue(service.findById("770").isPresent());
        verify(repo).findByCweId("770");
    }

    // ── resolveNames() ───────────────────────────────────────────────────────

    @Test
    void resolveNamesStripsMixedCasePrefixesBeforeLookup() {
        when(repo.findAllByCweIdIn(List.of("770", "79"))).thenReturn(List.of(
            entry("770", "Missing Reference"), entry("79", "Cross-site Scripting")));

        List<CweService.NameDto> result = service.resolveNames(List.of("cwe-770", "CWE-79"));

        assertEquals(2, result.size());
        verify(repo).findAllByCweIdIn(List.of("770", "79"));
    }

    @Test
    void resolveNamesReturnsEmptyForAnUnknownId() {
        when(repo.findAllByCweIdIn(anyList())).thenReturn(List.of());
        assertTrue(service.resolveNames(List.of("cwe-999999")).isEmpty());
    }
}
