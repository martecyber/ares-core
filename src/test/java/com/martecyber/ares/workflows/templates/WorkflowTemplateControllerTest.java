package com.martecyber.ares.workflows.templates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test — mirrors {@code WebhookControllerTest}'s no-Spring-context style;
 *  the controller is a thin pass-through, all real behavior lives in {@link WorkflowTemplateService}
 *  and is covered there by {@link WorkflowTemplateServiceIT}. */
class WorkflowTemplateControllerTest {

    private WorkflowTemplateService service;
    private WorkflowTemplateController controller;

    @BeforeEach
    void setUp() {
        service = mock(WorkflowTemplateService.class);
        controller = new WorkflowTemplateController(service);
    }

    private static WorkflowTemplateDto dto(long id, String name) {
        return new WorkflowTemplateDto(id, name, null, "platform", "{\"nodes\":[],\"edges\":[]}", 1L,
            OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void listDelegatesToService() {
        when(service.list(0, 50)).thenReturn(new PageImpl<>(List.of(dto(1, "a")), PageRequest.of(0, 50), 1));
        var page = controller.list(0, 50);
        assertEquals(1, page.total());
    }

    @Test
    void getDelegatesToService() {
        when(service.get(5L)).thenReturn(dto(5, "found"));
        assertEquals("found", controller.get(5L).name());
    }

    @Test
    void createReturns201WithLocationHeader() {
        var req = new WorkflowTemplateService.CreateOrUpdateRequest("New", null, "platform", "{\"nodes\":[],\"edges\":[]}");
        when(service.create(req, false)).thenReturn(dto(7, "New"));

        var response = controller.create(false, req);
        assertEquals(201, response.getStatusCode().value());
        assertEquals("/api/v1/workflow-templates/7", response.getHeaders().getLocation().toString());
    }

    @Test
    void updateDelegatesToService() {
        var req = new WorkflowTemplateService.CreateOrUpdateRequest("Renamed", null, "platform", "{\"nodes\":[],\"edges\":[]}");
        when(service.update(3L, req)).thenReturn(dto(3, "Renamed"));
        assertEquals("Renamed", controller.update(3L, req).name());
    }

    @Test
    void fromWorkflowReturns201() {
        var req = new WorkflowTemplateService.FromWorkflowRequest(null, null, null);
        when(service.createFromWorkflow(9L, req, false)).thenReturn(dto(11, "From workflow"));

        var response = controller.fromWorkflow(9L, false, req);
        assertEquals(201, response.getStatusCode().value());
        assertEquals("From workflow", response.getBody().name());
    }

    @Test
    void deleteReturns204() {
        var response = controller.delete(4L);
        assertEquals(204, response.getStatusCode().value());
        verify(service).delete(4L);
    }

    @Test
    void exportOneSetsContentDispositionHeader() {
        var req = new WorkflowTemplateService.CreateOrUpdateRequest("Exp", null, "platform", "{\"nodes\":[],\"edges\":[]}");
        when(service.exportOne(2L)).thenReturn(req);

        var response = controller.exportOne(2L);
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("workflow-template-2.json"));
        assertEquals("Exp", response.getBody().name());
    }

    @Test
    void exportZipDelegatesToService() {
        when(service.exportZip(List.of(1L, 2L))).thenReturn(new byte[]{1, 2, 3});
        var response = controller.exportZip(List.of(1L, 2L));
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
    }

    @Test
    void importFilesDelegatesToService() {
        List<MultipartFile> files = List.of(new MockMultipartFile("files", "t.json", "application/json", "{}".getBytes()));
        when(service.importFiles(files)).thenReturn(new WorkflowTemplateImportResult(1, List.of()));
        var result = controller.importFiles(files);
        assertEquals(1, result.imported());
    }
}
