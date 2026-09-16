package de.axa.oraclecompare;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft die komplette Fassade mit realem Reader/Planer und zwei getrennten JDBC-Testverbindungen. */
class OracleSchemaCompareTest {
    @TempDir Path directory;

    @Test
    void routesEachSchemaToItsConnectionAndNeverMutatesConnectionState() throws Exception {
        Dictionary reference = new Dictionary();
        Dictionary target = new Dictionary();
        Path output = directory.resolve("sync.sql");

        Path result = comparator("SOURCE", "TARGET", output).writeSynchronizationScript(reference.connection, target.connection);

        assertEquals(output.toAbsolutePath(), result);
        assertTrue(reference.boundSchemas.stream().allMatch("SOURCE"::equals));
        assertTrue(target.boundSchemas.stream().allMatch("TARGET"::equals));
        assertFalse(reference.boundSchemas.isEmpty());
        assertFalse(target.boundSchemas.isEmpty());
        assertEquals(0, reference.parameterQueries);
        assertEquals(1, target.parameterQueries);
        assertTrue(Files.readString(output, Charset.forName("windows-1252"))
                .contains("ALTER SESSION SET CURRENT_SCHEMA = \"TARGET\";"));
        String report = Files.readString(CompareConfiguration.defaultReportFile(output));
        assertTrue(report.contains("Keine Unterschiede im berücksichtigten Objektumfang"));
        assertTrue(report.contains("Referenzschema | <code>SOURCE</code>"));
        assertTrue(report.contains("Zielschema | <code>TARGET</code>"));
        reference.assertReleased();
        target.assertReleased();
    }

    @Test
    void supportsEqualSchemaNamesOnDifferentConnections() throws Exception {
        Dictionary reference = new Dictionary();
        Dictionary target = new Dictionary();
        Path output = directory.resolve("same-schema.sql");
        comparator("APP", "APP", output).writeSynchronizationScript(reference.connection, target.connection);
        assertTrue(Files.isRegularFile(output));
        reference.assertReleased();
        target.assertReleased();
    }

    @Test
    void supportsTwoSchemasThroughTheSameConnection() throws Exception {
        Dictionary database = new Dictionary();
        comparator("SOURCE", "TARGET", directory.resolve("one-connection.sql"))
                .writeSynchronizationScript(database.connection, database.connection);
        assertTrue(database.boundSchemas.containsAll(List.of("SOURCE", "TARGET")));
        database.assertReleased();
    }

    @Test
    void rejectsTheSameSchemaAndConnectionBeforeReadingDictionary() {
        Dictionary database = new Dictionary();
        assertThrows(IllegalArgumentException.class, () -> comparator("APP", "APP", directory.resolve("same.sql"))
                .writeSynchronizationScript(database.connection, database.connection));
        assertTrue(database.boundSchemas.isEmpty());
    }

    @Test
    void dictionaryFailurePreservesPreviousOutputAndReleasesResources() throws Exception {
        Dictionary reference = new Dictionary();
        Dictionary target = new Dictionary();
        target.failure = new SQLException("Missing dictionary privilege", "42000", 942);
        Path output = Files.writeString(directory.resolve("sync.sql"), "previous complete script");
        Path report = Files.writeString(CompareConfiguration.defaultReportFile(output), "previous complete report");

        SQLException failure = assertThrows(SQLException.class, () -> comparator("SOURCE", "TARGET", output)
                .writeSynchronizationScript(reference.connection, target.connection));

        assertEquals(942, failure.getErrorCode());
        assertEquals("previous complete script", Files.readString(output));
        assertEquals("previous complete report", Files.readString(report));
        reference.assertReleased();
        target.assertReleased();
    }

    @Test
    void rejectsClosedConnectionsWithoutQuerying() {
        Dictionary reference = new Dictionary();
        Dictionary target = new Dictionary();
        target.closed = true;
        assertThrows(SQLException.class, () -> comparator("SOURCE", "TARGET", directory.resolve("closed.sql"))
                .writeSynchronizationScript(reference.connection, target.connection));
        assertTrue(reference.boundSchemas.isEmpty());
        assertTrue(target.boundSchemas.isEmpty());
        assertEquals(0, reference.parameterQueries);
        assertEquals(0, target.parameterQueries);
    }

    @Test
    void targetCapabilityFailurePreservesArtifactsAndPreventsDictionaryReads() throws Exception {
        Dictionary reference = new Dictionary();
        Dictionary target = new Dictionary();
        target.parameterFailure = new SQLException("Missing V$PARAMETER privilege", "42000", 942);
        Path output = Files.writeString(directory.resolve("sync.sql"), "previous complete script");
        Path report = Files.writeString(CompareConfiguration.defaultReportFile(output), "previous complete report");

        SQLException failure = assertThrows(SQLException.class, () -> comparator("SOURCE", "TARGET", output)
                .writeSynchronizationScript(reference.connection, target.connection));

        assertEquals(942, failure.getErrorCode());
        assertEquals("previous complete script", Files.readString(output));
        assertEquals("previous complete report", Files.readString(report));
        assertTrue(reference.boundSchemas.isEmpty());
        assertTrue(target.boundSchemas.isEmpty());
        assertEquals(0, reference.parameterQueries);
        assertEquals(1, target.parameterQueries);
        reference.assertReleased();
        target.assertReleased();
    }

    @Test
    void acceptsStandardTargetWithoutReadingSourceCapabilities() throws Exception {
        Dictionary reference = new Dictionary();
        Dictionary target = new Dictionary();
        reference.parameterFailure = new SQLException("Source parameter access is unnecessary");
        target.maxStringSize = "STANDARD";

        comparator("SOURCE", "TARGET", directory.resolve("standard.sql"))
                .writeSynchronizationScript(reference.connection, target.connection);

        assertEquals(0, reference.parameterQueries);
        assertEquals(1, target.parameterQueries);
        reference.assertReleased();
        target.assertReleased();
    }

    @Test
    void writesReportToConfiguredIndependentDirectory() throws Exception {
        Dictionary reference = new Dictionary();
        Dictionary target = new Dictionary();
        Path output = directory.resolve("sql/sync.sql");
        Path report = directory.resolve("reports/changes.md");
        new OracleSchemaCompare(new CompareConfiguration("SOURCE", "TARGET", output, report, List.of()))
                .writeSynchronizationScript(reference.connection, target.connection);
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.readString(report).contains("Die Anwendung hat das Skript nicht ausgeführt."));
        assertFalse(Files.exists(CompareConfiguration.defaultReportFile(output)));
    }

    @Test
    void rejectsReportDirectoryBeforeDictionaryReadsAndPreservesPreviousScript() throws Exception {
        Dictionary reference = new Dictionary();
        Dictionary target = new Dictionary();
        Path output = Files.writeString(directory.resolve("sync.sql"), "previous");
        OracleSchemaCompare compare = new OracleSchemaCompare(new CompareConfiguration("SOURCE", "TARGET", output, directory, List.of()));
        assertThrows(java.io.IOException.class, () -> compare.writeSynchronizationScript(reference.connection, target.connection));
        assertTrue(reference.boundSchemas.isEmpty());
        assertTrue(target.boundSchemas.isEmpty());
        assertEquals("previous", Files.readString(output));
    }

    private OracleSchemaCompare comparator(String source, String target, Path output) {
        return new OracleSchemaCompare(new CompareConfiguration(source, target, output, List.of()));
    }

    /** Jede nicht ausdrücklich erlaubte Operation, insbesondere PL/SQL/Commit/Close, lässt den Test scheitern. */
    private static final class Dictionary {
        final List<String> boundSchemas = new ArrayList<>();
        int statements;
        int resultSets;
        int parameterQueries;
        boolean closed;
        SQLException failure;
        SQLException parameterFailure;
        String maxStringSize = "EXTENDED";
        final Connection connection = proxy(Connection.class, (object, method, args) -> switch (method.getName()) {
            case "isClosed" -> closed;
            case "prepareStatement" -> statement((String) args[0]);
            default -> throw new AssertionError("Unerwartete Connection-Operation: " + method.getName());
        });

        PreparedStatement statement(String sql) {
            String normalized = sql.toUpperCase(Locale.ROOT);
            assertTrue(normalized.stripLeading().startsWith("SELECT"));
            assertFalse(normalized.contains("DBMS_METADATA"));
            statements++;
            Map<Integer, String> bindings = new HashMap<>();
            return proxy(PreparedStatement.class, (object, method, args) -> switch (method.getName()) {
                case "setString" -> { bindings.put((Integer) args[0], (String) args[1]); yield null; }
                case "executeQuery" -> {
                    boundSchemas.addAll(bindings.values());
                    if (normalized.contains("FROM V$PARAMETER")) {
                        parameterQueries++;
                        if (parameterFailure != null) throw parameterFailure;
                        yield parameterRows();
                    }
                    if (failure != null) throw failure;
                    yield rows(normalized.contains("FROM DBA_USERS"), bindings.get(1));
                }
                case "close" -> { statements--; yield null; }
                default -> throw new AssertionError("Unerwartete Statement-Operation: " + method.getName());
            });
        }

        ResultSet parameterRows() {
            resultSets++;
            List<Map<String, String>> parameters = List.of(
                    Map.of("NAME", "max_string_size", "VALUE", maxStringSize, "CONTAINER_NAME", "APP_PDB"),
                    Map.of("NAME", "compatible", "VALUE", "19.0.0", "CONTAINER_NAME", "APP_PDB"));
            int[] cursor = {-1};
            return proxy(ResultSet.class, (object, method, args) -> switch (method.getName()) {
                case "next" -> ++cursor[0] < parameters.size();
                case "getString" -> parameters.get(cursor[0]).get(args[0]);
                case "close" -> { resultSets--; yield null; }
                default -> throw new AssertionError("Unerwartete Parameter-ResultSet-Operation: " + method.getName());
            });
        }

        ResultSet rows(boolean hasUser, String user) {
            resultSets++;
            int[] cursor = {0};
            ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (object, method, args) -> switch (method.getName()) {
                case "getColumnCount" -> hasUser ? 1 : 0;
                case "getColumnLabel", "getColumnName" -> "USERNAME";
                default -> throw new AssertionError("Unerwartete Metadaten-Operation: " + method.getName());
            });
            return proxy(ResultSet.class, (object, method, args) -> switch (method.getName()) {
                case "getMetaData" -> metadata;
                case "next" -> hasUser && cursor[0]++ == 0;
                case "getString" -> user;
                case "close" -> { resultSets--; yield null; }
                default -> throw new AssertionError("Unerwartete ResultSet-Operation: " + method.getName());
            });
        }

        void assertReleased() {
            assertEquals(0, statements);
            assertEquals(0, resultSets);
            assertFalse(closed);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
