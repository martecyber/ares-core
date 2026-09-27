package com.martecyber.ares.files;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.files.dto.FileMetadataDto;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService svc;

    public FileController(FileService svc) { this.svc = svc; }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<FileMetadataDto> list(
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) Long findingId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(svc.list(organizationId, projectId, findingId, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FileMetadataDto get(@PathVariable Long id) { return svc.getMetadata(id); }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FileMetadataDto upload(
        @RequestPart("file") MultipartFile file,
        @RequestParam Long organizationId,
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) Long findingId
    ) throws IOException {
        return svc.upload(file, organizationId, projectId, findingId);
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<byte[]> download(@PathVariable Long id) throws IOException {
        FileMetadataDto meta = svc.getMetadata(id);
        byte[] data = svc.download(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(meta.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment().filename(meta.originalName()).build());
        headers.setContentLength(data.length);
        return ResponseEntity.ok().headers(headers).body(data);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void delete(@PathVariable Long id) throws IOException { svc.delete(id); }
}
