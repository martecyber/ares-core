package com.martecyber.ares.aql.parser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Hand-written recursive-descent parser over {@link AqlLexer}'s tokens, implementing the AQL
 * grammar from the implementation plan:
 *
 * <pre>
 * query      := orExpr
 * orExpr     := andExpr (OR andExpr)*
 * andExpr    := notExpr ([AND] notExpr)*        # implicit AND between adjacent terms
 * notExpr    := (NOT | "-")? primary
 * primary    := "(" orExpr ")" | field OP value | field IN list | field HAS scalar | bareTerm
 * OP         := "==" | "~=" | "!=" | "&gt;" | "&gt;=" | "&lt;" | "&lt;="   # Wireshark-style,
 *                                                                          # standalone tokens
 * list       := "[" scalar ("," scalar)* "]"
 * </pre>
 *
 * Field-vs-bare-term is resolved with one token of lookahead: a WORD followed immediately by a
 * comparison operator token is a field; otherwise it's a bare (free-text) term. {@code IN}/{@code
 * HAS} are two more ways a field comparison can start, handled slightly differently from each
 * other: {@code IN} is recognized positionally (a WORD spelled "in" is only an operator when
 * immediately followed by "["), so plain "in" keeps working as an ordinary bare term everywhere
 * else; {@code HAS} has no such bracket to anchor on (its right-hand side is a bare scalar, not a
 * list) so it's a genuine reserved lexer keyword instead, same tradeoff already made for AND/OR/
 * NOT — see {@link AqlLexer}.
 */
public class AqlParser {

    private static final Set<AqlTokenType> COMPARISON_OPS = EnumSet.of(
        AqlTokenType.EQ, AqlTokenType.TILDE_EQ, AqlTokenType.NEQ,
        AqlTokenType.GT, AqlTokenType.GTE, AqlTokenType.LT, AqlTokenType.LTE);

    private static final Set<AqlTokenType> PRIMARY_START = EnumSet.of(
        AqlTokenType.WORD, AqlTokenType.STRING, AqlTokenType.LPAREN,
        AqlTokenType.NOT, AqlTokenType.MINUS);

    private final List<AqlToken> tokens;
    private int pos = 0;

    private AqlParser(List<AqlToken> tokens) {
        this.tokens = tokens;
    }

    public static AqlNode parse(String input) {
        AqlParser parser = new AqlParser(new AqlLexer(input).tokenize());
        AqlNode node = parser.parseOr();
        parser.expect(AqlTokenType.EOF);
        return node;
    }

    private AqlNode parseOr() {
        List<AqlNode> operands = new ArrayList<>();
        operands.add(parseAnd());
        while (peek().type() == AqlTokenType.OR) {
            advance();
            operands.add(parseAnd());
        }
        return operands.size() == 1 ? operands.get(0) : new AqlNode.Or(operands);
    }

    private AqlNode parseAnd() {
        List<AqlNode> operands = new ArrayList<>();
        operands.add(parseNot());
        while (true) {
            AqlTokenType t = peek().type();
            if (t == AqlTokenType.AND) {
                advance();
                operands.add(parseNot());
            } else if (PRIMARY_START.contains(t)) {
                operands.add(parseNot());
            } else {
                break;
            }
        }
        return operands.size() == 1 ? operands.get(0) : new AqlNode.And(operands);
    }

    private AqlNode parseNot() {
        if (peek().type() == AqlTokenType.NOT || peek().type() == AqlTokenType.MINUS) {
            advance();
            return new AqlNode.Not(parsePrimary());
        }
        return parsePrimary();
    }

    private AqlNode parsePrimary() {
        AqlToken tok = peek();
        if (tok.type() == AqlTokenType.LPAREN) {
            advance();
            AqlNode inner = parseOr();
            expect(AqlTokenType.RPAREN);
            return inner;
        }
        if (tok.type() == AqlTokenType.STRING) {
            advance();
            return new AqlNode.BareTerm(tok.text(), true);
        }
        if (tok.type() == AqlTokenType.WORD) {
            advance();
            if (COMPARISON_OPS.contains(peek().type())) {
                AqlToken opTok = advance();
                AqlOperator op = AqlOperator.fromToken(opTok.type());
                return new AqlNode.Comparison(tok.text(), op, parseValue());
            }
            // "IN" is recognized positionally, not as a lexer keyword (see AqlLexer) — only when
            // the WORD spelled "in" is immediately followed by '[' does it count as the IN
            // operator, so a lone "in" (e.g. inside free-text like "man in the middle") keeps
            // parsing as an ordinary bare term exactly as before.
            if (peek().type() == AqlTokenType.WORD && peek().text().equalsIgnoreCase("IN")
                && peekAhead(1).type() == AqlTokenType.LBRACKET) {
                advance();
                return new AqlNode.Comparison(tok.text(), AqlOperator.IN, parseValue());
            }
            if (peek().type() == AqlTokenType.HAS) {
                advance();
                return new AqlNode.Comparison(tok.text(), AqlOperator.HAS, parseScalar());
            }
            return new AqlNode.BareTerm(tok.text(), false);
        }
        throw new AqlParseException(
            "Expected a term, field comparison, or '(' but found '" + tok.text() + "'", tok.position());
    }

    private AqlValue parseValue() {
        if (peek().type() == AqlTokenType.LBRACKET) {
            advance();
            List<AqlValue.Scalar> items = new ArrayList<>();
            items.add(parseScalar());
            while (peek().type() == AqlTokenType.COMMA) {
                advance();
                items.add(parseScalar());
            }
            expect(AqlTokenType.RBRACKET);
            return new AqlValue.ListValue(items);
        }
        return parseScalar();
    }

    private AqlValue.Scalar parseScalar() {
        AqlToken tok = peek();
        if (tok.type() == AqlTokenType.STRING) {
            advance();
            return new AqlValue.Scalar(tok.text(), true);
        }
        // AND/OR/NOT/HAS are reserved as leading keywords but remain usable as literal values,
        // e.g. status:not, since the lexer classifies them by spelling alone.
        if (tok.type() == AqlTokenType.WORD || tok.type() == AqlTokenType.AND
            || tok.type() == AqlTokenType.OR || tok.type() == AqlTokenType.NOT
            || tok.type() == AqlTokenType.HAS) {
            advance();
            return new AqlValue.Scalar(tok.text(), false);
        }
        throw new AqlParseException("Expected a value but found '" + tok.text() + "'", tok.position());
    }

    private AqlToken peek() {
        return tokens.get(pos);
    }

    /** Looks {@code offset} tokens past the current position without consuming anything — clamped
     *  to the final EOF token so callers never index past the end. */
    private AqlToken peekAhead(int offset) {
        int i = Math.min(pos + offset, tokens.size() - 1);
        return tokens.get(i);
    }

    private AqlToken advance() {
        AqlToken tok = tokens.get(pos);
        if (tok.type() != AqlTokenType.EOF) {
            pos++;
        }
        return tok;
    }

    private void expect(AqlTokenType type) {
        AqlToken tok = peek();
        if (tok.type() != type) {
            throw new AqlParseException("Expected '" + type + "' but found '" + tok.text() + "'", tok.position());
        }
        advance();
    }
}
