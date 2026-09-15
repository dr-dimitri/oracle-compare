package de.axa.oraclecompare;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Explizit aufrufbarer Oracle-19c-Integrationstest mit zwei vom Aufrufer bereitgestellten
 * JDBC-Verbindungen. Die Verbindungen dürfen zu verschiedenen Datenbanken/PDBs gehören;
 * Schema- und Benutzernamen sind frei wählbar. Beide aktuellen Schemata müssen anfangs leer sein.
 *
 * <p>Der Test erstellt Fixtures, gleicht das Ziel an die Referenz an und verlangt danach einen
 * Abgleich ohne weitere Objekt-DDL. Zusätzlich prüft er Foreign Keys, Views und Partitionen.
 * Er wird nicht automatisch von Maven gestartet und öffnet selbst keine Verbindungen.</p>
 *
 * <p>Connection, Autocommit und Isolationseinstellungen bleiben beim Aufrufer. Oracle-DDL
 * führt trotzdem implizite Commits aus; ausschließlich eigene Verbindungen zu entbehrlichen
 * Testschema verwenden. Angelegte Testobjekte bleiben zur Untersuchung erhalten, auch im
 * Fehlerfall. Es werden keine Schemata/Benutzer/Directories erstellt oder gelöscht.</p>
 *
 * <p>Voraussetzungen: Metadaten-/DDL-Rechte des Comparators, Partitionierungsunterstützung,
 * verfügbare Quell-Tablespaces auch auf dem Ziel, READ/WRITE auf EXPORT_HOST sowie EXECUTE
 * auf UTL_FILE. Der Test erzeugt pro Lauf eine eigene Data-Pump-Datei in EXPORT_HOST auf
 * beiden Datenbanken (bei gemeinsamem Verzeichnis nur einmal). Der Dateiname wird zurückgegeben;
 * Testobjekte und Dateien werden anschließend von der Testumgebung aufgeräumt.</p>
 */
public final class OracleSchemaIntegration {
    private static final Charset SCRIPT_CHARSET = Charset.forName("windows-1252");
    private static final String SCHEMA_PLACEHOLDER = "${SCHEMA_IDENTIFIER}";

    private OracleSchemaIntegration() { }

    /** Hinterlassene Artefakte zur Untersuchung und zum gezielten Aufräumen der Testumgebung. */
    public record Result(Path synchronizationScript, String externalDataFile) { }

    /**
     * Führt die beiden getrennten Fixtures und den anschließenden Abgleich aus.
     * Schemanamen stammen jeweils aus {@link Connection#getSchema()} und werden exakt gequotet.
     *
     * @param referenceConnection offene Verbindung zum leeren Referenzschema
     * @param targetConnection offene Verbindung zum leeren Zielschema
     * @param outputFile dauerhaft aufzubewahrendes erzeugtes Abgleichsskript
     * @throws SQLException bei nicht leeren Schemata, Datenbankfehlern oder fehlgeschlagenen Prüfungen
     * @throws IOException bei fehlenden Fixtures, Skript- oder Dateifehlern
     */
    public static Result run(Connection referenceConnection, Connection targetConnection, Path outputFile)
            throws SQLException, IOException {
        Objects.requireNonNull(outputFile, "outputFile");
        String referenceSchema = currentSchema(referenceConnection, "Referenz");
        String targetSchema = currentSchema(targetConnection, "Ziel");
        if (referenceConnection == targetConnection) {
            throw new IllegalArgumentException("Der Integrationstest benötigt zwei getrennte JDBC-Connections.");
        }
        // Beide Vorprüfungen und beide Parserläufe müssen vor der ersten Fixture-DDL erfolgreich sein.
        requireEmptySchema(referenceConnection, referenceSchema);
        requireEmptySchema(targetConnection, targetSchema);
        if (referenceSchema.equals(targetSchema)) {
            requireDifferentSchemas(referenceConnection, targetConnection, referenceSchema);
        }
        String externalFile = "oracle_compare_" + UUID.randomUUID().toString().replace("-", "") + ".dmp";
        if (fileExists(referenceConnection, externalFile) || fileExists(targetConnection, externalFile)) {
            throw new SQLException("Die neu gewählte Testdatei existiert bereits: EXPORT_HOST/" + externalFile);
        }
        List<String> referenceFixture = fixture("reference.sql", referenceSchema);
        List<String> targetFixture = fixture("target.sql", targetSchema);
        List<String> referenceExternal = externalFixture(referenceSchema, "EXT_DATAPUMP", externalFile);
        List<String> targetExternalSeed = externalFixture(targetSchema, "ORACMP_EXTERNAL_SEED", externalFile);
        OracleSqlScript.execute(referenceConnection, referenceExternal);
        // Bei getrennten Hosts erzeugt das Ziel dieselben Daten selbst; bei gemeinsamem Directory
        // ist die Quelldatei bereits erreichbar. Kein CREATE versucht eine vorhandene Datei zu ersetzen.
        if (!fileExists(targetConnection, externalFile)) {
            OracleSqlScript.execute(targetConnection, targetExternalSeed);
            OracleSqlScript.execute(targetConnection,
                    List.of("DROP TABLE " + SqlText.qualified(targetSchema, "ORACMP_EXTERNAL_SEED") + " PURGE"));
        }
        OracleSqlScript.execute(referenceConnection, referenceFixture);
        OracleSqlScript.execute(targetConnection, targetFixture);

        OracleSchemaComparator comparator = new OracleSchemaComparator();
        comparator.writeSynchronizationScript(referenceConnection, referenceSchema,
                targetConnection, targetSchema, outputFile);
        List<String> synchronization = OracleSqlScript.statements(Files.readString(outputFile, SCRIPT_CHARSET));
        if (objectStatements(synchronization, targetSchema).isEmpty()) {
            throw new SQLException("Das unterschiedliche Fixture erzeugte keine Objekt-DDL.");
        }
        OracleSqlScript.execute(targetConnection, synchronization);
        verifyTarget(targetConnection, targetSchema);

        Path verification = Files.createTempFile(outputFile.toAbsolutePath().getParent(), ".oracle-compare-verification-", ".sql");
        try {
            comparator.writeSynchronizationScript(referenceConnection, referenceSchema,
                    targetConnection, targetSchema, verification);
            List<String> remaining = objectStatements(
                    OracleSqlScript.statements(Files.readString(verification, SCRIPT_CHARSET)), targetSchema);
            if (!remaining.isEmpty()) {
                throw new SQLException("Nach dem Abgleich verbleiben " + remaining.size()
                        + " SQL-Anweisungen. Erste Anweisung: " + remaining.get(0));
            }
        } finally {
            Files.deleteIfExists(verification);
        }
        return new Result(outputFile.toAbsolutePath().normalize(), externalFile);
    }

    /** Keine Normalisierung: Auch gequotete Oracle-Schemanamen mit gemischter Schreibweise gelten. */
    private static String currentSchema(Connection connection, String role) throws SQLException {
        Objects.requireNonNull(connection, role + "-Connection");
        if (connection.isClosed()) throw new SQLException(role + "-Connection ist geschlossen.");
        String schema = connection.getSchema();
        if (schema == null || schema.isBlank()) throw new SQLException(role + "-Connection liefert kein aktuelles Schema.");
        SqlText.identifier(schema);
        return schema;
    }

    /** Prüft auch nicht vom Comparator unterstützte Objekttypen, damit keine Nutzobjekte betroffen sind. */
    private static void requireEmptySchema(Connection connection, String schema) throws SQLException {
        requireNoRows(connection, schema,
                "SELECT object_name, object_type FROM dba_objects WHERE owner = ? AND ROWNUM = 1",
                "Integrationstest benötigt ein leeres Schema");
    }

    /** Erkennt auch zwei unterschiedliche JDBC-URLs/Aliase für dasselbe physische Schema. */
    private static void requireDifferentSchemas(Connection reference, Connection target, String schema) throws SQLException {
        String name = "OC_PROBE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(java.util.Locale.ROOT);
        OracleSqlScript.execute(reference, List.of("CREATE TABLE " + SqlText.qualified(schema, name) + " (ID NUMBER)"));
        SQLException failure = null;
        try (var statement = target.prepareStatement("SELECT object_name FROM dba_objects WHERE owner = ? AND object_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, name);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) throw new SQLException("Beide Connections verweisen auf dasselbe Testschema: " + schema);
            }
        } catch (SQLException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                OracleSqlScript.execute(reference, List.of("DROP TABLE " + SqlText.qualified(schema, name) + " PURGE"));
            } catch (SQLException cleanupFailure) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    /** Ersetzt ausschließlich den technischen Platzhalter; Literale enthalten keine Schemanamen. */
    static List<String> fixture(String resourceName, String schema) throws IOException {
        return OracleSqlScript.statements(fixtureText(resourceName).replace(SCHEMA_PLACEHOLDER, SqlText.identifier(schema)));
    }

    private static String fixtureText(String resourceName) throws IOException {
        try (var input = OracleSchemaIntegration.class.getResourceAsStream("/oracle/" + resourceName)) {
            if (input == null) throw new IOException("Integrationsfixture fehlt im Test-Classpath: " + resourceName);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<String> externalFixture(String schema, String table, String fileName) throws IOException {
        // Ein interner, streng begrenzter Dateiname ist kein frei ausführbarer SQL-Text.
        if (!fileName.matches("oracle_compare_[0-9a-f]{32}\\.dmp")) {
            throw new IllegalArgumentException("Ungültiger Name der Data-Pump-Testdatei.");
        }
        return OracleSqlScript.statements(fixtureText("external-data.sql")
                .replace(SCHEMA_PLACEHOLDER, SqlText.identifier(schema))
                .replace("${EXTERNAL_TABLE_IDENTIFIER}", SqlText.identifier(table))
                .replace("${EXTERNAL_FILE}", fileName));
    }

    /** Prüft Directoryzugriff und Dateiexistenz ohne Dateien anzulegen, zu öffnen oder zu verändern. */
    private static boolean fileExists(Connection connection, String fileName) throws SQLException {
        try (var statement = connection.prepareCall("""
                DECLARE
                  file_present BOOLEAN;
                  file_length NUMBER;
                  block_size BINARY_INTEGER;
                  file_flag PLS_INTEGER := 0;
                BEGIN
                  UTL_FILE.FGETATTR('EXPORT_HOST', ?, file_present, file_length, block_size);
                  IF file_present THEN file_flag := 1; END IF;
                  ? := file_flag;
                END;
                """)) {
            statement.setString(1, fileName);
            statement.registerOutParameter(2, Types.INTEGER);
            statement.execute();
            return statement.getInt(2) == 1;
        }
    }

    /** Der Generator fügt selbst bei einem leeren Abgleich die bekannte aktuelle Schemaeinstellung ein. */
    private static List<String> objectStatements(List<String> statements, String targetSchema) {
        String expectedSession = "ALTER SESSION SET CURRENT_SCHEMA = " + SqlText.identifier(targetSchema);
        return statements.stream().filter(sql -> !sql.equals(expectedSession)).toList();
    }

    /** Prüft reale Dictionary-Zustände zusätzlich zum zweiten Metadatenabgleich. */
    private static void verifyTarget(Connection connection, String schema) throws SQLException {
        requireNoRows(connection, schema, """
                SELECT constraint_name, status || '/' || validated FROM all_constraints
                WHERE owner = ? AND constraint_type = 'R' AND (status <> 'ENABLED' OR validated <> 'VALIDATED')
                """, "Foreign Key ist nicht aktiviert und validiert");
        requireNoRows(connection, schema, """
                SELECT object_name, status FROM all_objects
                WHERE owner = ? AND object_type = 'VIEW' AND status <> 'VALID'
                """, "View ist ungültig");
        requireNoRows(connection, schema, """
                SELECT index_name, partition_name FROM all_ind_partitions
                WHERE index_owner = ? AND status <> 'USABLE'
                """, "Indexpartition ist nicht verwendbar");
        requirePairs(connection, schema,
                "SELECT table_name, partitioning_type FROM all_part_tables WHERE owner = ?",
                Map.of("PART_RANGE_SALES", "RANGE", "PART_LIST_REGIONS", "LIST"), "Tabellenpartitionierung");
        requirePairs(connection, schema,
                "SELECT index_name, locality FROM all_part_indexes WHERE owner = ?",
                Map.of("PART_RANGE_LOCAL_IX", "LOCAL", "PART_RANGE_GLOBAL_IX", "GLOBAL",
                        "PART_LIST_LOCAL_IX", "LOCAL"), "Indexpartitionierung");
        requirePairs(connection, schema, """
                SELECT table_name || '.' || partition_name, TO_CHAR(partition_position)
                FROM all_tab_partitions WHERE table_owner = ?
                """, Map.of("PART_RANGE_SALES.P_2025", "1", "PART_RANGE_SALES.P_FUTURE", "2",
                        "PART_LIST_REGIONS.P_EU", "1", "PART_LIST_REGIONS.P_US", "2",
                        "PART_LIST_REGIONS.P_OTHER", "3"), "Tabellenpartitionen");
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT ID, LABEL FROM "
                     + SqlText.qualified(schema, "EXT_DATAPUMP") + " ORDER BY ID")) {
            for (int id = 1; id <= 2; id++) {
                String label = id == 1 ? "First external row" : "Second external row";
                if (!rows.next() || rows.getInt(1) != id || !label.equals(rows.getString(2))) {
                    throw new SQLException("Externe Tabelle liefert nicht die erwarteten Testdaten: " + schema);
                }
            }
            if (rows.next()) throw new SQLException("Externe Tabelle enthält zusätzliche Testdaten: " + schema);
        }
    }

    private static void requireNoRows(Connection connection, String schema, String sql, String message)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) throw new SQLException(message + ": " + schema + "."
                        + rows.getString(1) + " (" + rows.getString(2) + ")");
            }
        }
    }

    private static void requirePairs(Connection connection, String schema, String sql,
                                     Map<String, String> expected, String label) throws SQLException {
        Map<String, String> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) actual.put(rows.getString(1), rows.getString(2));
            }
        }
        if (!actual.equals(expected)) throw new SQLException(label + " in " + schema
                + ": erwartet " + expected + ", erhalten " + actual);
    }
}
