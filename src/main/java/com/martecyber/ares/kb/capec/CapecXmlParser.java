package com.martecyber.ares.kb.capec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class CapecXmlParser {

    private static final Logger log = LoggerFactory.getLogger(CapecXmlParser.class);

    public static final String CAPEC_XML_URL = "https://capec.mitre.org/data/xml/capec_latest.xml";

    public List<CapecEntry> parse(InputStream in, Consumer<Integer> progress) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(in);
        doc.getDocumentElement().normalize();

        String version = doc.getDocumentElement().getAttribute("Version");
        if (version == null || version.isBlank()) version = "Unknown";

        NodeList patterns = doc.getElementsByTagNameNS("*", "Attack_Pattern");
        int total = patterns.getLength();
        log.info("Found {} CAPEC attack patterns", total);

        // Unlike CWE's streaming SAX parser, the whole document is already in memory here, so
        // the <External_References> catalog can just be built once up front and resolved inline
        // per pattern — no post-process pass needed.
        Map<String, CapecEntry.Reference> refCatalog = parseReferenceCatalog(doc);

        List<CapecEntry> entries = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            try {
                CapecEntry entry = parsePattern((Element) patterns.item(i), version, refCatalog);
                if (entry != null) entries.add(entry);
            } catch (Exception e) {
                log.warn("Failed to parse CAPEC pattern at index {}: {}", i, e.getMessage());
            }
            if (progress != null && i % 50 == 0) progress.accept(i);
        }
        if (progress != null) progress.accept(total);
        return entries;
    }

    private Map<String, CapecEntry.Reference> parseReferenceCatalog(Document doc) {
        Map<String, CapecEntry.Reference> catalog = new HashMap<>();
        NodeList refs = doc.getElementsByTagNameNS("*", "External_Reference");
        for (int i = 0; i < refs.getLength(); i++) {
            Element r = (Element) refs.item(i);
            String refId = r.getAttribute("Reference_ID");
            if (refId == null || refId.isEmpty()) continue;
            String url = text(r, "URL");
            String title = text(r, "Title");
            if (url != null) catalog.put(refId, new CapecEntry.Reference(url, title));
        }
        return catalog;
    }

    private CapecEntry parsePattern(Element el, String version, Map<String, CapecEntry.Reference> refCatalog) {
        CapecEntry e = new CapecEntry();
        e.setCapecId(el.getAttribute("ID"));
        e.setCode("CAPEC-" + el.getAttribute("ID"));
        e.setName(el.getAttribute("Name"));
        e.setAbstraction(el.getAttribute("Abstraction"));
        e.setStatus(el.getAttribute("Status"));
        e.setDescription(text(el, "Description"));
        e.setExtendedDescription(text(el, "Extended_Description"));
        e.setTypicalSeverity(text(el, "Typical_Severity"));
        e.setLikelihoodOfAttack(text(el, "Likelihood_Of_Attack"));
        e.setSyncedAt(Instant.now());
        e.setSourceVersion(version);

        Element prereqs = first(el, "Prerequisites");
        if (prereqs != null) e.setPrerequisites(texts(prereqs, "Prerequisite"));

        Element mitigations = first(el, "Mitigations");
        if (mitigations != null) e.setMitigations(parseMitigations(mitigations));

        Element consequences = first(el, "Consequences");
        if (consequences != null) e.setConsequences(parseConsequences(consequences));

        Element relatedWeaknesses = first(el, "Related_Weaknesses");
        if (relatedWeaknesses != null) {
            List<String> cweIds = new ArrayList<>();
            NodeList nodes = relatedWeaknesses.getElementsByTagNameNS("*", "Related_Weakness");
            for (int i = 0; i < nodes.getLength(); i++) {
                String cweId = ((Element) nodes.item(i)).getAttribute("CWE_ID");
                if (cweId != null && !cweId.isEmpty()) cweIds.add("CWE-" + cweId);
            }
            e.setRelatedCweIds(cweIds);
        }

        Element taxonomies = first(el, "Taxonomy_Mappings");
        if (taxonomies != null) e.setRelatedAttackTechniqueIds(parseAttackMappings(taxonomies));

        Element domains = first(el, "Domains_Of_Attack");
        if (domains != null) e.setDomains(texts(domains, "Domain_Of_Attack"));

        Element relatedPatterns = first(el, "Related_Attack_Patterns");
        if (relatedPatterns != null) parseRelated(relatedPatterns, e);

        Element executionFlow = first(el, "Execution_Flow");
        if (executionFlow != null) e.setExecutionFlow(parseFlow(executionFlow));

        Element references = first(el, "References");
        if (references != null) e.setReferences(parseReferences(references, refCatalog));

        return e;
    }

    private List<CapecEntry.Reference> parseReferences(Element el, Map<String, CapecEntry.Reference> refCatalog) {
        List<CapecEntry.Reference> result = new ArrayList<>();
        NodeList nodes = el.getElementsByTagNameNS("*", "Reference");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element ref = (Element) nodes.item(i);
            // The attribute name isn't 100% consistent across MITRE's XML schemas (CWE uses
            // External_Reference_ID; CAPEC's own convention is checked first, with that as a
            // fallback) — try both rather than silently dropping every reference if this guess
            // is wrong for the currently-synced schema version.
            String refId = ref.getAttribute("Reference_ID");
            if (refId.isEmpty()) refId = ref.getAttribute("External_Reference_ID");
            CapecEntry.Reference r = !refId.isEmpty() ? refCatalog.get(refId) : null;
            if (r != null) result.add(r);
        }
        return result;
    }

    private List<String> parseMitigations(Element el) {
        List<String> result = new ArrayList<>();
        NodeList nodes = el.getElementsByTagNameNS("*", "Mitigation");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element m = (Element) nodes.item(i);
            Element desc = first(m, "Description");
            String t = desc != null ? desc.getTextContent().trim() : m.getTextContent().trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    private List<CapecEntry.Consequence> parseConsequences(Element el) {
        List<CapecEntry.Consequence> result = new ArrayList<>();
        NodeList nodes = el.getElementsByTagNameNS("*", "Consequence");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element c = (Element) nodes.item(i);
            List<String> scopes = texts(c, "Scope");
            List<String> impacts = texts(c, "Impact");
            String likelihood = text(c, "Likelihood");
            String note = text(c, "Note");
            result.add(new CapecEntry.Consequence(scopes, impacts, likelihood, note));
        }
        return result;
    }

    private List<String> parseAttackMappings(Element el) {
        List<String> result = new ArrayList<>();
        NodeList nodes = el.getElementsByTagNameNS("*", "Taxonomy_Mapping");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element m = (Element) nodes.item(i);
            String name = m.getAttribute("Taxonomy_Name");
            if ("ATTACK".equals(name) || (name != null && name.contains("ATT&CK"))) {
                Element entryId = first(m, "Entry_ID");
                if (entryId != null) {
                    String raw = entryId.getTextContent().trim();
                    result.add(raw.startsWith("T") ? raw : "T" + raw);
                }
            }
        }
        return result;
    }

    private void parseRelated(Element el, CapecEntry entry) {
        List<String> parents = new ArrayList<>();
        List<String> children = new ArrayList<>();
        List<CapecEntry.RelatedPattern> all = new ArrayList<>();
        NodeList nodes = el.getElementsByTagNameNS("*", "Related_Attack_Pattern");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element r = (Element) nodes.item(i);
            String nature = r.getAttribute("Nature");
            String id = r.getAttribute("CAPEC_ID");
            if (id != null && !id.isEmpty()) {
                if ("ChildOf".equals(nature)) parents.add(id);
                else if ("ParentOf".equals(nature)) children.add(id);
                if (nature != null && !nature.isEmpty()) all.add(new CapecEntry.RelatedPattern(id, nature));
            }
        }
        entry.setParentCapecIds(parents);
        entry.setChildCapecIds(children);
        entry.setRelatedAttackPatterns(all);
    }

    private List<CapecEntry.AttackStep> parseFlow(Element el) {
        List<CapecEntry.AttackStep> steps = new ArrayList<>();
        NodeList nodes = el.getElementsByTagNameNS("*", "Attack_Step");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element s = (Element) nodes.item(i);
            String stepNum = text(s, "Step");
            int num;
            try { num = Integer.parseInt(stepNum != null ? stepNum : ""); } catch (NumberFormatException e) { num = i + 1; }
            String phase = text(s, "Phase");
            String desc = first(s, "Description") != null ? first(s, "Description").getTextContent().trim() : null;
            List<String> techniques = texts(s, "Technique");
            steps.add(new CapecEntry.AttackStep(num, phase, desc, techniques));
        }
        return steps;
    }

    // ── XML helpers ─────────────────────────────────────────────────────

    private Element first(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagNameNS("*", tag);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private String text(Element parent, String tag) {
        Element e = first(parent, tag);
        return e != null ? e.getTextContent().trim() : null;
    }

    private List<String> texts(Element parent, String tag) {
        List<String> result = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS("*", tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            String t = nodes.item(i).getTextContent().trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }
}
