package de.axa.oraclecompare;

import java.util.Locale;

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
                char open = sql.charAt(quote + 1);
                char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
                int end = sql.indexOf("" + close + '\'', quote + 2);
                i = end < 0 ? sql.length() : end + 2;
                previousDot = false;
            } else if (c == '\'') {
                i = quotedEnd(sql, i, '\'');
                previousDot = false;
            } else if (c == '@') {
                // Auch unquotierte globale Link-Namen können aus mehreren Punktsegmenten bestehen.
                i = qualifiedIdentifierEnd(sql, i + 1);
                previousDot = false;
            } else if (isIdentifierStart(c)) {
                String token;
                i = identifierEnd(sql, i);
                if (c == '"') {
                    token = sql.substring(start + 1, i - 1).replace("\"\"", "\"");
                } else {
                    token = sql.substring(start, i).toUpperCase(Locale.ROOT);
                }
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
