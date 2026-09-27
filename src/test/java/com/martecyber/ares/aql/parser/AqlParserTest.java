package com.martecyber.ares.aql.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AqlParserTest {

    @Test
    void simpleEqualityComparison() {
        AqlNode node = AqlParser.parse("severity == critical");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals("severity", cmp.field());
        assertEquals(AqlOperator.EQ, cmp.operator());
        assertEquals(new AqlValue.Scalar("critical", false), cmp.value());
    }

    @Test
    void equalityToleratesNoSurroundingWhitespace() {
        AqlNode node = AqlParser.parse("severity==critical");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals("severity", cmp.field());
        assertEquals(AqlOperator.EQ, cmp.operator());
    }

    @Test
    void quotedContainsComparison() {
        AqlNode node = AqlParser.parse("title ~= \"sql injection\"");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals("title", cmp.field());
        assertEquals(AqlOperator.CONTAINS, cmp.operator());
        assertEquals(new AqlValue.Scalar("sql injection", true), cmp.value());
    }

    @Test
    void numericComparisonOperators() {
        assertEquals(AqlOperator.GTE, comparisonOf("score >= 7.0").operator());
        assertEquals(AqlOperator.LTE, comparisonOf("score <= 7.0").operator());
        assertEquals(AqlOperator.GT, comparisonOf("score > 7").operator());
        assertEquals(AqlOperator.LT, comparisonOf("score < 7").operator());
        assertEquals(AqlOperator.NEQ, comparisonOf("status != archived").operator());
    }

    @Test
    void listValue() {
        AqlNode node = AqlParser.parse("severity == [critical,high]");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        var list = assertInstanceOf(AqlValue.ListValue.class, cmp.value());
        assertEquals(
            List.of(new AqlValue.Scalar("critical", false), new AqlValue.Scalar("high", false)),
            list.items());
    }

    @Test
    void inOperatorWithListValue() {
        AqlNode node = AqlParser.parse("severity IN [critical, high]");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals("severity", cmp.field());
        assertEquals(AqlOperator.IN, cmp.operator());
        var list = assertInstanceOf(AqlValue.ListValue.class, cmp.value());
        assertEquals(
            List.of(new AqlValue.Scalar("critical", false), new AqlValue.Scalar("high", false)),
            list.items());
    }

    @Test
    void inOperatorIsCaseInsensitive() {
        assertEquals(AqlOperator.IN, comparisonOf("severity in [critical]").operator());
        assertEquals(AqlOperator.IN, comparisonOf("severity In [critical]").operator());
    }

    @Test
    void hasOperatorWithScalarValue() {
        AqlNode node = AqlParser.parse("cwes HAS \"CWE-79\"");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals("cwes", cmp.field());
        assertEquals(AqlOperator.HAS, cmp.operator());
        assertEquals(new AqlValue.Scalar("CWE-79", true), cmp.value());
    }

    @Test
    void hasOperatorIsCaseInsensitive() {
        assertEquals(AqlOperator.HAS, comparisonOf("cwes has CWE-79").operator());
        assertEquals(AqlOperator.HAS, comparisonOf("cwes Has CWE-79").operator());
    }

    @Test
    void hasOperatorRejectsAListValue() {
        assertThrows(AqlParseException.class, () -> AqlParser.parse("cwes HAS [a, b]"));
    }

    // The one real lexical-compatibility risk of adding IN/HAS: bare free-text queries that
    // happen to contain the literal words "in"/"has" must keep parsing exactly as before. IN is
    // recognized positionally (only when immediately followed by '['), so it's unaffected; HAS is
    // a genuine reserved keyword (like AND/OR/NOT already are), so a lone "has" can no longer
    // stand as its own bare term — this is a deliberate, not accidental, grammar change, locked in
    // by the two tests below.

    @Test
    void wordInWithoutABracketStaysAPlainBareTerm() {
        AqlNode node = AqlParser.parse("man in the middle");
        var and = assertInstanceOf(AqlNode.And.class, node);
        assertEquals(4, and.operands().size());
        assertEquals("man", ((AqlNode.BareTerm) and.operands().get(0)).text());
        assertEquals("in", ((AqlNode.BareTerm) and.operands().get(1)).text());
        assertEquals("the", ((AqlNode.BareTerm) and.operands().get(2)).text());
        assertEquals("middle", ((AqlNode.BareTerm) and.operands().get(3)).text());
    }

    @Test
    void threeBareWordsWithHasInTheMiddleParsesAsAHasComparisonNotThreeBareTerms() {
        // Deliberate behavior change, same tradeoff already made for AND/OR/NOT: since HAS is a
        // reserved keyword, "word1 has word2" always matches the "field HAS value" production —
        // there's no remaining syntax for three independent bare terms where the middle one is
        // literally "has" (unlike "in", which stays ambiguity-free because it requires a
        // following '[' to count as an operator — see wordInWithoutABracketStaysAPlainBareTerm).
        AqlNode node = AqlParser.parse("device has access");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals("device", cmp.field());
        assertEquals(AqlOperator.HAS, cmp.operator());
        assertEquals(new AqlValue.Scalar("access", false), cmp.value());
    }

    @Test
    void aLoneHasWithNoPrecedingFieldIsRejected() {
        assertThrows(AqlParseException.class, () -> AqlParser.parse("has"));
    }

    @Test
    void reservedWordHasUsableAsAScalarValueAfterAnOperator() {
        AqlNode node = AqlParser.parse("status == has");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals(new AqlValue.Scalar("has", false), cmp.value());
    }

    @Test
    void implicitAndBetweenTerms() {
        AqlNode node = AqlParser.parse("severity == critical status == new");
        var and = assertInstanceOf(AqlNode.And.class, node);
        assertEquals(2, and.operands().size());
    }

    @Test
    void explicitAndIsEquivalentToImplicit() {
        assertEquals(AqlParser.parse("severity == critical status == new"),
            AqlParser.parse("severity == critical AND status == new"));
    }

    @Test
    void orHasLowerPrecedenceThanAnd() {
        // a AND b OR c AND d  ==  (a AND b) OR (c AND d)
        AqlNode node = AqlParser.parse("a == 1 AND b == 2 OR c == 3 AND d == 4");
        var or = assertInstanceOf(AqlNode.Or.class, node);
        assertEquals(2, or.operands().size());
        assertInstanceOf(AqlNode.And.class, or.operands().get(0));
        assertInstanceOf(AqlNode.And.class, or.operands().get(1));
    }

    @Test
    void groupingOverridesPrecedence() {
        AqlNode node = AqlParser.parse("(severity == critical OR severity == high) AND NOT status == [archived]");
        var and = assertInstanceOf(AqlNode.And.class, node);
        assertEquals(2, and.operands().size());
        assertInstanceOf(AqlNode.Or.class, and.operands().get(0));
        var not = assertInstanceOf(AqlNode.Not.class, and.operands().get(1));
        var cmp = assertInstanceOf(AqlNode.Comparison.class, not.operand());
        assertEquals("status", cmp.field());
    }

    @Test
    void minusIsShorthandForNot() {
        AqlNode node = AqlParser.parse("kev == true AND -status == archived");
        var and = assertInstanceOf(AqlNode.And.class, node);
        assertInstanceOf(AqlNode.Not.class, and.operands().get(1));
    }

    @Test
    void dottedFieldPath() {
        AqlNode node = AqlParser.parse("metadata.osVersion == \"Ubuntu 22.04\"");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals("metadata.osVersion", cmp.field());
        assertEquals(new AqlValue.Scalar("Ubuntu 22.04", true), cmp.value());
    }

    @Test
    void crossStoreFieldPath() {
        AqlNode node = AqlParser.parse("cve.kevListed == true");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals("cve.kevListed", cmp.field());
    }

    @Test
    void relativeDateLiteralLexesAsSingleValue() {
        AqlNode node = AqlParser.parse("createdAt >= now-7d");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals(new AqlValue.Scalar("now-7d", false), cmp.value());
    }

    @Test
    void relativeDateLiteralSupportsPlusAndOtherUnits() {
        AqlNode node = AqlParser.parse("dueDate <= now+3h");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals(new AqlValue.Scalar("now+3h", false), cmp.value());
    }

    @Test
    void bareTermIsFreeTextSearch() {
        AqlNode node = AqlParser.parse("log4j");
        var term = assertInstanceOf(AqlNode.BareTerm.class, node);
        assertEquals("log4j", term.text());
        assertFalse(term.quoted());
    }

    @Test
    void reservedWordUsableAsQuotedValue() {
        AqlNode node = AqlParser.parse("status == \"not\"");
        var cmp = assertInstanceOf(AqlNode.Comparison.class, node);
        assertEquals(new AqlValue.Scalar("not", true), cmp.value());
    }

    @Test
    void missingValueAfterOperatorIsRejected() {
        AqlParseException ex = assertThrows(AqlParseException.class, () -> AqlParser.parse("severity =="));
        assertTrue(ex.getMessage().contains("value"));
    }

    @Test
    void unterminatedStringIsRejected() {
        assertThrows(AqlParseException.class, () -> AqlParser.parse("title ~= \"unterminated"));
    }

    @Test
    void unmatchedParenIsRejected() {
        assertThrows(AqlParseException.class, () -> AqlParser.parse("(severity == critical"));
    }

    @Test
    void trailingGarbageAfterValidQueryIsRejected() {
        assertThrows(AqlParseException.class, () -> AqlParser.parse("severity == critical )"));
    }

    @Test
    void unexpectedCharacterIsRejected() {
        assertThrows(AqlParseException.class, () -> AqlParser.parse("severity == critical & status == new"));
    }

    @Test
    void bareEqualsSignIsRejected() {
        AqlParseException ex = assertThrows(AqlParseException.class, () -> AqlParser.parse("severity = critical"));
        assertTrue(ex.getMessage().contains("=="));
    }

    @Test
    void bareTildeIsRejected() {
        AqlParseException ex = assertThrows(AqlParseException.class, () -> AqlParser.parse("title ~ sql"));
        assertTrue(ex.getMessage().contains("~="));
    }

    @Test
    void colonSyntaxIsRejectedWithAHelpfulMessage() {
        AqlParseException ex = assertThrows(AqlParseException.class, () -> AqlParser.parse("severity:critical"));
        assertTrue(ex.getMessage().contains("=="));
    }

    private AqlNode.Comparison comparisonOf(String query) {
        return assertInstanceOf(AqlNode.Comparison.class, AqlParser.parse(query));
    }
}
