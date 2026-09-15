package de.axa.oraclecompare;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Kleiner JDBC-Ausführer für die Fixtures und die erzeugten SQL*Plus-Skripte dieses Tests.
 * SQL endet an einem Semikolon außerhalb von Literalen/Kommentaren, PL/SQL an einer eigenen
 * Slash-Zeile. SQL*Plus-Einstellungen werden nicht an JDBC gesendet. Dies ist kein allgemeiner
 * SQL*Plus-Ersatz; insbesondere werden keine Dateien eingebunden und keine Variablen ersetzt.
 */
final class OracleSqlScript {
    private static final Pattern BLOCK_START = Pattern.compile(
            "(?is)^(?:DECLARE\\b|BEGIN\\b|CREATE\\s+(?:OR\\s+REPLACE\\s+)?"
                    + "(?:(?:NON)?EDITIONABLE\\s+)?(?:PROCEDURE|FUNCTION|TRIGGER|PACKAGE)\\b)");

    private OracleSqlScript() { }

    /** Zerlegt zuerst das ganze Skript; Syntaxfehler des Ausführers verursachen keine Teil-DDL. */
    static List<String> statements(String script) throws IOException {
        String text = script.replace("\r\n", "\n").replace('\r', '\n');
        List<String> statements = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        StringBuilder significant = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (i == 0 || text.charAt(i - 1) == '\n') {
                int end = text.indexOf('\n', i);
                if (end < 0) end = text.length();
                String line = text.substring(i, end).strip();
                if (line.equals("/")) {
                    if (significant.toString().isBlank() || !BLOCK_START.matcher(significant.toString().strip()).find()) {
                        throw new IOException("Unerwarteter PL/SQL-Terminator ohne offenen PL/SQL-Block.");
                    }
                    statements.add(sql.toString().strip());
                    sql.setLength(0);
                    significant.setLength(0);
                    i = end < text.length() ? end + 1 : end;
                    continue;
                }
                if (significant.toString().isBlank() && isClientDirective(line)) {
                    sql.setLength(0);
                    significant.setLength(0);
                    i = end < text.length() ? end + 1 : end;
                    continue;
                }
            }
            int start = i;
            if (text.startsWith("--", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
                significant.append(' ');
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) throw new IOException("Nicht abgeschlossener SQL-Kommentar.");
                i = end + 2;
                significant.append(' ');
            } else if (alternativeQuoteOffset(text, i) >= 0) {
                int quote = alternativeQuoteOffset(text, i);
                char close = switch (text.charAt(quote + 1)) {
                    case '[' -> ']'; case '{' -> '}'; case '(' -> ')'; case '<' -> '>';
                    default -> text.charAt(quote + 1);
                };
                int end = text.indexOf("" + close + '\'', quote + 2);
                if (end < 0) throw new IOException("Nicht abgeschlossenes q-quotiertes SQL-Literal.");
                i = end + 2;
                significant.append('L');
            } else if (text.charAt(i) == '\'' || text.charAt(i) == '"') {
                i = quotedEnd(text, i);
                significant.append('L');
            } else if (text.charAt(i) == ';' && !BLOCK_START.matcher(significant.toString().strip()).find()) {
                if (!significant.toString().isBlank()) statements.add(sql.toString().strip());
                sql.setLength(0);
                significant.setLength(0);
                i++;
                continue;
            } else {
                significant.append(text.charAt(i++));
            }
            sql.append(text, start, i);
        }
        if (!significant.toString().isBlank()) {
            throw new IOException("Nicht abgeschlossene SQL-Anweisung (Semikolon bzw. PL/SQL-Slash fehlt).");
        }
        return List.copyOf(statements);
    }

    /** Führt jede bereits vollständig zerlegte Anweisung genau einmal aus und schließt nur Statements. */
    static void execute(Connection connection, List<String> statements) throws SQLException {
        for (int i = 0; i < statements.size(); i++) {
            try (var statement = connection.createStatement()) {
                statement.execute(statements.get(i));
            } catch (SQLException failure) {
                throw new SQLException("Integrationsskript, Anweisung " + (i + 1) + ": " + failure.getMessage(),
                        failure.getSQLState(), failure.getErrorCode(), failure);
            }
        }
    }

    /** Überspringt ausschließlich die vom Generator verwendeten SQL*Plus-Steuerzeilen. */
    private static boolean isClientDirective(String line) throws IOException {
        String normalized = line.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.equals("SET DEFINE OFF") || normalized.equals("SET SQLBLANKLINES ON")
                || normalized.equals("SET SQLTERMINATOR ON") || normalized.startsWith("PROMPT ")
                || normalized.equals("PROMPT")
                || normalized.equals("WHENEVER OSERROR EXIT FAILURE ROLLBACK")
                || normalized.equals("WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK")) return true;
        if (normalized.startsWith("SET ") || normalized.startsWith("WHENEVER ")
                || normalized.startsWith("@") || normalized.startsWith("SPOOL ")) {
            throw new IOException("Nicht unterstützte SQL*Plus-Steuerzeile: " + line);
        }
        return false;
    }

    private static int alternativeQuoteOffset(String text, int start) {
        if (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1))
                || "_$#".indexOf(text.charAt(start - 1)) >= 0)) return -1;
        int q = start;
        if (text.charAt(q) == 'n' || text.charAt(q) == 'N') q++;
        return q + 2 < text.length() && (text.charAt(q) == 'q' || text.charAt(q) == 'Q')
                && text.charAt(q + 1) == '\'' ? q + 1 : -1;
    }

    private static int quotedEnd(String text, int start) throws IOException {
        char quote = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i++) == quote) {
                if (i < text.length() && text.charAt(i) == quote) i++;
                else return i;
            }
        }
        throw new IOException("Nicht abgeschlossenes SQL-Literal bzw. gequoteter Bezeichner.");
    }
}
