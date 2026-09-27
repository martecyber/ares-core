package com.martecyber.ares.aql.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written tokenizer for the AQL grammar (see the AQL implementation plan for the EBNF).
 * A WORD must start with a letter/digit/underscore but may continue with '.', '-' or '+', which
 * lets "metadata.osVersion", "now-7d", "now+3h" and "2026-01-01" lex as single tokens while a
 * leading '-' (which never starts a legal WORD) is still free to be picked up as the NOT-shorthand
 * MINUS token. A leading '+' has no such special meaning and is simply illegal — it's only ever
 * valid mid-word, e.g. inside a relative-date literal.
 *
 * <p>Comparison operators are standalone (Wireshark-display-filter style), not colon-prefixed:
 * "==" (equality/IN), "~=" (contains), "!=", "&gt;", "&gt;=", "&lt;", "&lt;=". Whitespace around
 * an operator is always optional either way (every token boundary already tolerates it) — both
 * {@code priority == P0} and {@code priority==P0} lex identically.
 */
public class AqlLexer {

    private final String input;
    private int pos;

    public AqlLexer(String input) {
        this.input = input;
        this.pos = 0;
    }

    public List<AqlToken> tokenize() {
        List<AqlToken> tokens = new ArrayList<>();
        AqlToken token;
        do {
            token = next();
            tokens.add(token);
        } while (token.type() != AqlTokenType.EOF);
        return tokens;
    }

    private AqlToken next() {
        skipWhitespace();
        if (pos >= input.length()) {
            return new AqlToken(AqlTokenType.EOF, "", pos);
        }

        int start = pos;
        char c = input.charAt(pos);

        switch (c) {
            case '(': pos++; return new AqlToken(AqlTokenType.LPAREN, "(", start);
            case ')': pos++; return new AqlToken(AqlTokenType.RPAREN, ")", start);
            case '[': pos++; return new AqlToken(AqlTokenType.LBRACKET, "[", start);
            case ']': pos++; return new AqlToken(AqlTokenType.RBRACKET, "]", start);
            case ',': pos++; return new AqlToken(AqlTokenType.COMMA, ",", start);
            case '"': return readString(start);
            case '-': pos++; return new AqlToken(AqlTokenType.MINUS, "-", start);
            case '=':
                pos++;
                if (pos < input.length() && input.charAt(pos) == '=') {
                    pos++;
                    return new AqlToken(AqlTokenType.EQ, "==", start);
                }
                throw new AqlParseException("Expected '==' for equality — a bare '=' is not a valid AQL operator", start);
            case '~':
                pos++;
                if (pos < input.length() && input.charAt(pos) == '=') {
                    pos++;
                    return new AqlToken(AqlTokenType.TILDE_EQ, "~=", start);
                }
                throw new AqlParseException("Expected '~=' for contains — a bare '~' is not a valid AQL operator", start);
            case '!':
                pos++;
                if (pos < input.length() && input.charAt(pos) == '=') {
                    pos++;
                    return new AqlToken(AqlTokenType.NEQ, "!=", start);
                }
                throw new AqlParseException("Expected '!=' for not-equal — a bare '!' is not a valid AQL operator", start);
            case '>':
                pos++;
                if (pos < input.length() && input.charAt(pos) == '=') {
                    pos++;
                    return new AqlToken(AqlTokenType.GTE, ">=", start);
                }
                return new AqlToken(AqlTokenType.GT, ">", start);
            case '<':
                pos++;
                if (pos < input.length() && input.charAt(pos) == '=') {
                    pos++;
                    return new AqlToken(AqlTokenType.LTE, "<=", start);
                }
                return new AqlToken(AqlTokenType.LT, "<", start);
            case ':':
                throw new AqlParseException(
                    "':' is not a valid AQL operator — use '==' for equality or '~=' for contains", start);
            default:
                if (isWordStart(c)) {
                    return readWord(start);
                }
                throw new AqlParseException("Unexpected character '" + c + "'", start);
        }
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private boolean isWordStart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private boolean isWordContinue(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '+';
    }

    private AqlToken readWord(int start) {
        while (pos < input.length() && isWordContinue(input.charAt(pos))) {
            pos++;
        }
        String text = input.substring(start, pos);
        // "IN" is deliberately NOT a keyword here (unlike AND/OR/NOT/HAS) — it stays a plain WORD
        // and is recognized positionally by the parser only when immediately followed by '[', so
        // "in" keeps working as an ordinary free-text bare term everywhere else (see AqlParser).
        // "HAS" doesn't have an equivalent syntactic anchor on its right-hand side (a bare scalar,
        // not a bracketed list), so unlike IN it's reserved unconditionally — same tradeoff this
        // codebase already made for AND/OR/NOT, and "has" remains usable as a scalar VALUE after
        // an operator (see AqlParser.parseScalar), just not as a standalone bare term.
        AqlTokenType keyword = switch (text.toUpperCase(java.util.Locale.ROOT)) {
            case "AND" -> AqlTokenType.AND;
            case "OR" -> AqlTokenType.OR;
            case "NOT" -> AqlTokenType.NOT;
            case "HAS" -> AqlTokenType.HAS;
            default -> AqlTokenType.WORD;
        };
        return new AqlToken(keyword, text, start);
    }

    private AqlToken readString(int start) {
        pos++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= input.length()) {
                throw new AqlParseException("Unterminated string literal", start);
            }
            char c = input.charAt(pos);
            if (c == '"') {
                pos++;
                return new AqlToken(AqlTokenType.STRING, sb.toString(), start);
            }
            if (c == '\\' && pos + 1 < input.length()
                && (input.charAt(pos + 1) == '"' || input.charAt(pos + 1) == '\\')) {
                sb.append(input.charAt(pos + 1));
                pos += 2;
                continue;
            }
            sb.append(c);
            pos++;
        }
    }
}
