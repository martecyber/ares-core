package com.martecyber.ares.aql;

import com.martecyber.ares.aql.compile.PostgresSpecificationCompiler;
import com.martecyber.ares.aql.parser.AqlNode;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.aql.registry.AqlField;
import com.martecyber.ares.aql.registry.EntityAqlRegistry;
import com.martecyber.ares.aql.registry.PostgresColumnField;
import com.martecyber.ares.findings.templates.FindingTemplate;
import com.martecyber.ares.findings.templates.FindingTemplateAqlRegistry;
import com.martecyber.ares.findings.templates.FindingTemplateRepository;
import com.martecyber.ares.kb.attack.AttackAqlRegistry;
import com.martecyber.ares.kb.attack.AttackTechnique;
import com.martecyber.ares.kb.attack.AttackTechniqueRepository;
import com.martecyber.ares.kb.capec.CapecAqlRegistry;
import com.martecyber.ares.kb.capec.CapecEntry;
import com.martecyber.ares.kb.capec.CapecRepository;
import com.martecyber.ares.kb.cve.CveAqlRegistry;
import com.martecyber.ares.kb.cve.CveEntry;
import com.martecyber.ares.kb.cve.CveRepository;
import com.martecyber.ares.kb.cwe.CweAqlRegistry;
import com.martecyber.ares.kb.cwe.CweEntry;
import com.martecyber.ares.kb.cwe.CweRepository;
import com.martecyber.ares.kb.exploits.ExploitAqlRegistry;
import com.martecyber.ares.kb.exploits.ExploitEntry;
import com.martecyber.ares.kb.exploits.ExploitRepository;
import com.martecyber.ares.kb.owasp.OwaspAqlRegistry;
import com.martecyber.ares.kb.owasp.OwaspEntry;
import com.martecyber.ares.kb.owasp.OwaspRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Generic AQL execution for the platform-wide "catalog" entities — CVE/CWE/CAPEC/exploit/OWASP/
 * ATT&amp;CK technique/finding template. Deliberately excludes {@code asset}/{@code finding}/{@code
 * detection}: those are project-scoped and already have their own {@code listByAql} service
 * methods that AND in an org/project {@code Specification} on top of the AQL-derived one (see
 * {@code AssetService}/{@code FindingService}/{@code DetectionService}) — bypassing those via a
 * naive generic executor would reopen the exact class of cross-tenant leak fixed earlier
 * (Asset&harr;Detection/Finding AQL leak). Every entity registered here has zero tenant boundary
 * today (their own REST controllers apply no org/project filtering either), so a single unscoped
 * executor is safe for them specifically — not a general-purpose "run AQL against anything".
 *
 * <p>{@code attackTactic}/{@code cveKevDetail} are deliberately not registered here — both are
 * relation-only registries with no standalone REST {@code ?aql=} surface by product decision
 * (only reachable nested as {@code attack.tactics.*}/{@code cve.kev.*}).
 */
@Component
public class AqlQueryableEntityRegistry {

    @PersistenceContext
    private EntityManager em;

    private final Map<String, Pairing<?>> byEntityName;

    public AqlQueryableEntityRegistry(CveAqlRegistry cveRegistry, CveRepository cveRepository,
                                      CweAqlRegistry cweRegistry, CweRepository cweRepository,
                                      CapecAqlRegistry capecRegistry, CapecRepository capecRepository,
                                      ExploitAqlRegistry exploitRegistry, ExploitRepository exploitRepository,
                                      OwaspAqlRegistry owaspRegistry, OwaspRepository owaspRepository,
                                      AttackAqlRegistry attackRegistry, AttackTechniqueRepository attackRepository,
                                      FindingTemplateAqlRegistry findingTemplateRegistry, FindingTemplateRepository findingTemplateRepository) {
        this.byEntityName = Map.ofEntries(
            pairing(cveRegistry, cveRepository, CveEntry.class),
            pairing(cweRegistry, cweRepository, CweEntry.class),
            pairing(capecRegistry, capecRepository, CapecEntry.class),
            pairing(exploitRegistry, exploitRepository, ExploitEntry.class),
            pairing(owaspRegistry, owaspRepository, OwaspEntry.class),
            pairing(attackRegistry, attackRepository, AttackTechnique.class),
            pairing(findingTemplateRegistry, findingTemplateRepository, FindingTemplate.class));
    }

    private record Pairing<T>(EntityAqlRegistry<T> registry, JpaSpecificationExecutor<T> repository, Class<T> entityClass) {}

    private static <T> Map.Entry<String, Pairing<?>> pairing(EntityAqlRegistry<T> registry, JpaSpecificationExecutor<T> repo, Class<T> entityClass) {
        return Map.entry(registry.entityName(), new Pairing<>(registry, repo, entityClass));
    }

    public boolean supports(String entityType) {
        return byEntityName.containsKey(entityType);
    }

    public EntityAqlRegistry<?> registryFor(String entityType) {
        return pairingFor(entityType).registry();
    }

    private Pairing<?> pairingFor(String entityType) {
        Pairing<?> p = byEntityName.get(entityType);
        if (p == null) {
            throw new IllegalArgumentException("Entity '" + entityType + "' isn't queryable from a workflow variable");
        }
        return p;
    }

    /** Whole-entity mode — same shape as {@code AssetService.listByAql}/etc., minus the scope AND. */
    @SuppressWarnings("unchecked")
    public <T> List<T> queryEntities(String entityType, String aql, int limit) {
        Pairing<T> p = (Pairing<T>) pairingFor(entityType);
        AqlNode node = AqlParser.parse(aql);
        Specification<T> spec = new PostgresSpecificationCompiler<>(p.registry()).compile(node);
        return p.repository().findAll(spec, PageRequest.of(0, limit)).getContent();
    }

    /** Count-only mode — no row fetch, for Workflow CONDITION nodes comparing result counts. */
    @SuppressWarnings("unchecked")
    public <T> long countEntities(String entityType, String aql) {
        Pairing<T> p = (Pairing<T>) pairingFor(entityType);
        AqlNode node = AqlParser.parse(aql);
        Specification<T> spec = new PostgresSpecificationCompiler<>(p.registry()).compile(node);
        return p.repository().count(spec);
    }

    /** Field-projection mode — selects one column instead of the whole entity, for scalar-typed
     *  workflow variables. Only {@link PostgresColumnField} kinds (plain/array columns) are
     *  projectable; relation/jsonb/kb-materialized fields are rejected, same posture as Workflow
     *  CONDITION nodes restricting to {@code InMemoryResolvableField}-capable fields. An
     *  {@code ARRAY_COLUMN} field's per-row array is flattened into individual scalar entries —
     *  a "string" variable is a flat list of strings, not a list of arrays. */
    @SuppressWarnings("unchecked")
    public <T> List<Object> queryProjected(String entityType, String aql, String fieldName, int limit) {
        Pairing<T> p = (Pairing<T>) pairingFor(entityType);
        AqlField<T> field = p.registry().requireField(fieldName);
        if (!(field instanceof PostgresColumnField<T> column)) {
            throw new IllegalArgumentException(
                "Field '" + fieldName + "' on entity '" + entityType + "' can't be projected into a variable — "
                    + "only plain or array columns support this today");
        }
        AqlNode node = AqlParser.parse(aql);
        Specification<T> spec = new PostgresSpecificationCompiler<>(p.registry()).compile(node);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<T> root = cq.from(p.entityClass());
        Predicate predicate = spec.toPredicate(root, cq, cb);
        cq.where(predicate);
        cq.select(column.resolvePath(root));

        List<Object> rows = em.createQuery(cq).setMaxResults(limit).getResultList();
        List<Object> flattened = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof String[] arr) {
                flattened.addAll(Arrays.asList(arr));
            } else if (row instanceof List<?> list) {
                flattened.addAll(list);
            } else if (row != null) {
                flattened.add(row);
            }
        }
        return flattened;
    }
}
