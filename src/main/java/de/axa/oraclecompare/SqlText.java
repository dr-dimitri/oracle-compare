package de.axa.oraclecompare;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** SQL-Bezeichner und lexikalisches Schema-Remapping ohne Änderungen an Literalen/Kommentaren. */
final class SqlText {
    private SqlText() { }

    /** Erwartet einen Dictionary-Namen (ohne äußere Anführungszeichen). */
    static String identifier(String name) {
        if (name == null || name.isBlank() || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Ungültiger Oracle-Bezeichner: " + name);
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    static String qualified(String schema, String name) {
        return identifier(schema) + "." + identifier(name);
    }

    /**
     * Ermittelt lokale Sequenzen aus NEXTVAL-/CURRVAL-Ausdrücken eines Spalten-Defaults.
     * Unqualifizierte Namen gehören zum angegebenen Schema; quotierte Namen behalten ihre
     * Schreibweise. Literale, Kommentare und Referenzen über Datenbank-Links zählen nicht.
     */
    static Set<String> localSequenceReferences(String expression, String schema) {
        if (expression == null) return Set.of();
        Set<String> sequences = new HashSet<>();
        int i = 0;
        while ((i = skipTrivia(expression, i)) < expression.length()) {
            char c = expression.charAt(i);
            int quote = alternativeQuoteOffset(expression, i);
            if (quote >= 0) {
                i = alternativeQuotedEnd(expression, quote);
            } else if (c == '\'') {
                i = quotedEnd(expression, i, '\'');
            } else if (c == '@') {
                i = qualifiedIdentifierEnd(expression, i + 1);
            } else if (isIdentifierStart(c)) {
                List<String> parts = new ArrayList<>();
                while (true) {
                    int end = identifierEnd(expression, i);
                    parts.add(identifierValue(expression, i, end));
                    i = skipTrivia(expression, end);
                    if (i >= expression.length() || expression.charAt(i) != '.') break;
                    int next = skipTrivia(expression, i + 1);
                    if (next >= expression.length() || !isIdentifierStart(expression.charAt(next))) break;
                    i = next;
                }
                if (i < expression.length() && expression.charAt(i) == '@') {
                    i = qualifiedIdentifierEnd(expression, i + 1);
                    continue;
                }
                if ((parts.size() == 2 || parts.size() == 3 && parts.get(0).equals(schema))
                        && Set.of("NEXTVAL", "CURRVAL").contains(parts.get(parts.size() - 1))) {
                    sequences.add(parts.get(parts.size() - 2));
                }
            } else {
                i++;
            }
        }
        return Set.copyOf(sequences);
    }

    /**
     * Remappt qualifizierte Schema-Bezeichner in Defaults, View-Texten und Indexausdrücken.
     * Einfache, nationale und q-quotierte Literale sowie beide Kommentarformen bleiben erhalten.
     * Datenbank-Link-Namen und die über einen Link referenzierten entfernten Schemata bleiben
     * unverändert: Das Remapping gilt ausschließlich für Objekte in der lokalen Datenbank.
     * Der Referenzschemaname darf im SQL nicht gleichzeitig als Tabellenalias verwendet werden.
     */
    static String remap(String sql, String source, String target) {
        if (source.equals(target)) return sql;
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        boolean previousDot = false;
        while (i < sql.length()) {
            int start = i;
            char c = sql.charAt(i);
            if (sql.startsWith("--", i)) {
                i = sql.indexOf('\n', i);
                if (i < 0) i = sql.length();
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else if (alternativeQuoteOffset(sql, i) >= 0) {
                int quote = alternativeQuoteOffset(sql, i);
                i = alternativeQuotedEnd(sql, quote);
                previousDot = false;
            } else if (c == '\'') {
                i = quotedEnd(sql, i, '\'');
                previousDot = false;
            } else if (c == '@') {
                // Auch unquotierte globale Link-Namen können aus mehreren Punktsegmenten bestehen.
                i = qualifiedIdentifierEnd(sql, i + 1);
                previousDot = false;
            } else if (isIdentifierStart(c)) {
                i = identifierEnd(sql, i);
                String token = identifierValue(sql, start, i);
                int next = skipTrivia(sql, i);
                boolean remap = !previousDot && token.equals(source)
                        && next < sql.length() && sql.charAt(next) == '.'
                        && !isRemoteReference(sql, start);
                previousDot = false;
                if (remap) {
                    out.append(identifier(target));
                    continue;
                }
            } else {
                i++;
                if (!Character.isWhitespace(c)) previousDot = c == '.';
            }
            out.append(sql, start, i);
        }
        return out.toString();
    }

    /** Erkennt den Link auch hinter mehrteiligen Namen wie SCHEMA.PACKAGE.PROCEDURE@LINK. */
    private static boolean isRemoteReference(String sql, int start) {
        int next = skipTrivia(sql, qualifiedIdentifierEnd(sql, start));
        return next < sql.length() && sql.charAt(next) == '@';
    }

    /** Liest eine Punktfolge von Bezeichnern und erhält dabei Kommentare und Leerraum im SQL. */
    private static int qualifiedIdentifierEnd(String sql, int start) {
        int next = skipTrivia(sql, start);
        if (next >= sql.length() || !isIdentifierStart(sql.charAt(next))) return start;
        int end = identifierEnd(sql, next);
        while (true) {
            next = skipTrivia(sql, end);
            if (next >= sql.length() || sql.charAt(next) != '.') return end;
            next = skipTrivia(sql, next + 1);
            if (next >= sql.length() || !isIdentifierStart(sql.charAt(next))) return end;
            end = identifierEnd(sql, next);
        }
    }

    private static boolean isIdentifierStart(char c) {
        return c == '"' || Character.isLetter(c) || c == '_' || c == '$' || c == '#';
    }

    private static int identifierEnd(String text, int start) {
        if (text.charAt(start) == '"') return quotedEnd(text, start, '"');
        int i = start + 1;
        while (i < text.length()
                && (Character.isLetterOrDigit(text.charAt(i)) || "_$#".indexOf(text.charAt(i)) >= 0)) i++;
        return i;
    }

    private static int alternativeQuoteOffset(String text, int start) {
        int q = start;
        if (text.charAt(q) == 'n' || text.charAt(q) == 'N') q++;
        return q + 2 < text.length() && (text.charAt(q) == 'q' || text.charAt(q) == 'Q')
                && text.charAt(q + 1) == '\'' ? q + 1 : -1;
    }

    private static int alternativeQuotedEnd(String text, int quote) {
        char open = text.charAt(quote + 1);
        char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
        int end = text.indexOf("" + close + '\'', quote + 2);
        return end < 0 ? text.length() : end + 2;
    }

    private static String identifierValue(String text, int start, int end) {
        return text.charAt(start) == '"' ? text.substring(start + 1, end - 1).replace("\"\"", "\"")
                : text.substring(start, end).toUpperCase(Locale.ROOT);
    }

    private static int quotedEnd(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i++) == quote) {
                if (i < text.length() && text.charAt(i) == quote) i++;
                else return i;
            }
        }
        return i;
    }

    private static int skipTrivia(String text, int start) {
        int i = start;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) i++;
            else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) return text.length();
                i = end + 2;
            } else if (text.startsWith("--", i)) {
                int end = text.indexOf('\n', i + 2);
                if (end < 0) return text.length();
                i = end + 1;
            } else break;
        }
        return i;
    }
}
