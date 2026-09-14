package de.axa.oraclecompare;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static de.axa.oraclecompare.SchemaModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prüft die JDBC-Ergebnisverarbeitung und den frühen Abbruch vor einer Dateiveränderung. */
class OracleMetadataTest {
    @TempDir
    Path directory;

    @Test
    void excludesDictionaryIdentifiedLogTablesAndTheirIndexesAndConstraints() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("Mixed_Schema", Query.LOGS, row("MLOG$_ITEMS"), row("Renamed_Log"));
        jdbc.rows("Mixed_Schema", Query.TABLES,
                row("ITEMS", null, "N", null), row("MLOG$_USER_TABLE", null, "N", null),
                row("MLOG$_ITEMS", null, "N", null), row("Renamed_Log", null, "Y", null));
        jdbc.rows("Mixed_Schema", Query.INDEXES,
                row("IX_ITEMS", "ITEMS", "NORMAL", "Mixed_Schema", "NO"),
                row("IX_LOG", "MLOG$_ITEMS", "NORMAL", "Mixed_Schema", "NO"),
                row("IX_RENAMED_LOG", "Renamed_Log", "DOMAIN", "Mixed_Schema", "NO"));
        jdbc.rows("Mixed_Schema", Query.FOREIGN_KEYS,
                row("FK_ITEMS", "ITEMS"), row("FK_LOG", "MLOG$_ITEMS"));
        jdbc.rows("Mixed_Schema", Query.CONSTRAINT_INDEXES,
                row("PK_ITEMS", "ITEMS"), row("PK_LOG", "Renamed_Log"));

        Snapshot snapshot = new OracleMetadata(jdbc.connection).snapshot("Mixed_Schema");

        assertEquals(Set.of("ITEMS", "MLOG$_USER_TABLE"), snapshot.objects(Type.TABLE).keySet(),
                "Der Dictionary-Eintrag entscheidet, nicht ein MLOG$_-Namenspräfix.");
        assertEquals(Map.of("IX_ITEMS", new DbObject(Type.INDEX, "IX_ITEMS", "ITEMS")), snapshot.objects(Type.INDEX));
        assertEquals(List.of(new ForeignKey("FK_ITEMS", "ITEMS")), snapshot.foreignKeys());
        assertEquals(Map.of("PK_ITEMS", "ITEMS"), snapshot.constraintIndexes());
        jdbc.assertReleased();
    }

    @Test
    void logTableExclusionIsScopedToItsOwner() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("REFERENCE", Query.TABLES, row("SAME_NAME", null, "N", null));
        jdbc.rows("TARGET", Query.TABLES, row("SAME_NAME", null, "N", null));
        jdbc.rows("TARGET", Query.LOGS, row("SAME_NAME"));
        OracleMetadata metadata = new OracleMetadata(jdbc.connection);

        assertEquals(Set.of("SAME_NAME"), metadata.snapshot("REFERENCE").objects(Type.TABLE).keySet());
        assertTrue(metadata.snapshot("TARGET").objects(Type.TABLE).isEmpty());
        jdbc.assertReleased();
    }

    @Test
    void doesNotPlanDropTableForAnExtraLogInTheTarget() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.LOGS, row("MLOG$_ITEMS"));
        jdbc.rows("TARGET", Query.TABLES, row("MLOG$_ITEMS", null, "N", null));
        jdbc.rows("TARGET", Query.INDEXES, row("IX_LOG", "MLOG$_ITEMS", "NORMAL", "TARGET", "NO"));
        Path output = directory.resolve("logs.sql");

        new OracleSchemaComparator().writeSynchronizationScript(jdbc.connection, "REFERENCE", "TARGET", output);

        String script = Files.readString(output, Charset.forName("windows-1252"));
        assertFalse(script.contains("DROP TABLE"));
        assertFalse(script.contains("MLOG$_ITEMS"));
        assertFalse(script.contains("IX_LOG"));
        assertEquals(0, jdbc.metadataCalls);
        jdbc.assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFERENCE", "TARGET"})
    void rejectsBitmapJoinIndexesInEitherSchemaBeforeReplacingAnExistingFile(String owner) throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows(owner, Query.TABLES, row("FACT", null, "N", null), row("DIM", null, "N", null));
        jdbc.rows(owner, Query.INDEXES, row("IX_FACT_DIM", "FACT", "BITMAP", owner, "YES"));
        Path output = Files.writeString(directory.resolve("existing.sql"), "previous complete script\r\n");
        byte[] previous = Files.readAllBytes(output);

        SQLException failure = assertThrows(SQLException.class,
                () -> new OracleSchemaComparator().writeSynchronizationScript(
                        jdbc.connection, "REFERENCE", "TARGET", output));

        assertTrue(failure.getMessage().contains("Bitmap-Join-Index"));
        assertTrue(failure.getMessage().contains(owner + ".IX_FACT_DIM"));
        assertArrayEquals(previous, Files.readAllBytes(output));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(output), files.toList());
        }
        assertEquals(0, jdbc.metadataCalls, "Unvollständige Abhängigkeiten müssen schon bei der Bestandsaufnahme abbrechen.");
        jdbc.assertReleased();
    }

    @Test
    void acceptsOrdinaryBitmapIndexes() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES, row("FACT", null, "N", null));
        jdbc.rows("TARGET", Query.INDEXES, row("IX_FACT", "FACT", "BITMAP", "TARGET", "NO"));

        Snapshot snapshot = new OracleMetadata(jdbc.connection).snapshot("TARGET");

        assertEquals(Map.of("IX_FACT", new DbObject(Type.INDEX, "IX_FACT", "FACT")), snapshot.objects(Type.INDEX));
        jdbc.assertReleased();
    }

    private static String[] row(String... cells) { return cells; }

    /** Ordnet JDBC-Abfragen ihren fachlichen Ergebnissen zu, ohne Oracle-DDL zu simulieren. */
    private enum Query {
        USERS("SELECT USERNAME "), LOGS("SELECT LOG_TABLE "), TABLES("SELECT TABLE_NAME, "),
        INDEXES("SELECT INDEX_NAME, "), VIEWS("SELECT VIEW_NAME "), SEQUENCES("SELECT SEQUENCE_NAME "),
        FOREIGN_KEYS("SELECT CONSTRAINT_NAME, "), VIEW_DEPENDENCIES("SELECT NAME, "),
        CONSTRAINT_INDEXES("SELECT DISTINCT INDEX_NAME, ");

        private final String prefix;

        Query(String prefix) { this.prefix = prefix; }

        static Query of(String sql) {
            String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
            for (Query query : values()) if (normalized.startsWith(query.prefix)) return query;
            throw new AssertionError("Unerwartete Dictionary-Abfrage: " + sql);
        }
    }

    /** Minimale JDBC-Fixture: bindet echte Schema-Parameter und verfolgt Ressourcenbesitz. */
    private static final class JdbcDictionary {
        private final Map<String, Map<Query, List<String[]>>> data = new HashMap<>();
        private int openStatements;
        private int openResultSets;
        private int metadataCalls;
        private boolean connectionClosed;
        private final Connection connection = proxy(Connection.class, (object, method, args) -> {
            return switch (method.getName()) {
                case "isClosed" -> connectionClosed;
                case "close" -> { connectionClosed = true; yield null; }
                case "prepareStatement" -> statement(Query.of((String) args[0]));
                case "prepareCall" -> {
                    metadataCalls++;
                    throw new AssertionError("Bei diesen Bestandsaufnahmen darf kein DBMS_METADATA-Aufruf erfolgen.");
                }
                default -> throw new AssertionError("Unerwartete Connection-Methode: " + method.getName());
            };
        });

        void rows(String schema, Query query, String[]... rows) {
            data.computeIfAbsent(schema, ignored -> new EnumMap<>(Query.class)).put(query, List.of(rows));
        }

        private PreparedStatement statement(Query query) {
            openStatements++;
            Map<Integer, String> parameters = new HashMap<>();
            boolean[] closed = {false};
            return proxy(PreparedStatement.class, (object, method, args) -> {
                return switch (method.getName()) {
                    case "setString" -> { parameters.put((Integer) args[0], (String) args[1]); yield null; }
                    case "executeQuery" -> {
                        String schema = parameters.get(1);
                        assertNotNull(schema, "Jede Dictionary-Abfrage benötigt den Schemafilter.");
                        if (query == Query.VIEW_DEPENDENCIES || query == Query.CONSTRAINT_INDEXES) {
                            assertEquals(schema, parameters.get(2));
                        }
                        List<String[]> rows = query == Query.USERS ? List.<String[]>of(row(schema))
                                : data.getOrDefault(schema, Map.of()).getOrDefault(query, List.of());
                        yield resultSet(rows);
                    }
                    case "close" -> {
                        if (!closed[0]) { openStatements--; closed[0] = true; }
                        yield null;
                    }
                    default -> throw new AssertionError("Unerwartete Statement-Methode: " + method.getName());
                };
            });
        }

        private ResultSet resultSet(List<String[]> rows) {
            openResultSets++;
            int[] position = {-1};
            boolean[] closed = {false};
            return proxy(ResultSet.class, (object, method, args) -> {
                return switch (method.getName()) {
                    case "next" -> ++position[0] < rows.size();
                    case "getString" -> rows.get(position[0])[(Integer) args[0] - 1];
                    case "close" -> {
                        if (!closed[0]) { openResultSets--; closed[0] = true; }
                        yield null;
                    }
                    default -> throw new AssertionError("Unerwartete ResultSet-Methode: " + method.getName());
                };
            });
        }

        void assertReleased() {
            assertEquals(0, openResultSets);
            assertEquals(0, openStatements);
            assertFalse(connectionClosed, "Die übergebene Connection gehört weiterhin dem Aufrufer.");
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }
    }
}
