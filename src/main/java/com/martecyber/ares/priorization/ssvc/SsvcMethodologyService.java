package com.martecyber.ares.priorization.ssvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.findings.FindingScoreRepository;
import com.martecyber.ares.findings.templates.FindingTemplateScoreRepository;
import com.martecyber.ares.priorization.ssvc.dto.SsvcMethodologyDto;
import com.martecyber.ares.priorization.ssvc.dto.SsvcMethodologyExportDto;
import com.martecyber.ares.priorization.ssvc.dto.SsvcOutcomeDto;
import com.martecyber.ares.priorization.ssvc.dto.SsvcRoleDetailDto;
import com.martecyber.ares.priorization.ssvc.dto.SsvcTreeNodeDto;
import com.martecyber.ares.priorization.ssvc.dto.SsvcRequests.*;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SsvcMethodologyService {

    private static final Set<String> VALID_PRIORITY_LEVELS = Set.of("P0", "P1", "P2", "P3", "P4");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SsvcMethodologyRepository methodologyRepo;
    private final SsvcRoleRepository roleRepo;
    private final SsvcTreeNodeRepository nodeRepo;
    private final SsvcTreeNodeOptionRepository optionRepo;
    private final FindingScoreRepository findingScoreRepo;
    private final FindingTemplateScoreRepository templateScoreRepo;

    public SsvcMethodologyService(SsvcMethodologyRepository methodologyRepo,
                                   SsvcRoleRepository roleRepo,
                                   SsvcTreeNodeRepository nodeRepo,
                                   SsvcTreeNodeOptionRepository optionRepo,
                                   FindingScoreRepository findingScoreRepo,
                                   FindingTemplateScoreRepository templateScoreRepo) {
        this.methodologyRepo = methodologyRepo;
        this.roleRepo = roleRepo;
        this.nodeRepo = nodeRepo;
        this.optionRepo = optionRepo;
        this.findingScoreRepo = findingScoreRepo;
        this.templateScoreRepo = templateScoreRepo;
    }

    // ── Reads ─────────────────────────────────────────────────────────────────────

    public List<SsvcMethodologyDto> list() {
        return methodologyRepo.findAllOrdered().stream().map(this::toSummaryDto).toList();
    }

    public SsvcMethodologyDto getDetail(Long id) {
        SsvcMethodology m = methodologyRepo.findById(id).orElseThrow(() -> NotFoundException.of("ssvc_methodology", id));
        List<SsvcMethodologyDto.SsvcRoleSummaryDto> roles = roleRepo.findByMethodologyId(m.getId()).stream()
            .map(this::toRoleSummaryDto).toList();
        return new SsvcMethodologyDto(m.getId(), m.getCode(), m.getName(), m.getDescription(), m.isSystem(), roles);
    }

    /** Resolves which role a given tree node (typically a leaf a score points to) belongs
     *  to — used by the frontend to restore the score editor's methodology+role selection
     *  and decision-tree wizard path when editing an existing SSVC score. */
    public Long getRoleIdForNode(Long nodeId) {
        SsvcTreeNode n = nodeRepo.findById(nodeId).orElseThrow(() -> NotFoundException.of("ssvc_tree_node", nodeId));
        return n.getRoleId();
    }

    public SsvcRoleDetailDto getRoleDetail(Long roleId) {
        SsvcRole r = roleRepo.findById(roleId).orElseThrow(() -> NotFoundException.of("ssvc_role", roleId));
        SsvcMethodology m = methodologyRepo.findById(r.getMethodologyId())
            .orElseThrow(() -> NotFoundException.of("ssvc_methodology", r.getMethodologyId()));
        SsvcTreeNodeDto tree = loadTree(r.getId());
        return new SsvcRoleDetailDto(r.getId(), m.getId(), m.getCode(), m.isSystem(), r.getCode(), r.getName(),
            r.getDescription(), r.isUsesPriorityMapping(), isLocked(r.getId()), tree, parseOutcomes(r.getOutcomes()));
    }

    /** (De)serialization for SsvcRole.outcomes — a JSON array stored directly on the role,
     *  independent of the tree, so an outcome added to the palette survives even before
     *  it's assigned to any leaf. */
    private List<SsvcOutcomeDto> parseOutcomes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.of(MAPPER.readValue(json, SsvcOutcomeDto[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String serializeOutcomes(List<SsvcOutcomeDto> outcomes) {
        try {
            return MAPPER.writeValueAsString(outcomes == null ? List.of() : outcomes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid outcomes payload", e);
        }
    }

    private SsvcMethodologyDto toSummaryDto(SsvcMethodology m) {
        List<SsvcMethodologyDto.SsvcRoleSummaryDto> roles = roleRepo.findByMethodologyId(m.getId()).stream()
            .map(this::toRoleSummaryDto).toList();
        return new SsvcMethodologyDto(m.getId(), m.getCode(), m.getName(), m.getDescription(), m.isSystem(), roles);
    }

    private SsvcMethodologyDto.SsvcRoleSummaryDto toRoleSummaryDto(SsvcRole r) {
        boolean hasTree = nodeRepo.findRootByRoleId(r.getId()).isPresent();
        return new SsvcMethodologyDto.SsvcRoleSummaryDto(r.getId(), r.getCode(), r.getName(), r.getDescription(),
            r.isUsesPriorityMapping(), isLocked(r.getId()), hasTree);
    }

    private boolean isLocked(Long roleId) {
        List<Long> nodeIds = nodeRepo.findByRoleId(roleId).stream().map(SsvcTreeNode::getId).toList();
        if (nodeIds.isEmpty()) return false;
        return findingScoreRepo.existsBySsvcLeafNodeIdIn(nodeIds) || templateScoreRepo.existsBySsvcLeafNodeIdIn(nodeIds);
    }

    /** Loads a role's full tree into a nested DTO. Manual assembly (no @OneToMany):
     *  fetch every node + option for the role in two queries, then walk the in-memory
     *  parent/child links starting at the root. Returns null if the role has no tree yet. */
    private SsvcTreeNodeDto loadTree(Long roleId) {
        List<SsvcTreeNode> nodes = nodeRepo.findByRoleId(roleId);
        if (nodes.isEmpty()) return null;
        List<SsvcTreeNodeOption> options = optionRepo.findByRoleId(roleId);

        Map<Long, List<SsvcTreeNodeOption>> optionsByNodeId = options.stream()
            .collect(Collectors.groupingBy(SsvcTreeNodeOption::getTreeNodeId));
        Map<String, SsvcTreeNode> childByParentAndOption = new HashMap<>();
        SsvcTreeNode root = null;
        for (SsvcTreeNode n : nodes) {
            if (n.getParentNodeId() == null) {
                root = n;
            } else {
                childByParentAndOption.put(n.getParentNodeId() + "|" + n.getParentOptionCode(), n);
            }
        }
        if (root == null) return null;
        return buildNodeDto(root, optionsByNodeId, childByParentAndOption);
    }

    private SsvcTreeNodeDto buildNodeDto(SsvcTreeNode node, Map<Long, List<SsvcTreeNodeOption>> optionsByNodeId,
                                          Map<String, SsvcTreeNode> childByParentAndOption) {
        if (node.isLeaf()) {
            return new SsvcTreeNodeDto(node.getId(), node.getNodeType(), null, null, null, null,
                node.getOutcomeCode(), node.getOutcomeLabel(), node.getOutcomeDescription(), node.getPriorityLevel());
        }
        List<SsvcTreeNodeDto.SsvcTreeNodeOptionDto> optionDtos = optionsByNodeId
            .getOrDefault(node.getId(), List.of()).stream()
            .map(opt -> {
                SsvcTreeNode child = childByParentAndOption.get(node.getId() + "|" + opt.getCode());
                SsvcTreeNodeDto childDto = child != null ? buildNodeDto(child, optionsByNodeId, childByParentAndOption) : null;
                return new SsvcTreeNodeDto.SsvcTreeNodeOptionDto(opt.getId(), opt.getCode(), opt.getLabel(), opt.getHelpText(), childDto);
            }).toList();
        return new SsvcTreeNodeDto(node.getId(), node.getNodeType(), node.getDecisionPointCode(),
            node.getDecisionPointName(), node.getDecisionPointHelp(), optionDtos, null, null, null, null);
    }

    // ── Methodology CRUD ─────────────────────────────────────────────────────────

    @Transactional
    public SsvcMethodologyDto createMethodology(CreateMethodologyRequest req) {
        if (methodologyRepo.findByCode(req.code()).isPresent())
            throw new ConflictException("A methodology with code '" + req.code() + "' already exists");
        OffsetDateTime now = OffsetDateTime.now();
        SsvcMethodology m = new SsvcMethodology();
        m.setCode(req.code());
        m.setName(req.name());
        m.setDescription(req.description());
        m.setSystem(false);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        methodologyRepo.save(m);
        return toSummaryDto(m);
    }

    @Transactional
    public SsvcMethodologyDto updateMethodology(Long id, UpdateMethodologyRequest req) {
        SsvcMethodology m = methodologyRepo.findById(id).orElseThrow(() -> NotFoundException.of("ssvc_methodology", id));
        if (m.isSystem()) throw new ConflictException("System methodologies cannot be edited");
        if (req.name() != null && !req.name().isBlank()) m.setName(req.name().trim());
        if (req.description() != null) m.setDescription(req.description());
        m.setUpdatedAt(OffsetDateTime.now());
        methodologyRepo.save(m);
        return toSummaryDto(m);
    }

    @Transactional
    public void deleteMethodology(Long id) {
        SsvcMethodology m = methodologyRepo.findById(id).orElseThrow(() -> NotFoundException.of("ssvc_methodology", id));
        if (m.isSystem()) throw new ConflictException("System methodologies cannot be deleted");
        for (SsvcRole r : roleRepo.findByMethodologyId(id)) {
            if (isLocked(r.getId()))
                throw new ConflictException("Methodology has scores referencing role '" + r.getCode() + "' and cannot be deleted");
        }
        methodologyRepo.deleteById(id);
    }

    // ── Role CRUD ────────────────────────────────────────────────────────────────

    @Transactional
    public SsvcMethodologyDto.SsvcRoleSummaryDto createRole(Long methodologyId, CreateRoleRequest req) {
        SsvcMethodology m = methodologyRepo.findById(methodologyId)
            .orElseThrow(() -> NotFoundException.of("ssvc_methodology", methodologyId));
        if (m.isSystem()) throw new ConflictException("Cannot add roles to a system methodology");
        boolean codeExists = roleRepo.findByMethodologyId(methodologyId).stream()
            .anyMatch(r -> r.getCode().equals(req.code()));
        if (codeExists) throw new ConflictException("A role with code '" + req.code() + "' already exists on this methodology");

        OffsetDateTime now = OffsetDateTime.now();
        SsvcRole r = new SsvcRole();
        r.setMethodologyId(methodologyId);
        r.setCode(req.code());
        r.setName(req.name());
        r.setDescription(req.description());
        r.setUsesPriorityMapping(req.usesPriorityMapping() == null || req.usesPriorityMapping());
        r.setSortOrder(roleRepo.findByMethodologyId(methodologyId).size());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        roleRepo.save(r);
        return toRoleSummaryDto(r);
    }

    @Transactional
    public SsvcMethodologyDto.SsvcRoleSummaryDto updateRole(Long roleId, UpdateRoleRequest req) {
        SsvcRole r = roleRepo.findById(roleId).orElseThrow(() -> NotFoundException.of("ssvc_role", roleId));
        requireEditableMethodology(r);
        if (req.code() != null && !req.code().isBlank() && !req.code().equals(r.getCode())) {
            boolean codeTaken = roleRepo.findByMethodologyId(r.getMethodologyId()).stream()
                .anyMatch(other -> !other.getId().equals(roleId) && other.getCode().equals(req.code()));
            if (codeTaken) throw new ConflictException("A role with code '" + req.code() + "' already exists on this methodology");
            r.setCode(req.code().trim());
        }
        if (req.name() != null && !req.name().isBlank()) r.setName(req.name().trim());
        if (req.description() != null) r.setDescription(req.description());
        if (req.outcomes() != null) r.setOutcomes(serializeOutcomes(validateOutcomes(req.outcomes(), r.isUsesPriorityMapping())));
        r.setUpdatedAt(OffsetDateTime.now());
        roleRepo.save(r);
        return toRoleSummaryDto(r);
    }

    private List<SsvcOutcomeDto> validateOutcomes(List<SsvcOutcomeDto> outcomes, boolean usesPriorityMapping) {
        Set<String> codes = new HashSet<>();
        for (SsvcOutcomeDto o : outcomes) {
            if (o.code() == null || o.code().isBlank()) throw new IllegalArgumentException("Every outcome needs a key.");
            if (o.label() == null || o.label().isBlank()) throw new IllegalArgumentException("Every outcome needs a value.");
            if (!codes.add(o.code())) throw new ConflictException("Duplicate outcome key '" + o.code() + "'.");
            if (usesPriorityMapping && (o.priorityLevel() == null || !VALID_PRIORITY_LEVELS.contains(o.priorityLevel())))
                throw new IllegalArgumentException("Outcome '" + o.label() + "' needs a priority (P0-P4).");
        }
        return outcomes;
    }

    @Transactional
    public void deleteRole(Long roleId) {
        SsvcRole r = roleRepo.findById(roleId).orElseThrow(() -> NotFoundException.of("ssvc_role", roleId));
        requireEditableMethodology(r);
        if (isLocked(roleId)) throw new ConflictException("Role has scores referencing it and cannot be deleted");
        roleRepo.deleteById(roleId);
    }

    private void requireEditableMethodology(SsvcRole r) {
        SsvcMethodology m = methodologyRepo.findById(r.getMethodologyId())
            .orElseThrow(() -> NotFoundException.of("ssvc_methodology", r.getMethodologyId()));
        if (m.isSystem()) throw new ConflictException("System methodologies cannot be edited");
    }

    // ── Tree replace ─────────────────────────────────────────────────────────────

    @Transactional
    public SsvcRoleDetailDto replaceTree(Long roleId, ReplaceTreeRequest req) {
        SsvcRole r = roleRepo.findById(roleId).orElseThrow(() -> NotFoundException.of("ssvc_role", roleId));
        SsvcMethodology m = methodologyRepo.findById(r.getMethodologyId())
            .orElseThrow(() -> NotFoundException.of("ssvc_methodology", r.getMethodologyId()));
        if (m.isSystem()) throw new ConflictException("System methodologies cannot be edited");
        if (isLocked(roleId))
            throw new ConflictException("Role has scores referencing its tree — create a new role/methodology instead of restructuring one already in use");
        if (req.root() == null) throw new IllegalArgumentException("root is required");

        validateNode(req.root(), r.isUsesPriorityMapping());

        nodeRepo.deleteByRoleId(roleId);
        OffsetDateTime now = OffsetDateTime.now();
        insertNode(roleId, null, null, req.root(), 0, now);

        return getRoleDetail(roleId);
    }

    private void validateNode(TreeNodeInput node, boolean usesPriorityMapping) {
        if ("leaf".equals(node.nodeType())) {
            if (node.outcomeCode() == null || node.outcomeCode().isBlank())
                throw new IllegalArgumentException("Every leaf must have an outcomeCode");
            if (node.outcomeLabel() == null || node.outcomeLabel().isBlank())
                throw new IllegalArgumentException("Every leaf must have an outcomeLabel");
            if (usesPriorityMapping) {
                if (node.priorityLevel() == null || !VALID_PRIORITY_LEVELS.contains(node.priorityLevel()))
                    throw new IllegalArgumentException("Leaf '" + node.outcomeLabel() + "' must have a priorityLevel (P0-P4) — this role uses priority mapping");
            }
        } else if ("branch".equals(node.nodeType())) {
            if (node.decisionPointCode() == null || node.decisionPointCode().isBlank()
                || node.decisionPointName() == null || node.decisionPointName().isBlank())
                throw new IllegalArgumentException("Every branch must have a decisionPointCode and decisionPointName");
            if (node.options() == null || node.options().isEmpty())
                throw new IllegalArgumentException("Branch '" + node.decisionPointName() + "' must have at least one option");
            Set<String> seenCodes = new java.util.HashSet<>();
            for (TreeNodeOptionInput opt : node.options()) {
                if (opt.code() == null || opt.code().isBlank() || opt.label() == null || opt.label().isBlank())
                    throw new IllegalArgumentException("Every option needs a code and label");
                if (!seenCodes.add(opt.code()))
                    throw new IllegalArgumentException("Duplicate option code '" + opt.code() + "' under '" + node.decisionPointName() + "'");
                if (opt.child() == null)
                    throw new IllegalArgumentException("Option '" + opt.label() + "' under '" + node.decisionPointName() + "' has no child node");
                validateNode(opt.child(), usesPriorityMapping);
            }
        } else {
            throw new IllegalArgumentException("nodeType must be 'branch' or 'leaf', got '" + node.nodeType() + "'");
        }
    }

    private void insertNode(Long roleId, Long parentNodeId, String parentOptionCode, TreeNodeInput input,
                             int sortOrder, OffsetDateTime now) {
        SsvcTreeNode node = new SsvcTreeNode();
        node.setRoleId(roleId);
        node.setParentNodeId(parentNodeId);
        node.setParentOptionCode(parentOptionCode);
        node.setNodeType(input.nodeType());
        node.setSortOrder(sortOrder);
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        if ("leaf".equals(input.nodeType())) {
            node.setOutcomeCode(input.outcomeCode());
            node.setOutcomeLabel(input.outcomeLabel());
            node.setOutcomeDescription(input.outcomeDescription());
            node.setPriorityLevel(input.priorityLevel());
        } else {
            node.setDecisionPointCode(input.decisionPointCode());
            node.setDecisionPointName(input.decisionPointName());
            node.setDecisionPointHelp(input.decisionPointHelp());
        }
        SsvcTreeNode saved = nodeRepo.save(node);

        if ("branch".equals(input.nodeType())) {
            int i = 0;
            List<SsvcTreeNodeOption> optionsToSave = new ArrayList<>();
            for (TreeNodeOptionInput opt : input.options()) {
                SsvcTreeNodeOption option = new SsvcTreeNodeOption();
                option.setTreeNodeId(saved.getId());
                option.setCode(opt.code());
                option.setLabel(opt.label());
                option.setHelpText(opt.helpText());
                option.setSortOrder(i);
                optionsToSave.add(option);
                i++;
            }
            optionRepo.saveAll(optionsToSave);
            i = 0;
            for (TreeNodeOptionInput opt : input.options()) {
                insertNode(roleId, saved.getId(), opt.code(), opt.child(), i, now);
                i++;
            }
        }
    }

    // ── Export ──────────────────────────────────────────────────────────────────
    // Read-only — available to any viewer, not gated behind SSVC_WRITE, and works for
    // system methodologies too (export is not a mutation). Each role's tree is converted
    // to the same TreeNodeInput shape ReplaceTreeRequest already uses, so the export is
    // round-trippable through the existing create endpoints without a separate parser.

    public SsvcMethodologyExportDto exportOne(Long id) {
        SsvcMethodology m = methodologyRepo.findById(id).orElseThrow(() -> NotFoundException.of("ssvc_methodology", id));
        List<SsvcMethodologyExportDto.SsvcRoleExportDto> roles = roleRepo.findByMethodologyId(id).stream()
            .map(r -> {
                SsvcTreeNodeDto tree = loadTree(r.getId());
                return new SsvcMethodologyExportDto.SsvcRoleExportDto(
                    r.getCode(), r.getName(), r.getDescription(), r.isUsesPriorityMapping(),
                    parseOutcomes(r.getOutcomes()), tree != null ? toTreeNodeInput(tree) : null);
            }).toList();
        return new SsvcMethodologyExportDto(m.getCode(), m.getName(), m.getDescription(), roles);
    }

    private TreeNodeInput toTreeNodeInput(SsvcTreeNodeDto n) {
        if ("leaf".equals(n.nodeType())) {
            return new TreeNodeInput("leaf", null, null, null, null,
                n.outcomeCode(), n.outcomeLabel(), n.outcomeDescription(), n.priorityLevel());
        }
        List<TreeNodeOptionInput> options = n.options().stream()
            .map(o -> new TreeNodeOptionInput(o.code(), o.label(), o.helpText(),
                o.child() != null ? toTreeNodeInput(o.child()) : null))
            .toList();
        return new TreeNodeInput("branch", n.decisionPointCode(), n.decisionPointName(), n.decisionPointHelp(),
            options, null, null, null, null);
    }

    /** A .json file per methodology, zipped together — used for the "export selected"
     *  bulk action. */
    public byte[] exportZip(List<Long> ids) {
        try (var baos = new java.io.ByteArrayOutputStream();
             var zos = new java.util.zip.ZipOutputStream(baos)) {
            for (Long id : ids) {
                SsvcMethodologyExportDto data = exportOne(id);
                byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
                String safeCode = data.code().replaceAll("[^a-zA-Z0-9-]+", "_");
                zos.putNextEntry(new java.util.zip.ZipEntry(safeCode + "-" + id + ".json"));
                zos.write(json);
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to build export zip", e);
        }
    }

    // ── Import ──────────────────────────────────────────────────────────────────
    // Accepts any mix of .json files (one methodology each) and .zip files (a batch of
    // .json entries, as produced by exportZip) in a single upload. Best-effort: one bad
    // file or entry is reported in the result rather than aborting the whole import.

    @Transactional
    public SsvcMethodologyImportResult importFiles(List<org.springframework.web.multipart.MultipartFile> files) {
        int imported = 0;
        List<String> errors = new ArrayList<>();
        for (var file : files) {
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
            try {
                if (name.toLowerCase().endsWith(".zip")) {
                    try (var zis = new java.util.zip.ZipInputStream(file.getInputStream())) {
                        java.util.zip.ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".json")) continue;
                            byte[] bytes = zis.readAllBytes();
                            try {
                                importMethodology(MAPPER.readValue(bytes, SsvcMethodologyExportDto.class));
                                imported++;
                            } catch (Exception e) {
                                errors.add(name + "/" + entry.getName() + ": " + e.getMessage());
                            }
                        }
                    }
                } else {
                    importMethodology(MAPPER.readValue(file.getBytes(), SsvcMethodologyExportDto.class));
                    imported++;
                }
            } catch (Exception e) {
                errors.add(name + ": " + e.getMessage());
            }
        }
        return new SsvcMethodologyImportResult(imported, errors);
    }

    /** A code collision is expected (e.g. re-importing an export of a methodology that
     *  still exists) — rather than failing, the imported copy gets a de-duplicated code
     *  so both can coexist; the user can rename/delete afterwards. */
    private void importMethodology(SsvcMethodologyExportDto data) {
        String code = data.code();
        int suffix = 2;
        while (methodologyRepo.findByCode(code).isPresent()) {
            code = data.code() + "-" + suffix;
            suffix++;
        }
        SsvcMethodologyDto methodology = createMethodology(new CreateMethodologyRequest(code, data.name(), data.description()));
        for (SsvcMethodologyExportDto.SsvcRoleExportDto roleData : data.roles()) {
            var role = createRole(methodology.id(), new CreateRoleRequest(
                roleData.code(), roleData.name(), roleData.description(), roleData.usesPriorityMapping()));
            if (roleData.outcomes() != null && !roleData.outcomes().isEmpty()) {
                updateRole(role.id(), new UpdateRoleRequest(null, null, null, roleData.outcomes()));
            }
            if (roleData.tree() != null) {
                replaceTree(role.id(), new ReplaceTreeRequest(roleData.tree()));
            }
        }
    }
}
