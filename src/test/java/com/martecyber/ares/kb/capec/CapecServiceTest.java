package com.martecyber.ares.kb.capec;

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
 * Pure Mockito unit test for {@link CapecService} — the same id-prefix-stripping bug class as
 * {@link com.martecyber.ares.kb.cwe.CweServiceTest} (case-sensitive {@code startsWith("CAPEC-")}
 * silently failing to strip a lowercase-prefixed id). No Spring context.
 */
class CapecServiceTest {

    private CapecRepository repo;
    private CapecService service;

    @BeforeEach
    void setUp() {
        repo = mock(CapecRepository.class);
        service = new CapecService(repo, mock(CapecXmlParser.class), mock(JobService.class),
            new ObjectMapper(), mock(CapecAqlRegistry.class));
    }

    private CapecEntry entry(String capecId, String name) {
        CapecEntry e = new CapecEntry();
        e.setCapecId(capecId);
        e.setName(name);
        return e;
    }

    // ── findById() ───────────────────────────────────────────────────────────

    @Test
    void findByIdStripsAnUppercasePrefix() {
        when(repo.findByCapecId("94")).thenReturn(Optional.of(entry("94", "Adversary in the Middle")));
        assertTrue(service.findById("CAPEC-94").isPresent());
        verify(repo).findByCapecId("94");
    }

    @Test
    void findByIdStripsALowercasePrefix() {
        when(repo.findByCapecId("94")).thenReturn(Optional.of(entry("94", "Adversary in the Middle")));
        assertTrue(service.findById("capec-94").isPresent());
        verify(repo).findByCapecId("94");
    }

    // ── resolveNames() ───────────────────────────────────────────────────────

    @Test
    void resolveNamesStripsMixedCasePrefixesBeforeLookup() {
        when(repo.findAllByCapecIdIn(List.of("94", "63"))).thenReturn(List.of(
            entry("94", "Adversary in the Middle"), entry("63", "Cross-Site Scripting")));

        List<CapecService.NameDto> result = service.resolveNames(List.of("capec-94", "CAPEC-63"));

        assertEquals(2, result.size());
        verify(repo).findAllByCapecIdIn(List.of("94", "63"));
    }

    @Test
    void resolveNamesReturnsEmptyForAnUnknownId() {
        when(repo.findAllByCapecIdIn(anyList())).thenReturn(List.of());
        assertTrue(service.resolveNames(List.of("capec-999999")).isEmpty());
    }
}
