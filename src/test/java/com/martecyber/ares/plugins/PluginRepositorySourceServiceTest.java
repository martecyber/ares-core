package com.martecyber.ares.plugins;

import com.martecyber.ares.plugins.dto.PluginRepositorySourceDto;
import com.martecyber.ares.plugins.dto.RepositoryCatalogDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PluginRepositorySourceServiceTest {

    private PluginRepositorySourceRepository repo;
    private PluginRepositoryClient client;
    private PluginRepositorySourceService service;

    @BeforeEach
    void setUp() {
        repo = mock(PluginRepositorySourceRepository.class);
        client = mock(PluginRepositoryClient.class);
        service = new PluginRepositorySourceService(repo, client);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void addValidatesTheUrlByFetchingItsCatalogBeforeSaving() {
        when(client.fetchCatalog("https://repo.example.com"))
            .thenReturn(new RepositoryCatalogDto("Community repo", "2026-01-01", List.of()));

        PluginRepositorySourceDto result = service.add("My repo", "https://repo.example.com", 7L);

        assertEquals("My repo", result.name());
        assertFalse(result.official());
        assertTrue(result.enabled());
        verify(repo).save(any());
    }

    @Test
    void addFallsBackToTheCatalogsOwnNameWhenNoneIsGiven() {
        when(client.fetchCatalog("https://repo.example.com"))
            .thenReturn(new RepositoryCatalogDto("Community repo", "2026-01-01", List.of()));

        PluginRepositorySourceDto result = service.add(null, "https://repo.example.com", 7L);

        assertEquals("Community repo", result.name());
    }

    @Test
    void addPropagatesAFetchFailureInsteadOfSavingAnUnreachableRepository() {
        when(client.fetchCatalog("https://broken.example.com"))
            .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "unreachable"));

        assertThrows(ResponseStatusException.class, () -> service.add("Broken", "https://broken.example.com", 7L));
        verify(repo, never()).save(any());
    }

    @Test
    void theOfficialRepositoryCanBeDisabledButNotDeleted() {
        PluginRepositorySource official = new PluginRepositorySource();
        official.setOfficial(true);
        when(repo.findById(1L)).thenReturn(Optional.of(official));

        assertThrows(ResponseStatusException.class, () -> service.delete(1L));
        verify(repo, never()).delete(any());

        service.setEnabled(1L, false);
        assertFalse(official.isEnabled());
    }

    @Test
    void aNonOfficialRepositoryCanBeDeleted() {
        PluginRepositorySource added = new PluginRepositorySource();
        added.setOfficial(false);
        when(repo.findById(2L)).thenReturn(Optional.of(added));

        service.delete(2L);

        verify(repo).delete(added);
    }
}
