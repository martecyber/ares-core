package com.martecyber.ares.references;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.templates.FindingTemplate;
import com.martecyber.ares.findings.templates.FindingTemplateRepository;
import com.martecyber.ares.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test for {@link ReferenceService}. {@code findOrCreateUrlEntry}'s full
 *  success path is deliberately NOT covered here — {@link UrlMetadataFetcher#validate} does a
 *  real {@code InetAddress.getAllByName} SSRF check as a static call the service can't have
 *  injected/mocked, so exercising it end-to-end would make this test depend on live DNS/network
 *  reachability. Only the pre-DNS validation failures (blank/malformed/wrong-scheme/no-host) are
 *  covered — they throw before any network call happens. */
class ReferenceServiceTest {

    private ReferenceCatalogRepository catalogRepo;
    private ReferenceEntryRepository entryRepo;
    private FindingRepository findingRepo;
    private DetectionRepository detectionRepo;
    private FindingTemplateRepository templateRepo;
    private UrlMetadataFetcher urlMetadataFetcher;
    private StorageService storage;
    private ReferenceService service;

    @BeforeEach
    void setUp() {
        catalogRepo = mock(ReferenceCatalogRepository.class);
        entryRepo = mock(ReferenceEntryRepository.class);
        findingRepo = mock(FindingRepository.class);
        detectionRepo = mock(DetectionRepository.class);
        templateRepo = mock(FindingTemplateRepository.class);
        urlMetadataFetcher = mock(UrlMetadataFetcher.class);
        storage = mock(StorageService.class);
        service = new ReferenceService(catalogRepo, entryRepo, findingRepo, detectionRepo, templateRepo, urlMetadataFetcher, storage);
        ReflectionTestUtils.setField(service, "faviconBucket", "ares-favicons");
        when(entryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(catalogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ReferenceEntry entry(Long id) {
        ReferenceEntry e = new ReferenceEntry();
        ReflectionTestUtils.setField(e, "id", id);
        when(entryRepo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    // ── Catalogs ─────────────────────────────────────────────────────

    @Test
    void getCatalogThrowsNotFoundForAnUnknownId() {
        when(catalogRepo.findById(1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.getCatalog(1L));
    }

    @Test
    void createCatalogPersistsTheGivenFields() {
        ReferenceCatalog c = service.createCatalog("CWE", "Common Weakness Enumeration", "{}");
        assertEquals("CWE", c.getCode());
        assertEquals("Common Weakness Enumeration", c.getTitle());
    }

    @Test
    void deleteCatalogThrowsNotFoundWhenMissing() {
        when(catalogRepo.existsById(1L)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> service.deleteCatalog(1L));
        verify(catalogRepo, never()).deleteById(any());
    }

    @Test
    void deleteCatalogRemovesAnExistingOne() {
        when(catalogRepo.existsById(1L)).thenReturn(true);
        service.deleteCatalog(1L);
        verify(catalogRepo).deleteById(1L);
    }

    // ── Entries ──────────────────────────────────────────────────────

    @Test
    void listEntriesSearchesByTitleWhenASearchTermIsGiven() {
        when(entryRepo.findByCatalogIdAndTitleContainingIgnoreCase(eq(1L), eq("xss"), any()))
            .thenReturn(new PageImpl<>(java.util.List.of()));
        Page<ReferenceEntry> page = service.listEntries(1L, "  xss  ", 0, 20);
        verify(entryRepo).findByCatalogIdAndTitleContainingIgnoreCase(eq(1L), eq("xss"), any());
        verify(entryRepo, never()).findByCatalogId(any(), any());
    }

    @Test
    void listEntriesFallsBackToUnfilteredListingWhenSearchIsBlank() {
        when(entryRepo.findByCatalogId(eq(1L), any())).thenReturn(new PageImpl<>(java.util.List.of()));
        service.listEntries(1L, "   ", 0, 20);
        verify(entryRepo).findByCatalogId(eq(1L), any());
    }

    @Test
    void createEntryThrowsNotFoundWhenTheCatalogDoesNotExist() {
        when(catalogRepo.existsById(1L)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> service.createEntry(1L, "Title", "Desc"));
    }

    @Test
    void findOrCreateEntryReturnsTheExistingRowWithoutCreatingADuplicate() {
        ReferenceEntry existing = entry(5L);
        when(entryRepo.findByCatalogIdAndTitle(1L, "Reflected XSS")).thenReturn(Optional.of(existing));

        ReferenceEntry result = service.findOrCreateEntry(1L, "Reflected XSS", "desc");

        assertSame(existing, result);
        verify(catalogRepo, never()).existsById(any());
    }

    @Test
    void findOrCreateEntryCreatesANewOneWhenNoneMatches() {
        when(entryRepo.findByCatalogIdAndTitle(1L, "New title")).thenReturn(Optional.empty());
        when(catalogRepo.existsById(1L)).thenReturn(true);

        ReferenceEntry result = service.findOrCreateEntry(1L, "New title", "desc");

        assertEquals("New title", result.getTitle());
        verify(entryRepo).save(any());
    }

    @Test
    void deleteEntryThrowsNotFoundWhenMissing() {
        when(entryRepo.existsById(1L)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> service.deleteEntry(1L));
    }

    // ── Finding / Detection / FindingTemplate links ─────────────────

    @Test
    void addFindingLinksAnExistingFindingToTheEntry() {
        ReferenceEntry e = entry(1L);
        Finding f = new Finding();
        ReflectionTestUtils.setField(f, "id", 10L);
        when(findingRepo.findById(10L)).thenReturn(Optional.of(f));

        service.addFinding(1L, 10L);

        assertTrue(e.getFindings().contains(f));
    }

    @Test
    void addFindingThrowsNotFoundForAnUnknownFinding() {
        entry(1L);
        when(findingRepo.findById(10L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.addFinding(1L, 10L));
    }

    @Test
    void removeFindingRemovesOnlyTheMatchingFinding() {
        ReferenceEntry e = entry(1L);
        Finding kept = new Finding();
        ReflectionTestUtils.setField(kept, "id", 1L);
        Finding removed = new Finding();
        ReflectionTestUtils.setField(removed, "id", 2L);
        e.getFindings().add(kept);
        e.getFindings().add(removed);

        service.removeFinding(1L, 2L);

        assertEquals(1, e.getFindings().size());
        assertTrue(e.getFindings().contains(kept));
    }

    @Test
    void addDetectionLinksAnExistingDetectionToTheEntry() {
        ReferenceEntry e = entry(1L);
        Detection d = new Detection();
        ReflectionTestUtils.setField(d, "id", 20L);
        when(detectionRepo.findById(20L)).thenReturn(Optional.of(d));

        service.addDetection(1L, 20L);

        assertTrue(e.getDetections().contains(d));
    }

    @Test
    void removeDetectionRemovesOnlyTheMatchingDetection() {
        ReferenceEntry e = entry(1L);
        Detection kept = new Detection();
        ReflectionTestUtils.setField(kept, "id", 1L);
        Detection removed = new Detection();
        ReflectionTestUtils.setField(removed, "id", 2L);
        e.getDetections().add(kept);
        e.getDetections().add(removed);

        service.removeDetection(1L, 2L);

        assertEquals(1, e.getDetections().size());
    }

    @Test
    void addFindingTemplateThrowsNotFoundForAnUnknownTemplate() {
        entry(1L);
        when(templateRepo.findById(30L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.addFindingTemplate(1L, 30L));
    }

    @Test
    void removeFindingTemplateRemovesOnlyTheMatchingTemplate() {
        ReferenceEntry e = entry(1L);
        FindingTemplate kept = new FindingTemplate();
        ReflectionTestUtils.setField(kept, "id", 1L);
        FindingTemplate removed = new FindingTemplate();
        ReflectionTestUtils.setField(removed, "id", 2L);
        e.getFindingTemplates().add(kept);
        e.getFindingTemplates().add(removed);

        service.removeFindingTemplate(1L, 2L);

        assertEquals(1, e.getFindingTemplates().size());
    }

    // ── findOrCreateUrlEntry: pre-DNS validation only ────────────────

    @Test
    void findOrCreateUrlEntryRejectsABlankUrl() {
        assertThrows(IllegalArgumentException.class, () -> service.findOrCreateUrlEntry("  ", null));
    }

    @Test
    void findOrCreateUrlEntryRejectsAMalformedUrl() {
        assertThrows(IllegalArgumentException.class, () -> service.findOrCreateUrlEntry("http://[bad", null));
    }

    @Test
    void findOrCreateUrlEntryRejectsANonHttpScheme() {
        assertThrows(IllegalArgumentException.class, () -> service.findOrCreateUrlEntry("ftp://example.com/file", null));
    }

    @Test
    void findOrCreateUrlEntryRejectsAUrlWithoutAHost() {
        assertThrows(IllegalArgumentException.class, () -> service.findOrCreateUrlEntry("http:///no-host", null));
    }

    // ── Favicon ──────────────────────────────────────────────────────

    @Test
    void getFaviconReturnsNullWhenTheEntryHasNoStoredFavicon() {
        ReferenceEntry e = entry(1L);
        assertNull(service.getFavicon(1L));
        verify(storage, never()).get(any(), any());
    }

    @Test
    void getFaviconFetchesFromStorageWhenStored() {
        ReferenceEntry e = entry(1L);
        e.setFaviconBucket("ares-favicons");
        e.setFaviconObjectKey("reference-entry/1/abc");
        e.setFaviconContentType("image/png");

        byte[] bytes = {1, 2, 3};
        when(storage.get("ares-favicons", "reference-entry/1/abc")).thenReturn(bytes);

        var favicon = service.getFavicon(1L);
        assertArrayEquals(bytes, favicon.bytes());
        assertEquals("image/png", favicon.contentType());
    }

    // ── Favicon bucket bootstrap ──────────────────────────────────────
    // The actual head/create-retry logic lives in S3StorageService now (see
    // S3StorageServiceTest) — this only needs to confirm the delegation happens.

    @Test
    void ensureFaviconBucketExistsDelegatesToStorageService() {
        service.ensureFaviconBucketExists();
        verify(storage).ensureBucketExists("ares-favicons");
    }
}
