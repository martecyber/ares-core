package com.martecyber.ares.findings.templates;

import com.martecyber.ares.common.DuplicateNameException;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingFieldTypeRepository;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingScoreRepository;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.references.ReferenceEntryRepository;
import com.martecyber.ares.tags.TagRepository;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Covers the "save as template" duplicate-name behavior added to createFromFinding — repeatedly
 *  saving edits of the same finding under an existing template's exact title must ask before
 *  overwriting rather than silently piling up near-duplicate templates (see
 *  ares-ui's useSaveAsTemplate for the confirm-and-retry flow this exception drives). */
class FindingTemplateServiceTest {

    private FindingTemplateRepository repo;
    private FindingTemplateFieldRepository fieldRepo;
    private FindingTemplateScoreRepository scoreRepo;
    private ReferenceEntryRepository referenceRepo;
    private ReferenceCatalogRepository catalogRepo;
    private FindingRepository findingRepo;
    private FindingScoreRepository findingScoreRepo;
    private FindingFieldTypeRepository fieldTypeRepo;
    private FindingTemplateAqlRegistry aqlRegistry;
    private WorkflowEventDispatcher workflowEventDispatcher;
    private FindingTemplateTagRepository tagRepo;
    private TagRepository tagCatalogRepo;
    private FindingTemplateService service;

    @BeforeEach
    void setUp() {
        repo = mock(FindingTemplateRepository.class);
        fieldRepo = mock(FindingTemplateFieldRepository.class);
        scoreRepo = mock(FindingTemplateScoreRepository.class);
        referenceRepo = mock(ReferenceEntryRepository.class);
        catalogRepo = mock(ReferenceCatalogRepository.class);
        findingRepo = mock(FindingRepository.class);
        findingScoreRepo = mock(FindingScoreRepository.class);
        fieldTypeRepo = mock(FindingFieldTypeRepository.class);
        aqlRegistry = mock(FindingTemplateAqlRegistry.class);
        workflowEventDispatcher = mock(WorkflowEventDispatcher.class);
        tagRepo = mock(FindingTemplateTagRepository.class);
        tagCatalogRepo = mock(TagRepository.class);
        service = new FindingTemplateService(repo, fieldRepo, scoreRepo, referenceRepo, catalogRepo,
            findingRepo, findingScoreRepo, fieldTypeRepo, aqlRegistry, workflowEventDispatcher,
            tagRepo, tagCatalogRepo);
    }

    @Test
    void createFromFindingThrowsDuplicateNameExceptionWhenTitleExistsAndNotOverwrite() {
        when(findingRepo.findById(anyLong())).thenReturn(Optional.of(new Finding()));
        when(repo.findByTitleIgnoreCase("Reused title")).thenReturn(Optional.of(new FindingTemplate()));

        var req = new FindingTemplateService.FromFindingRequest("Reused title");
        var ex = assertThrows(DuplicateNameException.class,
            () -> service.createFromFinding(1L, req, false));
        assertEquals("A finding template named \"Reused title\" already exists", ex.getMessage());

        // Nothing was written — the duplicate is rejected before any population/save happens.
        verify(repo, never()).save(any());
        verify(fieldRepo, never()).save(any());
        verify(scoreRepo, never()).save(any());
    }

    @Test
    void createFromFindingClearsExistingFieldsAndScoresBeforeRepopulatingWhenOverwriting() {
        Finding finding = new Finding();
        when(findingRepo.findById(anyLong())).thenReturn(Optional.of(finding));
        FindingTemplate existing = new FindingTemplate();
        ReflectionTestUtils.setField(existing, "id", 42L);
        when(repo.findByTitleIgnoreCase("Reused title")).thenReturn(Optional.of(existing));
        when(repo.findById(42L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fieldTypeRepo.findAllByOrderBySortOrderAscTitleAsc()).thenReturn(java.util.List.of());
        when(findingScoreRepo.findByFindingId(anyLong())).thenReturn(java.util.List.of());
        when(referenceRepo.findByFindingId(anyLong())).thenReturn(java.util.List.of());
        when(fieldRepo.findByTemplateId(any())).thenReturn(java.util.List.of());
        when(scoreRepo.findByTemplateId(any())).thenReturn(java.util.List.of());
        when(referenceRepo.findByTemplateId(any())).thenReturn(java.util.List.of());
        when(tagRepo.findTagsForTemplateIds(any())).thenReturn(java.util.List.of());

        var req = new FindingTemplateService.FromFindingRequest("Reused title");
        service.createFromFinding(1L, req, true);

        verify(fieldRepo).deleteByTemplateId(existing.getId());
        verify(scoreRepo).deleteByTemplateId(existing.getId());
        verify(repo).save(existing); // same row updated in place, not a new one
    }
}
