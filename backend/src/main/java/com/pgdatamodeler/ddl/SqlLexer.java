package com.pgdatamodeler.ddl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits PostgreSQL script text into statements of tokens. Understands {@code --} and nested {@code /* *}{@code /}
 * comments, single-quoted / {@code E''} strings, quoted identifiers, dollar quoting, psql meta-commands
 * ({@code \connect}) and the inline data of {@code COPY ... FROM stdin}.
 */
final class SqlLexer {

    enum Kind { WORD, QUOTED_IDENT, STRING, NUMBER, SYMBOL }

    /** {@code text} is the unescaped value for identifiers and strings, the raw text otherwise. */
    record Token(Kind kind, String text, int start, int end) {
        boolean isWord(String keyword) {
            return kind == Kind.WORD && text.equalsIgnoreCase(keyword);
        }

        boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }
    }

    record Statement(List<Token> tokens, int line) {
    }

    private final String src;
    private int pos;

    private SqlLexer(String src) {
        this.src = src;
    }

    static List<Statement> split(String sql) {
        return new SqlLexer(sql).run();
    }

    private List<Statement> run() {
        List<Statement> statements = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int startLine = 1;
        int depth = 0;
        while (true) {
            skipWhitespaceAndComments(current.isEmpty());
            if (pos >= src.length()) {
                break;
            }
            if (current.isEmpty()) {
                startLine = lineOf(pos);
            }
            Token t = next();
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth = Math.max(0, depth - 1);
            }
            if (t.isSymbol(";") && depth == 0) {
                if (!current.isEmpty()) {
                    statements.add(new Statement(List.copyOf(current), startLine));
                    if (isCopyFromStdin(current)) {
                        skipCopyData();
                    }
                }
                current.clear();
                continue;
            }
            current.add(t);
        }
        if (!current.isEmpty()) {
            statements.add(new Statement(List.copyOf(current), startLine));
        }
        return statements;
    }

    private int lineCountedTo;
    private int linesSoFar = 1;

    /** Line number of {@code offset}; counted incrementally because offsets only grow. */
    int lineOf(int offset) {
        if (offset < lineCountedTo) {
            lineCountedTo = 0;
            linesSoFar = 1;
        }
        for (int i = lineCountedTo; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                linesSoFar++;
            }
        }
        lineCountedTo = Math.max(lineCountedTo, Math.min(offset, src.length()));
        return linesSoFar;
    }

    private void skipWhitespaceAndComments(boolean atStatementStart) {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && peek(1) == '-') {
                skipLine();
            } else if (c == '/' && peek(1) == '*') {
                int level = 0;
                while (pos < src.length()) {
                    if (src.startsWith("/*", pos)) {
                        level++;
                        pos += 2;
                    } else if (src.startsWith("*/", pos)) {
                        level--;
                        pos += 2;
                        if (level == 0) {
                            break;
                        }
                    } else {
                        pos++;
                    }
                }
            } else if (c == '\\' && atStatementStart) {
                skipLine(); // psql meta-command such as \connect or \restrict
            } else {
                return;
            }
        }
    }

    private void skipLine() {
        while (pos < src.length() && src.charAt(pos) != '\n') {
            pos++;
        }
    }

    private static boolean isCopyFromStdin(List<Token> tokens) {
        return tokens.get(0).isWord("copy") && tokens.stream().anyMatch(t -> t.isWord("stdin"));
    }

    /** Skips the rows of {@code COPY ... FROM stdin;} up to and including the terminating {@code \.} line. */
    private void skipCopyData() {
        skipLine();
        while (pos < src.length()) {
            pos++; // newline
            int lineStart = pos;
            skipLine();
            if (src.substring(lineStart, pos).strip().equals("\\.")) {
                return;
            }
        }
    }

    private char peek(int ahead) {
        int i = pos + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private Token next() {
        int start = pos;
        char c = src.charAt(pos);
        if (c == '\'') {
            return new Token(Kind.STRING, readQuoted('\'', false), start, pos);
        }
        if ((c == 'E' || c == 'e') && peek(1) == '\'') {
            pos++;
            return new Token(Kind.STRING, readQuoted('\'', true), start, pos);
        }
        if ((c == 'B' || c == 'b' || c == 'X' || c == 'x' || c == 'N' || c == 'n') && peek(1) == '\'') {
            pos++;
            return new Token(Kind.STRING, readQuoted('\'', false), start, pos);
        }
        if ((c == 'U' || c == 'u') && peek(1) == '&' && (peek(2) == '\'' || peek(2) == '"')) {
            pos += 2;
            char q = src.charAt(pos);
            String value = readQuoted(q, false);
            return new Token(q == '"' ? Kind.QUOTED_IDENT : Kind.STRING, value, start, pos);
        }
        if (c == '"') {
            return new Token(Kind.QUOTED_IDENT, readQuoted('"', false), start, pos);
        }
        if (c == '$') {
            String body = tryDollarQuote();
            if (body != null) {
                return new Token(Kind.STRING, body, start, pos);
            }
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            return new Token(Kind.SYMBOL, src.substring(start, pos), start, pos);
        }
        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
            while (pos < src.length()) {
                char d = src.charAt(pos);
                if (Character.isDigit(d) || d == '.' || d == '_') {
                    pos++;
                } else if ((d == 'e' || d == 'E') && (Character.isDigit(peek(1))
                        || ((peek(1) == '+' || peek(1) == '-') && Character.isDigit(peek(2))))) {
                    pos += 2;
                } else {
                    break;
                }
            }
            return new Token(Kind.NUMBER, src.substring(start, pos), start, pos);
        }
        if (Character.isLetter(c) || c == '_') {
            while (pos < src.length()) {
                char d = src.charAt(pos);
                if (Character.isLetterOrDigit(d) || d == '_' || d == '$') {
                    pos++;
                } else {
                    break;
                }
            }
            return new Token(Kind.WORD, src.substring(start, pos), start, pos);
        }
        for (String op : new String[]{"::", "<>", "<=", ">=", "!=", "||", "->>", "->", "=>"}) {
            if (src.startsWith(op, pos)) {
                pos += op.length();
                return new Token(Kind.SYMBOL, op, start, pos);
            }
        }
        pos++;
        return new Token(Kind.SYMBOL, String.valueOf(c), start, pos);
    }

    private String readQuoted(char quote, boolean backslashEscapes) {
        StringBuilder sb = new StringBuilder();
        pos++; // opening quote
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (backslashEscapes && c == '\\' && pos + 1 < src.length()) {
                char e = src.charAt(pos + 1);
                sb.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> e;
                });
                pos += 2;
            } else if (c == quote) {
                if (peek(1) == quote) {
                    sb.append(quote);
                    pos += 2;
                } else {
                    pos++;
                    return sb.toString();
                }
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new IllegalArgumentException("Unterminated " + (quote == '"' ? "quoted identifier" : "string")
                + " starting on line " + lineOf(pos));
    }

    /** Reads {@code $tag$ ... $tag$} if one starts at {@code pos}; returns null (and does not move) otherwise. */
    private String tryDollarQuote() {
        int i = pos + 1;
        while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
            i++;
        }
        if (i >= src.length() || src.charAt(i) != '$' || (i > pos + 1 && Character.isDigit(src.charAt(pos + 1)))) {
            return null;
        }
        String tag = src.substring(pos, i + 1);
        int end = src.indexOf(tag, i + 1);
        if (end < 0) {
            throw new IllegalArgumentException("Unterminated dollar-quoted string " + tag + " on line " + lineOf(pos));
        }
        String body = src.substring(i + 1, end);
        pos = end + tag.length();
        return body;
    }
}
