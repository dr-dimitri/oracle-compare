package de.axa.oraclecompare;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Für die SQL-Erzeugung benötigte Fähigkeiten der Zieldatenbank. Explizite COLLATE-Klauseln
 * benötigen MAX_STRING_SIZE=EXTENDED und COMPATIBLE ab 12.2 außerhalb von CDB$ROOT.
 * Im CDB-Root gilt MAX_STRING_SIZE für Collations unabhängig vom Parameterwert als STANDARD.
 * Die Abfrage verändert weder
 * die Session noch die vom Aufrufer verwaltete Connection und benötigt Leserechte auf V$PARAMETER.
 */
record OracleDatabaseCapabilities(boolean supportsDataBoundCollation) {
    private static final String PARAMETERS_SQL = """
            SELECT name, value, SYS_CONTEXT('USERENV', 'CON_NAME') AS container_name FROM v$parameter
            WHERE name IN ('max_string_size', 'compatible')
            """;

    /**
     * Liest die wirksamen Zielparameter. Fehlende oder unbekannte Werte werden ausdrücklich
     * abgelehnt, damit die Ausgabe nicht auf einer ungesicherten Annahme über die Datenbank beruht.
     */
    static OracleDatabaseCapabilities read(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Map<String, String> parameters = new HashMap<>();
        String container = null;
        try (var statement = connection.prepareStatement(PARAMETERS_SQL);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString("NAME");
                String value = rows.getString("VALUE");
                String rowContainer = rows.getString("CONTAINER_NAME");
                if (rowContainer == null || rowContainer.isBlank()) {
                    throw new SQLException("Container-/Datenbankname der Zielsitzung fehlt in SYS_CONTEXT('USERENV', 'CON_NAME').");
                }
                if (container != null && !container.equals(rowContainer)) {
                    throw new SQLException("Inkonsistenter Containername während der Zielparameterabfrage.");
                }
                container = rowContainer;
                if (name == null || !name.equals("max_string_size") && !name.equals("compatible")) {
                    throw new SQLException("Unbekannter Zielparameter in V$PARAMETER: " + name);
                }
                if (parameters.containsKey(name)) {
                    throw new SQLException("Mehrfacher Zielparameter in V$PARAMETER: " + name);
                }
                if (value == null || value.isBlank()) {
                    throw new SQLException("Leerer Zielparameter in V$PARAMETER: " + name);
                }
                parameters.put(name, value.strip());
            }
        }
        String maxStringSize = required(parameters, "max_string_size").toUpperCase(Locale.ROOT);
        if (!maxStringSize.equals("STANDARD") && !maxStringSize.equals("EXTENDED")) {
            throw new SQLException("Ungültiges MAX_STRING_SIZE der Zieldatenbank: " + maxStringSize);
        }
        String compatible = required(parameters, "compatible");
        if (!compatible.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+){0,3}")) {
            throw new SQLException("Ungültiges COMPATIBLE der Zieldatenbank: " + compatible);
        }
        String[] version = compatible.split("\\.");
        int majorComparison = new BigInteger(version[0]).compareTo(BigInteger.valueOf(12));
        boolean compatibleVersion = majorComparison > 0 || majorComparison == 0
                && new BigInteger(version[1]).compareTo(BigInteger.valueOf(2)) >= 0;
        return new OracleDatabaseCapabilities(!"CDB$ROOT".equals(container)
                && maxStringSize.equals("EXTENDED") && compatibleVersion);
    }

    /** Meldet fehlende Parameter mit dem erforderlichen Dictionary-Zugriff als Kontext. */
    private static String required(Map<String, String> parameters, String name) throws SQLException {
        String value = parameters.get(name);
        if (value == null) {
            throw new SQLException("Zielparameter fehlt in V$PARAMETER: " + name);
        }
        return value;
    }
}
