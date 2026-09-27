package com.martecyber.ares.plugins;

import com.martecyber.ares.plugins.dto.PluginBrowseEntryDto;
import com.martecyber.ares.plugins.dto.PluginBrowseVersionDto;
import com.martecyber.ares.plugins.dto.RepositoryCatalogDto;
import com.martecyber.ares.plugins.dto.RepositoryPluginVersionsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** {@link PluginBrowseService} merges repository catalogs with installed state and this running
 *  instance's own ares-core/ares-ui version (hardcoded to the field initializers' defaults —
 *  0.6.0-beta40/0.1.11-beta65 — since these are built via {@code new}, not Spring, same as
 *  {@link PluginServiceTest}). */
class PluginBrowseServiceTest {

    private PluginRepositorySourceRepository repoSourceRepo;
    private PluginRepository pluginRepo;
    private PluginRepositoryClient client;
    private PluginBrowseService service;

    @BeforeEach
    void setUp() {
        repoSourceRepo = mock(PluginRepositorySourceRepository.class);
        pluginRepo = mock(PluginRepository.class);
        client = mock(PluginRepositoryClient.class);
        service = new PluginBrowseService(repoSourceRepo, pluginRepo, client);
    }

    private PluginRepositorySource repoSource(long id, String name, String baseUrl) {
        PluginRepositorySource s = new PluginRepositorySource();
        s.setName(name);
        s.setBaseUrl(baseUrl);
        s.setEnabled(true);
        s.setAddedAt(OffsetDateTime.now());
        // id is normally DB-generated — set via reflection-free approach: repositories under
        // test only ever need getId() to return something stable, achieved by relying on
        // equals-by-reference across this test (the DTO carries whatever getId() returns).
        return s;
    }

    private RepositoryCatalogDto.Entry catalogEntry(String id) {
        return new RepositoryCatalogDto.Entry(id, "Test Plugin " + id, "Acme", null, null, "1.0.0", null);
    }

    private RepositoryPluginVersionsDto.Version version(String v, String minApi, String maxApi) {
        return new RepositoryPluginVersionsDto.Version(v, "1", minApi, maxApi, null, null,
            List.of(), "https://example.com/" + v + ".jar", "abc123", "2026-01-01", null, false);
    }

    @Test
    void aPluginWithNoCompatibleVersionIsMarkedNotInstallable() {
        PluginRepositorySource repo = repoSource(1, "Official", "https://repo.example.com");
        when(repoSourceRepo.findAllByEnabledTrue()).thenReturn(List.of(repo));
        when(pluginRepo.findAll()).thenReturn(List.of());
        when(client.fetchCatalog(repo.getBaseUrl()))
            .thenReturn(new RepositoryCatalogDto("Official", "2026-01-01", List.of(catalogEntry("nmap"))));
        when(client.fetchPluginVersions(eq(repo.getBaseUrl()), eq("nmap"), any()))
            .thenReturn(new RepositoryPluginVersionsDto("nmap", "Nmap", "Acme", null, null, "A test plugin", List.of(version("1.0.0", "99.0.0", null))));

        List<PluginBrowseEntryDto> result = service.browse();

        assertEquals(1, result.size());
        assertFalse(result.get(0).installable());
        assertNull(result.get(0).latestCompatibleVersion());
    }

    @Test
    void anInstalledPluginWithANewerCompatibleVersionShowsUpdateAvailable() {
        PluginRepositorySource repo = repoSource(1, "Official", "https://repo.example.com");
        when(repoSourceRepo.findAllByEnabledTrue()).thenReturn(List.of(repo));

        Plugin installed = new Plugin();
        installed.setPluginId("nmap");
        installed.setVersion("1.0.0");
        when(pluginRepo.findAll()).thenReturn(List.of(installed));

        when(client.fetchCatalog(repo.getBaseUrl()))
            .thenReturn(new RepositoryCatalogDto("Official", "2026-01-01", List.of(catalogEntry("nmap"))));
        when(client.fetchPluginVersions(eq(repo.getBaseUrl()), eq("nmap"), any()))
            .thenReturn(new RepositoryPluginVersionsDto("nmap", "Nmap", "Acme", null, null, "A test plugin", List.of(version("1.1.0", null, null), version("1.0.0", null, null))));

        List<PluginBrowseEntryDto> result = service.browse();

        assertEquals(1, result.size());
        assertEquals("1.0.0", result.get(0).installedVersion());
        assertEquals("1.1.0", result.get(0).latestCompatibleVersion());
        assertTrue(result.get(0).updateAvailable());
    }

    @Test
    void aYankedVersionIsNeverTheLatestCompatibleOne() {
        PluginRepositorySource repo = repoSource(1, "Official", "https://repo.example.com");
        when(repoSourceRepo.findAllByEnabledTrue()).thenReturn(List.of(repo));
        when(pluginRepo.findAll()).thenReturn(List.of());
        when(client.fetchCatalog(repo.getBaseUrl()))
            .thenReturn(new RepositoryCatalogDto("Official", "2026-01-01", List.of(catalogEntry("nmap"))));
        RepositoryPluginVersionsDto.Version yanked = new RepositoryPluginVersionsDto.Version(
            "1.1.0", "1", null, null, null, null, List.of(), "u", "c", "2026-01-01", null, true);
        when(client.fetchPluginVersions(eq(repo.getBaseUrl()), eq("nmap"), any()))
            .thenReturn(new RepositoryPluginVersionsDto("nmap", "Nmap", "Acme", null, null, "A test plugin", List.of(yanked, version("1.0.0", null, null))));

        List<PluginBrowseEntryDto> result = service.browse();

        assertEquals("1.0.0", result.get(0).latestCompatibleVersion());
    }

    @Test
    void oneRepositoryFailingDoesNotHideAnotherRepositorysPlugins() {
        PluginRepositorySource broken = repoSource(1, "Broken", "https://broken.example.com");
        PluginRepositorySource ok = repoSource(2, "Ok", "https://ok.example.com");
        when(repoSourceRepo.findAllByEnabledTrue()).thenReturn(List.of(broken, ok));
        when(pluginRepo.findAll()).thenReturn(List.of());
        when(client.fetchCatalog(broken.getBaseUrl())).thenThrow(new RuntimeException("unreachable"));
        when(client.fetchCatalog(ok.getBaseUrl()))
            .thenReturn(new RepositoryCatalogDto("Ok", "2026-01-01", List.of(catalogEntry("wpscan"))));
        when(client.fetchPluginVersions(eq(ok.getBaseUrl()), eq("wpscan"), any()))
            .thenReturn(new RepositoryPluginVersionsDto("wpscan", "WPScan", "Acme", null, null, "A test plugin", List.of(version("1.0.0", null, null))));

        List<PluginBrowseEntryDto> result = service.browse();

        assertEquals(1, result.size());
        assertEquals("wpscan", result.get(0).pluginId());
    }

    @Test
    void browseVersionsFlagsEachVersionsOwnCompatibility() {
        PluginRepositorySource repo = repoSource(1, "Official", "https://repo.example.com");
        when(repoSourceRepo.findById(1L)).thenReturn(java.util.Optional.of(repo));
        when(client.fetchPluginVersions(eq(repo.getBaseUrl()), eq("nmap"), any()))
            .thenReturn(new RepositoryPluginVersionsDto("nmap", "Nmap", "Acme", null, null, "A test plugin",
                List.of(version("2.0.0", "99.0.0", null), version("1.0.0", null, null))));

        List<PluginBrowseVersionDto> result = service.browseVersions(1L, "nmap");

        assertFalse(result.get(0).compatible());
        assertNotNull(result.get(0).incompatibleReason());
        assertTrue(result.get(1).compatible());
        assertNull(result.get(1).incompatibleReason());
    }
}
