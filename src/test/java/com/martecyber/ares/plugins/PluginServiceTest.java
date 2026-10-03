package com.martecyber.ares.plugins;

import com.martecyber.ares.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** {@link PluginLoader}'s own JAR/classloading mechanics are exercised end-to-end by actually
 *  building and loading ares-plugin-fortirecon (see this repo's own plugin pilot) rather than
 *  here — mocked out below so this only checks {@link PluginService}'s own install/enable/
 *  disable/uninstall bookkeeping and validation. */
class PluginServiceTest {

    private PluginRepository repo;
    private PluginLoader loader;
    private PluginService service;

    @TempDir
    Path pluginsDir;

    @BeforeEach
    void setUp() {
        repo = mock(PluginRepository.class);
        loader = mock(PluginLoader.class);
        var repoSourceRepo = mock(PluginRepositorySourceRepository.class);
        var repoClient = mock(PluginRepositoryClient.class);
        var storage = mock(StorageService.class);
        service = new PluginService(repo, loader, repoSourceRepo, repoClient, storage, pluginsDir.toString());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MultipartFile jarFile() {
        return new MockMultipartFile("file", "plugin.jar", "application/java-archive", new byte[]{1, 2, 3, 4});
    }

    private PluginManifest manifest(String id, String version) {
        return new PluginManifest(id, version, "Test Plugin", "Acme", "MIT", "A test plugin", "1", null, null,
            List.of(), List.of(), List.of(), null, null, null, null);
    }

    private static PluginManifest.PluginDependency dependency(String pluginId) {
        return new PluginManifest.PluginDependency(pluginId, null, null);
    }

    private Plugin installedPlugin(String id, String version) {
        Plugin p = new Plugin();
        p.setPluginId(id);
        p.setVersion(version);
        p.setFilename(id + "-" + version + ".jar");
        p.setEnabled(true);
        return p;
    }

    @Test
    void installFromUploadSavesAndLoadsAValidPlugin() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(manifest("acme-widget", "1.0.0"));
        when(repo.findByPluginId("acme-widget")).thenReturn(Optional.empty());

        var result = service.installFromUpload(jarFile(), 7L, false);

        assertInstanceOf(InstallResult.Installed.class, result);
        var dto = ((InstallResult.Installed) result).plugin();
        assertEquals("acme-widget", dto.pluginId());
        assertEquals("upload", dto.source());
        assertTrue(dto.enabled());
        verify(loader).install(argThat(m -> m.id().equals("acme-widget")), any(File.class));
        verify(repo).save(argThat(p -> p.getInstalledBy().equals(7L)));
    }

    @Test
    void installFromUploadRejectsAnUnsupportedSdkVersion() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(
            new PluginManifest("acme-widget", "1.0.0", "Test Plugin", "Acme", "MIT", null, "99", null, null,
                List.of(), List.of(), List.of(), null, null, null, null));

        var ex = assertThrows(ResponseStatusException.class, () -> service.installFromUpload(jarFile(), 7L, false));
        assertTrue(ex.getReason().contains("SDK version"));
        verify(loader, never()).install(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void installFromUploadRejectsAMissingDependency() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(
            new PluginManifest("hackerone", "1.0.0", "HackerOne", "Acme", "MIT", null, "1", null, null,
                List.of(dependency("bughunting")), List.of(), List.of(), null, null, null, null));
        when(repo.findByPluginId("bughunting")).thenReturn(Optional.empty());

        var ex = assertThrows(PluginDependencyException.class, () -> service.installFromUpload(jarFile(), 7L, false));
        assertEquals("hackerone", ex.getPluginId());
        assertEquals("bughunting", ex.getDependencyId());
        assertFalse(ex.isDisabled());
        verify(loader, never()).install(any(), any());
    }

    @Test
    void installFromUploadRejectsADisabledDependency() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(
            new PluginManifest("hackerone", "1.0.0", "HackerOne", "Acme", "MIT", null, "1", null, null,
                List.of(dependency("bughunting")), List.of(), List.of(), null, null, null, null));
        Plugin base = installedPlugin("bughunting", "1.0.0");
        base.setEnabled(false);
        when(repo.findByPluginId("bughunting")).thenReturn(Optional.of(base));

        var ex = assertThrows(PluginDependencyException.class, () -> service.installFromUpload(jarFile(), 7L, false));
        assertEquals("hackerone", ex.getPluginId());
        assertEquals("bughunting", ex.getDependencyId());
        assertTrue(ex.isDisabled());
        verify(loader, never()).install(any(), any());
    }

    @Test
    void installFromUploadRejectsADependencyInstalledOutsideTheDeclaredVersionRange() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(
            new PluginManifest("hackerone", "1.0.0", "HackerOne", "Acme", "MIT", null, "1", null, null,
                List.of(new PluginManifest.PluginDependency("bughunting", "2.0.0", null)), List.of(), List.of(), null, null, null, null));
        when(repo.findByPluginId("bughunting")).thenReturn(Optional.of(installedPlugin("bughunting", "1.0.0")));

        var ex = assertThrows(PluginDependencyException.class, () -> service.installFromUpload(jarFile(), 7L, false));
        assertEquals("hackerone", ex.getPluginId());
        assertEquals("bughunting", ex.getDependencyId());
        assertTrue(ex.getMessage().contains("1.0.0"));
        verify(loader, never()).install(any(), any());
    }

    @Test
    void installFromUploadSucceedsWhenTheDependencyIsInstalledAndEnabled() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(
            new PluginManifest("hackerone", "1.0.0", "HackerOne", "Acme", "MIT", null, "1", null, null,
                List.of(dependency("bughunting")), List.of(), List.of(), null, null, null, null));
        when(repo.findByPluginId("bughunting")).thenReturn(Optional.of(installedPlugin("bughunting", "1.0.0")));
        when(repo.findByPluginId("hackerone")).thenReturn(Optional.empty());

        var result = service.installFromUpload(jarFile(), 7L, false);

        assertInstanceOf(InstallResult.Installed.class, result);
        verify(loader).install(argThat(m -> m.id().equals("hackerone")), any(File.class));
    }

    @Test
    void installFromUploadRejectsAnIncompatibleAresApiVersionRange() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(
            new PluginManifest("acme-widget", "1.0.0", "Test Plugin", "Acme", "MIT", null, "1", null, null,
                List.of(), List.of(), List.of(), "99.0.0", null, null, null));

        var ex = assertThrows(ResponseStatusException.class, () -> service.installFromUpload(jarFile(), 7L, false));
        assertTrue(ex.getReason().contains("ares-core"));
        verify(loader, never()).install(any(), any());
    }

    @Test
    void installFromUploadReportsASameVersionConflictAndTouchesNothing() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(manifest("acme-widget", "1.0.0"));
        when(repo.findByPluginId("acme-widget")).thenReturn(Optional.of(installedPlugin("acme-widget", "1.0.0")));

        var result = service.installFromUpload(jarFile(), 7L, false);

        assertEquals(new InstallResult.VersionConflict("same", "1.0.0", "1.0.0"), result);
        verify(loader, never()).install(any(), any());
        verify(repo, never()).save(any());
        verify(repo, never()).delete(any());
    }

    @Test
    void installFromUploadReportsAnUpgradeConflictWhenTheNewVersionIsHigher() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(manifest("acme-widget", "1.1.0"));
        when(repo.findByPluginId("acme-widget")).thenReturn(Optional.of(installedPlugin("acme-widget", "1.0.0")));

        var result = service.installFromUpload(jarFile(), 7L, false);

        assertEquals(new InstallResult.VersionConflict("upgrade", "1.0.0", "1.1.0"), result);
        verify(loader, never()).install(any(), any());
    }

    @Test
    void installFromUploadReportsADowngradeConflictWhenTheNewVersionIsLower() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(manifest("acme-widget", "0.9.0"));
        when(repo.findByPluginId("acme-widget")).thenReturn(Optional.of(installedPlugin("acme-widget", "1.0.0")));

        var result = service.installFromUpload(jarFile(), 7L, false);

        assertEquals(new InstallResult.VersionConflict("downgrade", "1.0.0", "0.9.0"), result);
        verify(loader, never()).install(any(), any());
    }

    @Test
    void installFromUploadWithReplaceTrueSwapsThePreviousVersion() throws Exception {
        when(loader.readManifest(any(File.class))).thenReturn(manifest("acme-widget", "1.1.0"));
        Plugin existing = installedPlugin("acme-widget", "1.0.0");
        when(repo.findByPluginId("acme-widget")).thenReturn(Optional.of(existing));

        var result = service.installFromUpload(jarFile(), 7L, true);

        assertInstanceOf(InstallResult.Installed.class, result);
        assertEquals("1.1.0", ((InstallResult.Installed) result).plugin().version());
        // The new JAR must be proven loadable before the old registration is touched — see
        // PluginLoader#install's own doc comment — so this is the swap-capable method, not load().
        verify(loader).install(argThat(m -> m.id().equals("acme-widget")), any(File.class));
        verify(repo).delete(existing);
    }

    @Test
    void setEnabledTrueLoadsThePluginWhenTransitioningFromDisabled() throws Exception {
        Plugin p = new Plugin();
        p.setPluginId("acme-widget");
        p.setFilename("acme-widget-1.0.0.jar");
        p.setEnabled(false);
        when(repo.findById(1L)).thenReturn(Optional.of(p));
        when(loader.readManifest(any(File.class))).thenReturn(manifest("acme-widget", "1.0.0"));
        java.nio.file.Files.write(pluginsDir.resolve(p.getFilename()), new byte[]{0});

        service.setEnabled(1L, true);

        verify(loader).load(argThat(m -> m.id().equals("acme-widget")), any(File.class));
        verify(loader, never()).unload(any());
        assertTrue(p.isEnabled());
    }

    @Test
    void setEnabledFalseUnloadsThePluginWhenTransitioningFromEnabled() {
        Plugin p = new Plugin();
        p.setPluginId("acme-widget");
        p.setFilename("acme-widget-1.0.0.jar");
        p.setEnabled(true);
        when(repo.findById(1L)).thenReturn(Optional.of(p));

        service.setEnabled(1L, false);

        verify(loader).unload("acme-widget");
        verify(loader, never()).load(any(), any());
        assertFalse(p.isEnabled());
    }

    @Test
    void setEnabledIsANoOpWhenAlreadyInTheRequestedState() {
        Plugin p = new Plugin();
        p.setPluginId("acme-widget");
        p.setEnabled(true);
        when(repo.findById(1L)).thenReturn(Optional.of(p));

        service.setEnabled(1L, true);

        verifyNoInteractions(loader);
        verify(repo, never()).save(any());
    }

    @Test
    void uninstallForgetsTheLoaderAndDeletesTheRowImmediately() {
        Plugin p = new Plugin();
        p.setPluginId("acme-widget");
        p.setFilename("acme-widget-1.0.0.jar");
        p.setEnabled(true);
        when(repo.findById(1L)).thenReturn(Optional.of(p));

        service.uninstall(1L);

        verify(loader).forget("acme-widget");
        verify(repo).delete(p);
        // No restart needed — see PluginLoader's own doc comment on why deleting a still-open
        // JAR file is safe. Nothing should still be marking this row for a later boot-time purge.
        verify(repo, never()).save(any());
    }

    @Test
    void uninstallIsBlockedWhileAnotherInstalledPluginDependsOnIt() {
        Plugin base = new Plugin();
        base.setPluginId("bughunting");
        base.setFilename("bughunting-1.0.0.jar");
        base.setEnabled(true);
        when(repo.findById(1L)).thenReturn(Optional.of(base));
        Plugin dependent = new Plugin();
        dependent.setPluginId("hackerone");
        dependent.setDependsOn(List.of(dependency("bughunting")));
        when(repo.findAll()).thenReturn(List.of(base, dependent));

        var ex = assertThrows(ResponseStatusException.class, () -> service.uninstall(1L));
        assertTrue(ex.getReason().contains("hackerone"));
        verify(loader, never()).forget(any());
        verify(repo, never()).delete(any());
    }

    @Test
    void setEnabledFalseIsBlockedWhileAnotherInstalledPluginDependsOnIt() {
        Plugin base = new Plugin();
        base.setPluginId("bughunting");
        base.setFilename("bughunting-1.0.0.jar");
        base.setEnabled(true);
        when(repo.findById(1L)).thenReturn(Optional.of(base));
        Plugin dependent = new Plugin();
        dependent.setPluginId("hackerone");
        dependent.setDependsOn(List.of(dependency("bughunting")));
        when(repo.findAll()).thenReturn(List.of(base, dependent));

        var ex = assertThrows(ResponseStatusException.class, () -> service.setEnabled(1L, false));
        assertTrue(ex.getReason().contains("hackerone"));
        verify(loader, never()).unload(any());
        assertTrue(base.isEnabled());
    }

    @Test
    void loadInstalledPluginsLoadsEveryEnabledRowAndSkipsDisabledOnes() throws Exception {
        Plugin toLoad = new Plugin();
        toLoad.setPluginId("acme-widget");
        toLoad.setFilename("acme-widget-1.0.0.jar");
        toLoad.setEnabled(true);
        Plugin disabled = new Plugin();
        disabled.setPluginId("acme-other");
        disabled.setEnabled(false);

        when(repo.findAllByOrderByInstalledAtAsc()).thenReturn(List.of(toLoad, disabled));
        when(loader.readManifest(any(File.class))).thenReturn(manifest("acme-widget", "1.0.0"));
        java.nio.file.Files.write(pluginsDir.resolve(toLoad.getFilename()), new byte[]{0});

        service.loadInstalledPlugins();

        verify(loader, times(1)).load(any(), any());
        verify(loader).load(argThat(m -> m.id().equals("acme-widget")), any(File.class));
    }

    @Test
    void loadInstalledPluginsLoadsADependencyBeforeItsDependent() throws Exception {
        Plugin base = new Plugin();
        base.setPluginId("base");
        base.setFilename("base-1.0.0.jar");
        base.setEnabled(true);
        Plugin dependent = new Plugin();
        dependent.setPluginId("dependent");
        dependent.setFilename("dependent-1.0.0.jar");
        dependent.setEnabled(true);
        dependent.setDependsOn(List.of(dependency("base")));

        // Deliberately returned in dependent-before-base DB order — the topological sort must
        // still load "base" first regardless of installedAt ordering.
        when(repo.findAllByOrderByInstalledAtAsc()).thenReturn(List.of(dependent, base));
        when(loader.readManifest(any(File.class))).thenAnswer(inv -> {
            File f = inv.getArgument(0);
            String id = f.getName().contains("dependent") ? "dependent" : "base";
            List<PluginManifest.PluginDependency> deps = id.equals("dependent") ? List.of(dependency("base")) : List.of();
            return new PluginManifest(id, "1.0.0", "Test", "Acme", "MIT", null, "1", null, null, deps, List.of(), List.of(), null, null, null, null);
        });

        List<String> loadOrder = new java.util.ArrayList<>();
        doAnswer(inv -> { loadOrder.add(((PluginManifest) inv.getArgument(0)).id()); return null; })
            .when(loader).load(any(), any());
        java.nio.file.Files.write(pluginsDir.resolve(base.getFilename()), new byte[]{0});
        java.nio.file.Files.write(pluginsDir.resolve(dependent.getFilename()), new byte[]{0});

        service.loadInstalledPlugins();

        assertEquals(List.of("base", "dependent"), loadOrder);
    }
}
