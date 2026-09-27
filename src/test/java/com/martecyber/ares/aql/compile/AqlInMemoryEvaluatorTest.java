package com.martecyber.ares.aql.compile;

import com.martecyber.ares.aql.parser.AqlNode;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.aql.registry.*;
import com.martecyber.ares.detections.Detection;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests for {@link AqlInMemoryEvaluator} — no Spring context, no DB. Uses a small
 *  ad-hoc registry (not the real {@code DetectionAqlRegistry}, which needs Spring-wired
 *  repositories) so this stays hermetic and targets the evaluator's own logic in isolation. */
class AqlInMemoryEvaluatorTest {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> NUMBER_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ);
    private static final Set<AqlOperator> ARRAY_OPS = EnumSet.of(AqlOperator.HAS);

    private static final class FixtureRegistry implements EntityAqlRegistry<Detection> {
        private final Map<String, AqlField<Detection>> fields = new LinkedHashMap<>();

        FixtureRegistry() {
            fields.put("title", new ColumnAqlField<>("title", AqlFieldType.STRING, AqlFieldKind.PHYSICAL_COLUMN,
                STRING_OPS, r -> r.get("title"), List.of(), (java.util.function.Function<Detection, Object>) Detection::getTitle));
            fields.put("priority", new ColumnAqlField<>("priority", AqlFieldType.PRIORITY, AqlFieldKind.PHYSICAL_COLUMN,
                NUMBER_OPS, r -> r.get("priority"), AqlField.PRIORITY_LABELS, (java.util.function.Function<Detection, Object>) Detection::getPriority));
            fields.put("occurrenceCount", new ColumnAqlField<>("occurrenceCount", AqlFieldType.NUMBER, AqlFieldKind.PHYSICAL_COLUMN,
                NUMBER_OPS, r -> r.get("occurrenceCount"), List.of(), (java.util.function.Function<Detection, Object>) (Detection d) -> (double) d.getOccurrenceCount()));
            fields.put("createdAt", new ColumnAqlField<>("createdAt", AqlFieldType.DATE, AqlFieldKind.PHYSICAL_COLUMN,
                NUMBER_OPS, r -> r.get("createdAt"), List.of(), (java.util.function.Function<Detection, Object>) Detection::getCreatedAt));
            fields.put("kevListed", new KbMaterializedField<>("kevListed", AqlFieldType.BOOLEAN, BOOLEAN_OPS,
                1L, "detections", "kevListed"));
            fields.put("noResolver", new ColumnAqlField<>("noResolver", AqlFieldType.STRING, AqlFieldKind.PHYSICAL_COLUMN,
                STRING_OPS, r -> r.get("sourceType")));
            // Not a real Detection column — a test-only stand-in for a future ARRAY_COLUMN field
            // (e.g. cve.cwes in Phase 3), resolved here by splitting sourceType on comma purely so
            // HAS has something list-shaped to evaluate against without touching the real entity.
            fields.put("tags", new ColumnAqlField<>("tags", AqlFieldType.STRING_LIST, AqlFieldKind.PHYSICAL_COLUMN,
                ARRAY_OPS, r -> r.get("sourceType"), List.of(),
                (java.util.function.Function<Detection, Object>) (Detection d) ->
                    d.getSourceType() == null ? List.of() : List.of(d.getSourceType().split(","))));
        }

        @Override public String entityName() { return "detection"; }
        @Override public Optional<AqlField<Detection>> field(String name) { return Optional.ofNullable(fields.get(name)); }
        @Override public List<AqlField<Detection>> defaultSearchFields() { return List.of(fields.get("title")); }
        @Override public List<AqlField<Detection>> allFields() { return List.copyOf(fields.values()); }
    }

    private final FixtureRegistry registry = new FixtureRegistry();

    private Detection detection(String title, int priority, int occurrenceCount, OffsetDateTime createdAt) {
        Detection d = new Detection();
        d.setTitle(title);
        d.setPriority((short) priority);
        d.setOccurrenceCount(occurrenceCount);
        d.setCreatedAt(createdAt);
        return d;
    }

    private boolean eval(Detection d, String aql) {
        AqlNode node = AqlParser.parse(aql);
        return AqlInMemoryEvaluator.matches(d, node, registry);
    }

    @Test
    void stringEqualityIsCaseInsensitive() {
        Detection d = detection("SQL Injection", 0, 1, OffsetDateTime.now());
        assertTrue(eval(d, "title == \"sql injection\""));
        assertFalse(eval(d, "title == \"xss\""));
    }

    @Test
    void stringContains() {
        Detection d = detection("Reflected XSS in login form", 0, 1, OffsetDateTime.now());
        assertTrue(eval(d, "title ~= xss"));
        assertFalse(eval(d, "title ~= sqli"));
    }

    @Test
    void priorityEqualityAndRange() {
        Detection d = detection("t", 1, 1, OffsetDateTime.now());
        assertTrue(eval(d, "priority == P1"));
        assertTrue(eval(d, "priority <= P2"));
        assertFalse(eval(d, "priority <= P0"));
    }

    @Test
    void priorityListMembership() {
        Detection d = detection("t", 2, 1, OffsetDateTime.now());
        assertTrue(eval(d, "priority == [P0,P1,P2]"));
        assertFalse(eval(d, "priority == [P0,P1]"));
        assertTrue(eval(d, "priority != [P0,P1]"));
    }

    @Test
    void inKeywordBehavesLikeEqualsWithList() {
        Detection d = detection("t", 2, 1, OffsetDateTime.now());
        assertTrue(eval(d, "priority IN [P0,P1,P2]"));
        assertFalse(eval(d, "priority IN [P0,P1]"));
        assertEquals(eval(d, "priority == [P0,P2]"), eval(d, "priority IN [P0,P2]"));
    }

    @Test
    void hasOperatorChecksArrayMembership() {
        Detection d = detection("t", 0, 1, OffsetDateTime.now());
        d.setSourceType("linux,web");
        assertTrue(eval(d, "tags HAS linux"));
        assertTrue(eval(d, "tags HAS web"));
        assertFalse(eval(d, "tags HAS windows"));
    }

    @Test
    void hasOperatorIsCaseInsensitive() {
        Detection d = detection("t", 0, 1, OffsetDateTime.now());
        d.setSourceType("Linux");
        assertTrue(eval(d, "tags HAS linux"));
    }

    @Test
    void hasOperatorOnAnEmptyArrayNeverMatches() {
        Detection d = detection("t", 0, 1, OffsetDateTime.now());
        d.setSourceType(null);
        assertFalse(eval(d, "tags HAS anything"));
    }

    @Test
    void numberComparisons() {
        Detection d = detection("t", 0, 5, OffsetDateTime.now());
        assertTrue(eval(d, "occurrenceCount >= 5"));
        assertTrue(eval(d, "occurrenceCount > 4"));
        assertFalse(eval(d, "occurrenceCount > 5"));
    }

    @Test
    void dateComparisonsAgainstRelativeNow() {
        Detection d = detection("t", 0, 1, OffsetDateTime.now().minusHours(1));
        assertTrue(eval(d, "createdAt >= now-1d"));
        assertFalse(eval(d, "createdAt <= now-1d"));
        assertTrue(eval(d, "createdAt < now"));
    }

    @Test
    void andOrNotCompose() {
        Detection d = detection("Critical bug", 0, 3, OffsetDateTime.now());
        assertTrue(eval(d, "priority == P0 AND occurrenceCount >= 3"));
        assertFalse(eval(d, "priority == P0 AND occurrenceCount >= 4"));
        assertTrue(eval(d, "priority == P4 OR occurrenceCount >= 3"));
        assertTrue(eval(d, "NOT priority == P1"));
    }

    @Test
    void bareTermSearchesDefaultSearchFields() {
        Detection d = detection("Reflected XSS in login form", 0, 1, OffsetDateTime.now());
        assertTrue(eval(d, "xss"));
        assertFalse(eval(d, "sqli"));
    }

    @Test
    void kbMaterializedFieldIsRejectedNotSilentlyWrong() {
        Detection d = detection("t", 0, 1, OffsetDateTime.now());
        AqlNode node = AqlParser.parse("kevListed == true");
        var ex = assertThrows(AqlCompileException.class, () -> AqlInMemoryEvaluator.matches(d, node, registry));
        assertTrue(ex.getMessage().contains("cannot be evaluated in-memory"));
    }

    @Test
    void columnFieldWithoutARegisteredResolverIsRejected() {
        Detection d = detection("t", 0, 1, OffsetDateTime.now());
        AqlNode node = AqlParser.parse("noResolver == foo");
        var ex = assertThrows(AqlCompileException.class, () -> AqlInMemoryEvaluator.matches(d, node, registry));
        assertTrue(ex.getMessage().contains("no in-memory value resolver"));
    }

    @Test
    void unsupportedFieldNameStillThrowsFieldNotFound() {
        Detection d = detection("t", 0, 1, OffsetDateTime.now());
        AqlNode node = AqlParser.parse("notAField == foo");
        assertThrows(AqlFieldNotFoundException.class, () -> AqlInMemoryEvaluator.matches(d, node, registry));
    }
}
