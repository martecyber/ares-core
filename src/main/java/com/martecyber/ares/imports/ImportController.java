package com.martecyber.ares.imports;

import com.martecyber.ares.common.IpValidator;
import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.imports.dto.ScanImportDto;
import com.martecyber.ares.imports.dto.ScanImportRollbackResult;
import com.martecyber.ares.users.OrgScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{engId}/imports")
public class ImportController {

    private final ImportService svc;
    private final ScanImportRollbackService rollbackSvc;
    private final OrgScopeService orgScope;

    public ImportController(ImportService svc, ScanImportRollbackService rollbackSvc, OrgScopeService orgScope) {
        this.svc = svc;
        this.rollbackSvc = rollbackSvc;
        this.orgScope = orgScope;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<ScanImportDto> list(@PathVariable Long engId, Authentication auth,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        orgScope.assertProjectAccess(auth, engId);
        return svc.listForProject(engId, page, size);
    }

    @GetMapping("/tools")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<Map<String, Object>> tools() {
        return svc.listAvailableTools();
    }

    /**
     * Returns as soon as the {@code ScanImport} row exists (status {@code "running"}) — the
     * actual parsing/persistence happens in the background (see {@link ImportService#startImport}
     * for why: a large result set can take minutes, well past any reasonable client timeout).
     * Callers track progress the same way they already do for any import — poll {@link #list}
     * (or the single row once it lands there) for {@code status} flipping to {@code
     * "completed"}/{@code "failed"}.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ScanImportDto run(@PathVariable Long engId,
                            @RequestParam String tool,
                            @RequestParam(required = false, defaultValue = "default") String format,
                            @RequestParam(value = "sourceIp",   required = false) String sourceIp,
                            @RequestParam(value = "nacProfile", required = false) String nacProfile,
                            @RequestParam MultipartFile file,
                            Authentication auth) throws Exception {
        orgScope.assertProjectAccess(auth, engId);
        boolean hasSourceIp   = sourceIp   != null && !sourceIp.isBlank();
        boolean hasNacProfile = nacProfile != null && !nacProfile.isBlank();

        if (hasSourceIp && !IpValidator.isValidLiteral(sourceIp)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid sourceIp — must be an IPv4 or IPv6 literal");
        }
        // NAC profile only makes sense alongside a source IP — it's a qualifier of that vantage point.
        if (hasNacProfile && !hasSourceIp) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "nacProfile requires sourceIp to be set");
        }
        if (hasNacProfile && nacProfile.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "nacProfile must be 64 characters or fewer");
        }
        byte[] content = file.getBytes();
        ScanImport record = svc.startImport(engId, tool, format, file.getOriginalFilename(), content,
            sourceIp, nacProfile, false, null);
        return ScanImportDto.from(record, false);
    }

    /** Single-row counterpart to {@link #list} — lets a client poll one import's status (e.g.
     *  right after {@link #run}) without paging through the whole list. */
    @GetMapping("/{importId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ScanImportDto get(@PathVariable Long engId, @PathVariable Long importId, Authentication auth) {
        orgScope.assertProjectAccess(auth, engId);
        return svc.getForProject(engId, importId);
    }

    @GetMapping("/{importId}/rollback-preview")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ScanImportRollbackResult rollbackPreview(@PathVariable Long engId, @PathVariable Long importId, Authentication auth) {
        orgScope.assertProjectAccess(auth, engId);
        return rollbackSvc.preview(engId, importId);
    }

    @PostMapping("/{importId}/rollback")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ScanImportRollbackResult rollback(@PathVariable Long engId, @PathVariable Long importId, Authentication auth) {
        orgScope.assertProjectAccess(auth, engId);
        return rollbackSvc.rollback(engId, importId);
    }
}
