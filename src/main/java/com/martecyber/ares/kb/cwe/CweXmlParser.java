package com.martecyber.ares.kb.cwe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds {@link CweEntry} instances via accumulate-then-set, not accumulate-by-mutating-a-live-
 * getter-result — {@code CweEntry}'s list-typed getters (Postgres-backed since Phase 5 of the
 * AQL-wide initiative: {@code parentIds}/{@code childIds}/etc. are native array columns,
 * {@code consequences}/{@code mitigations} are JSONB-string-backed) each return a fresh immutable
 * snapshot, not a mutable reference into the entity's own storage — {@code current.getParentIds()
 * .add(id)} would throw {@code UnsupportedOperationException} the way the old Mongo-era version of
 * this parser wrote it (a plain mutable {@code ArrayList} field on the Mongo document). Every
 * per-entry list is accumulated in a local field on {@link Handler} instead and committed via the
 * corresponding setter when the entry closes.
 */
@Component
public class CweXmlParser {

    private static final Logger log = LoggerFactory.getLogger(CweXmlParser.class);

    public static final String CWE_ZIP_URL = "https://cwe.mitre.org/data/xml/cwec_latest.xml.zip";

    public record ParseResult(List<CweEntry> entries, String version, int total) {}

    public ParseResult parse(InputStream in, Consumer<Integer> progress) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        SAXParser parser = factory.newSAXParser();
        Handler handler = new Handler(progress);
        parser.parse(in, handler);
        postProcess(handler);
        return new ParseResult(handler.entries, handler.version, handler.entries.size());
    }

    /** Resolves the two cross-entry relationships MITRE's schema can't express inline while an
     *  entry is still open: bibliographic URL references (a per-entry list of REF-ids that only
     *  resolve against the catalog's {@code <External_References>} block, which comes later in
     *  the document) and Category/View memberships (attached to the *container*'s Has_Member
     *  edges, not the weakness itself, so they must be inverted after every entry has a name). */
    private void postProcess(Handler h) {
        for (CweEntry e : h.entries) {
            List<CweEntry.RelatedWeakness> rws = e.getRelatedWeaknesses();
            if (!rws.isEmpty()) {
                List<CweEntry.RelatedWeakness> resolved = new ArrayList<>();
                for (CweEntry.RelatedWeakness rw : rws) {
                    String viewName = rw.viewId() != null ? h.namesById.get(rw.viewId()) : null;
                    resolved.add(new CweEntry.RelatedWeakness(rw.cweId(), rw.nature(), rw.viewId(), viewName));
                }
                e.setRelatedWeaknesses(resolved);
            }
        }

        for (CweEntry e : h.entries) {
            List<String> refIds = h.pendingReferenceIdsByCweId.get(e.getCweId());
            if (refIds != null && !refIds.isEmpty()) {
                List<CweEntry.Reference> resolved = new ArrayList<>();
                for (String refId : refIds) {
                    CweEntry.Reference r = h.referenceCatalog.get(refId);
                    if (r != null) resolved.add(r);
                }
                if (!resolved.isEmpty()) e.setReferences(resolved);
            }
        }

        Map<String, List<CweEntry.Membership>> membershipsByMemberId = new HashMap<>();
        for (Handler.MembershipEdge edge : h.membershipEdges) {
            String categoryName = h.namesById.get(edge.containerId());
            String viewName = edge.viewId() != null ? h.namesById.get(edge.viewId()) : null;
            membershipsByMemberId.computeIfAbsent(edge.memberId(), k -> new ArrayList<>())
                .add(new CweEntry.Membership(edge.viewId(), viewName, edge.containerId(), categoryName));
        }
        for (CweEntry e : h.entries) {
            List<CweEntry.Membership> ms = membershipsByMemberId.get(e.getCweId());
            if (ms != null) e.setMemberships(ms);
        }
    }

    private static class Handler extends DefaultHandler {

        private final Consumer<Integer> progress;
        final List<CweEntry> entries = new ArrayList<>();
        String version;

        /** Every entry's id→name, Weakness/Category/View alike — needed post-parse to resolve
         *  membership category/view names, which are only known once *their* entry has closed. */
        final Map<String, String> namesById = new HashMap<>();
        /** Has_Member edges collected while inside a Category/View entry — inverted post-parse
         *  into per-weakness {@link CweEntry.Membership} rows (see {@link #postProcess}). */
        record MembershipEdge(String containerId, String memberId, String viewId) {}
        final List<MembershipEdge> membershipEdges = new ArrayList<>();
        /** Catalog of {@code <External_Reference Reference_ID="REF-n">} entries, built as the
         *  document's trailing {@code <External_References>} block is parsed. */
        final Map<String, CweEntry.Reference> referenceCatalog = new HashMap<>();
        /** Per-weakness list of REF-ids from its own {@code <References>} block, resolved against
         *  {@link #referenceCatalog} post-parse since that catalog isn't complete yet. */
        final Map<String, List<String>> pendingReferenceIdsByCweId = new HashMap<>();

        private CweEntry current;
        private List<String> currentParentIds;
        private List<String> currentChildIds;
        private List<String> currentRelatedCapecIds;
        private List<String> currentApplicablePlatforms;
        private List<String> currentObservedExamples;
        private List<CweEntry.Consequence> currentConsequences;
        private List<CweEntry.Mitigation> currentMitigations;
        private List<CweEntry.RelatedWeakness> currentRelatedWeaknesses;
        private List<String> currentReferenceIds;
        private List<CweEntry.Note> currentNotes;
        private List<CweEntry.AlternateTerm> currentAlternateTerms;
        private List<CweEntry.DetectionMethod> currentDetectionMethods;

        private List<String> scopes;
        private List<String> impacts;
        private List<String> phases;
        private CweEntry.Mitigation currentMitigation;
        private String consequenceLikelihood;
        private String consequenceNote;
        private String mappingUsage;
        private String mappingRationale;
        private String mappingComments;
        private String noteType;
        private String currentTermText;
        private String currentTermDescription;
        private CweEntry.DetectionMethod currentDetectionMethod;
        private String currentExtRefId;
        private String currentExtRefTitle;
        private String currentExtRefUrl;

        private final List<String> stack = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        Handler(Consumer<Integer> progress) {
            this.progress = progress;
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes attrs) throws SAXException {
            stack.add(local);
            text.setLength(0);

            switch (local) {
                case "Weakness_Catalog" -> version = attrs.getValue("Version");
                case "Weakness" -> startEntry(attrs, "Weakness");
                case "Category"  -> startEntry(attrs, "Category");
                case "View"      -> startEntry(attrs, "View");
                case "Mapping_Notes" -> { mappingUsage = null; mappingRationale = null; mappingComments = null; }
                case "Consequence" -> {
                    scopes = new ArrayList<>();
                    impacts = new ArrayList<>();
                    consequenceLikelihood = null;
                    consequenceNote = null;
                }
                case "Mitigation" -> {
                    if (inSection("Potential_Mitigations")) {
                        phases = new ArrayList<>();
                        currentMitigation = new CweEntry.Mitigation(null, null, null, null);
                    }
                }
                case "Related_Weakness" -> {
                    if (current != null) {
                        String nature = attrs.getValue("Nature");
                        String id = attrs.getValue("CWE_ID");
                        String viewId = attrs.getValue("View_ID");
                        if (id != null) {
                            if ("ChildOf".equals(nature)) currentParentIds.add(id);
                            else if ("ParentOf".equals(nature)) currentChildIds.add(id);
                            // viewName isn't known yet (that View's own entry may not have closed
                            // yet) — resolved against namesById in CweXmlParser.postProcess.
                            if (nature != null) currentRelatedWeaknesses.add(new CweEntry.RelatedWeakness(id, nature, viewId, null));
                        }
                    }
                }
                case "Has_Member" -> {
                    // Membership lives on the *container* (Category/View), tagged with the View
                    // it was declared under — inverted per-weakness in CweXmlParser.postProcess.
                    if (current != null) {
                        String memberId = attrs.getValue("CWE_ID");
                        String viewId = attrs.getValue("View_ID");
                        if (memberId != null) membershipEdges.add(new MembershipEdge(current.getCweId(), memberId, viewId));
                    }
                }
                case "Related_Attack_Pattern" -> {
                    if (current != null) {
                        String capecId = attrs.getValue("CAPEC_ID");
                        if (capecId != null) currentRelatedCapecIds.add(capecId);
                    }
                }
                case "Language", "Technology", "Operating_System" -> {
                    if (current != null && inSection("Applicable_Platforms")) {
                        String name = attrs.getValue("Name");
                        String clazz = attrs.getValue("Class");
                        if (name != null) currentApplicablePlatforms.add(name);
                        else if (clazz != null) currentApplicablePlatforms.add(clazz);
                    }
                }
                case "Reference" -> {
                    // Self-closing, attribute-only weakness-level reference (distinct from
                    // Observed_Examples' text-content <Reference>CVE-x</Reference>, handled on
                    // endElement below) — just the REF-id, resolved post-parse against the
                    // trailing <External_References> catalog.
                    if (current != null && inSection("References") && !inSection("Observed_Examples")) {
                        String refId = attrs.getValue("External_Reference_ID");
                        if (refId != null) currentReferenceIds.add(refId);
                    }
                }
                case "External_Reference" -> {
                    currentExtRefId = attrs.getValue("Reference_ID");
                    currentExtRefTitle = null;
                    currentExtRefUrl = null;
                }
                case "Note" -> { if (current != null) noteType = attrs.getValue("Type"); }
                case "Alternate_Term" -> { currentTermText = null; currentTermDescription = null; }
                case "Detection_Method" -> {
                    if (inSection("Detection_Methods")) currentDetectionMethod = new CweEntry.DetectionMethod(null, null, null);
                }
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) throws SAXException {
            String t = text.toString().trim();

            switch (local) {
                case "Weakness", "Category", "View" -> {
                    if (current != null && current.getCweId() != null) {
                        current.setParentIds(currentParentIds);
                        current.setChildIds(currentChildIds);
                        current.setRelatedCapecIds(currentRelatedCapecIds);
                        current.setApplicablePlatforms(currentApplicablePlatforms);
                        current.setObservedExamples(currentObservedExamples);
                        current.setConsequences(currentConsequences);
                        current.setMitigations(currentMitigations);
                        current.setRelatedWeaknesses(currentRelatedWeaknesses);
                        current.setNotes(currentNotes);
                        current.setAlternateTerms(currentAlternateTerms);
                        current.setDetectionMethods(currentDetectionMethods);
                        current.setSyncedAt(Instant.now());
                        current.setSourceVersion(version);
                        namesById.put(current.getCweId(), current.getName());
                        if (!currentReferenceIds.isEmpty())
                            pendingReferenceIdsByCweId.put(current.getCweId(), new ArrayList<>(currentReferenceIds));
                        entries.add(current);
                        if (progress != null && entries.size() % 100 == 0) progress.accept(entries.size());
                    }
                    current = null;
                }
                case "Description" -> {
                    if (current != null && isDirectChildOf("Weakness", "Category", "View") && !t.isEmpty())
                        current.setDescription(t);
                    else if (currentMitigation != null && inSection("Potential_Mitigations") && !t.isEmpty()) {
                        currentMitigation = new CweEntry.Mitigation(
                            currentMitigation.phases(), currentMitigation.strategy(), t, currentMitigation.effectiveness());
                    } else if (inSection("Alternate_Term") && !t.isEmpty()) {
                        currentTermDescription = t;
                    } else if (currentDetectionMethod != null && inSection("Detection_Methods") && !t.isEmpty()) {
                        currentDetectionMethod = new CweEntry.DetectionMethod(
                            currentDetectionMethod.method(), t, currentDetectionMethod.effectiveness());
                    }
                }
                case "Extended_Description" -> { if (current != null && !t.isEmpty()) current.setExtendedDescription(t); }
                case "Scope"  -> { if (scopes != null && !t.isEmpty()) scopes.add(t); }
                case "Impact" -> { if (impacts != null && !t.isEmpty()) impacts.add(t); }
                case "Note"   -> {
                    if (scopes != null) consequenceNote = t;
                    else if (current != null && inSection("Notes") && !t.isEmpty()) currentNotes.add(new CweEntry.Note(noteType, t));
                }
                case "Term" -> { if (inSection("Alternate_Term") && !t.isEmpty()) currentTermText = t; }
                case "Alternate_Term" -> {
                    if (current != null && currentTermText != null) currentAlternateTerms.add(new CweEntry.AlternateTerm(currentTermText, currentTermDescription));
                    currentTermText = null;
                    currentTermDescription = null;
                }
                case "Method" -> {
                    if (currentDetectionMethod != null && inSection("Detection_Methods"))
                        currentDetectionMethod = new CweEntry.DetectionMethod(t, currentDetectionMethod.description(), currentDetectionMethod.effectiveness());
                }
                case "Detection_Method" -> {
                    if (currentDetectionMethod != null && current != null) currentDetectionMethods.add(currentDetectionMethod);
                    currentDetectionMethod = null;
                }
                case "URL" -> { if (currentExtRefId != null) currentExtRefUrl = t; }
                case "Title" -> { if (currentExtRefId != null) currentExtRefTitle = t; }
                case "External_Reference" -> {
                    if (currentExtRefId != null && currentExtRefUrl != null)
                        referenceCatalog.put(currentExtRefId, new CweEntry.Reference(currentExtRefUrl, currentExtRefTitle));
                    currentExtRefId = null;
                    currentExtRefTitle = null;
                    currentExtRefUrl = null;
                }
                case "Likelihood" -> {
                    if (scopes != null) consequenceLikelihood = t;
                    else if (current != null && inSection("Likelihood_Of_Exploit") && !t.isEmpty())
                        current.setLikelihoodOfExploit(t);
                }
                case "Consequence" -> {
                    if (current != null && scopes != null)
                        currentConsequences.add(new CweEntry.Consequence(
                            new ArrayList<>(scopes), new ArrayList<>(impacts), consequenceLikelihood, consequenceNote));
                    scopes = null;
                    impacts = null;
                }
                case "Phase" -> { if (phases != null && !t.isEmpty()) phases.add(t); }
                case "Strategy" -> {
                    if (currentMitigation != null) currentMitigation = new CweEntry.Mitigation(
                        currentMitigation.phases(), t, currentMitigation.description(), currentMitigation.effectiveness());
                }
                case "Effectiveness" -> {
                    if (currentMitigation != null) currentMitigation = new CweEntry.Mitigation(
                        currentMitigation.phases(), currentMitigation.strategy(), currentMitigation.description(), t);
                    else if (currentDetectionMethod != null) currentDetectionMethod = new CweEntry.DetectionMethod(
                        currentDetectionMethod.method(), currentDetectionMethod.description(), t);
                }
                case "Mitigation" -> {
                    if (currentMitigation != null && current != null) {
                        currentMitigations.add(new CweEntry.Mitigation(
                            phases != null ? new ArrayList<>(phases) : List.of(),
                            currentMitigation.strategy(), currentMitigation.description(), currentMitigation.effectiveness()));
                    }
                    currentMitigation = null;
                    phases = null;
                }
                case "Observed_Example" -> {
                    // The Reference element inside Observed_Example holds the CVE ID
                }
                case "Reference" -> {
                    if (inSection("Observed_Examples") && !t.isEmpty() && current != null)
                        currentObservedExamples.add(t);
                }
                case "Usage" -> {
                    if (inSection("Mapping_Notes") && !t.isEmpty()) mappingUsage = t;
                }
                case "Rationale" -> {
                    if (inSection("Mapping_Notes") && !t.isEmpty()) mappingRationale = t;
                }
                case "Comments" -> {
                    if (inSection("Mapping_Notes") && !t.isEmpty()) mappingComments = t;
                }
                case "Mapping_Notes" -> {
                    if (current != null && mappingUsage != null)
                        current.setVulnerabilityMapping(new CweEntry.VulnerabilityMapping(mappingUsage, mappingRationale, mappingComments));
                    mappingUsage = null; mappingRationale = null; mappingComments = null;
                }
            }

            if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            text.setLength(0);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        private void startEntry(Attributes attrs, String type) {
            current = new CweEntry();
            current.setCweId(attrs.getValue("ID"));
            current.setCode("CWE-" + attrs.getValue("ID"));
            current.setName(attrs.getValue("Name"));
            current.setType(type);
            current.setAbstraction(attrs.getValue("Abstraction"));
            current.setStatus(attrs.getValue("Status"));
            currentParentIds = new ArrayList<>();
            currentChildIds = new ArrayList<>();
            currentRelatedCapecIds = new ArrayList<>();
            currentApplicablePlatforms = new ArrayList<>();
            currentObservedExamples = new ArrayList<>();
            currentConsequences = new ArrayList<>();
            currentMitigations = new ArrayList<>();
            currentRelatedWeaknesses = new ArrayList<>();
            currentReferenceIds = new ArrayList<>();
            currentNotes = new ArrayList<>();
            currentAlternateTerms = new ArrayList<>();
            currentDetectionMethods = new ArrayList<>();
        }

        private boolean inSection(String name) {
            return stack.contains(name);
        }

        private boolean isDirectChildOf(String... parents) {
            if (stack.size() < 2) return false;
            String parent = stack.get(stack.size() - 2);
            for (String p : parents) if (p.equals(parent)) return true;
            return false;
        }
    }
}
