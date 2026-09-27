package com.martecyber.ares.tags;

import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.tags.dto.UpsertTagRequest;
import com.martecyber.ares.users.OrgScopeService;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TagService {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final TagRepository repo;
    private final OrganizationRepository orgRepo;
    private final OrgScopeService orgScope;

    public TagService(TagRepository repo, OrganizationRepository orgRepo, OrgScopeService orgScope) {
        this.repo = repo;
        this.orgRepo = orgRepo;
        this.orgScope = orgScope;
    }

    public List<TagDto> list(Long organizationId) {
        orgScope.assertOrgAccess(SecurityContextHolder.getContext().getAuthentication(), organizationId);
        return repo.findVisibleToOrganization(organizationId).stream().map(TagDto::from).toList();
    }

    /** Platform tags only — the catalog Exploit/FindingTemplate (no organization of their own)
     *  draw from, and the "Platform tags" tab of the admin tag manager. Readable by any operator
     *  (mutation is admin-only, see {@link #requirePlatformAdmin}). */
    public List<TagDto> listPlatform() {
        return repo.findByOrganizationIdIsNullOrderByNameAsc().stream().map(TagDto::from).toList();
    }

    @Transactional
    public TagDto create(Long organizationId, UpsertTagRequest req) {
        orgScope.assertOrgAccess(SecurityContextHolder.getContext().getAuthentication(), organizationId);
        if (!orgRepo.existsById(organizationId)) throw NotFoundException.of("organization", organizationId);
        String name = validateName(req.name());
        String color = validateColor(req.color());
        if (repo.existsByOrganizationIdAndNameIgnoreCase(organizationId, name)) {
            throw new ConflictException("A tag named '" + name + "' already exists in this organization");
        }
        OffsetDateTime now = OffsetDateTime.now();
        Tag t = new Tag();
        t.setOrganizationId(organizationId);
        t.setName(name);
        t.setColor(color);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return TagDto.from(repo.save(t));
    }

    @Transactional
    public TagDto createPlatform(UpsertTagRequest req) {
        requirePlatformAdmin();
        String name = validateName(req.name());
        String color = validateColor(req.color());
        if (repo.existsByOrganizationIdIsNullAndNameIgnoreCase(name)) {
            throw new ConflictException("A platform tag named '" + name + "' already exists");
        }
        OffsetDateTime now = OffsetDateTime.now();
        Tag t = new Tag();
        t.setOrganizationId(null);
        t.setName(name);
        t.setColor(color);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return TagDto.from(repo.save(t));
    }

    @Transactional
    public TagDto update(Long id, UpsertTagRequest req) {
        Tag t = repo.findById(id).orElseThrow(() -> NotFoundException.of("tag", id));
        boolean isPlatformTag = t.getOrganizationId() == null;
        if (isPlatformTag) {
            requirePlatformAdmin();
        } else {
            orgScope.assertOrgAccess(SecurityContextHolder.getContext().getAuthentication(), t.getOrganizationId());
        }
        if (req.name() != null) {
            String name = validateName(req.name());
            boolean duplicate = isPlatformTag
                ? repo.existsByOrganizationIdIsNullAndNameIgnoreCaseAndIdNot(name, id)
                : repo.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(t.getOrganizationId(), name, id);
            if (duplicate) {
                throw new ConflictException(isPlatformTag
                    ? "A platform tag named '" + name + "' already exists"
                    : "A tag named '" + name + "' already exists in this organization");
            }
            t.setName(name);
        }
        if (req.color() != null) t.setColor(validateColor(req.color()));
        t.setUpdatedAt(OffsetDateTime.now());
        return TagDto.from(repo.save(t));
    }

    @Transactional
    public void delete(Long id) {
        Tag t = repo.findById(id).orElseThrow(() -> NotFoundException.of("tag", id));
        if (t.getOrganizationId() == null) {
            requirePlatformAdmin();
        } else {
            orgScope.assertOrgAccess(SecurityContextHolder.getContext().getAuthentication(), t.getOrganizationId());
        }
        repo.deleteById(id);
    }

    private void requirePlatformAdmin() {
        if (!orgScope.isPlatformAdmin(SecurityContextHolder.getContext().getAuthentication())) {
            throw new AccessDeniedException("Only platform admins can manage platform tags");
        }
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must be 50 characters or fewer");
        }
        return trimmed;
    }

    private static String validateColor(String color) {
        if (color == null || !HEX_COLOR.matcher(color.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "color must be a hex value like #4F46E5");
        }
        return color.trim().toUpperCase();
    }
}
