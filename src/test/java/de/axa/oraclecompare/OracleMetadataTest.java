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
                row("FK_ITEMS", "ITEMS", null, null), row("FK_LOG", "MLOG$_ITEMS", null, null));
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

    @ParameterizedTest
    @ValueSource(strings = {"ix_docs", "DoCs", "IX_D*", "DOC?"})
    void excludesDomainIndexStorageAndItsOwnIndexesAndConstraints(String exclusion) throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("Mixed_Schema", Query.SECONDARY_OBJECTS,
                row("Renamed_Storage", "IX_DOCS", "DOCS"));
        jdbc.rows("Mixed_Schema", Query.TABLES,
                row("DOCS", null, "N", null), row("Renamed_Storage", null, "Y", null),
                row("OTHER", null, "N", null));
        jdbc.rows("Mixed_Schema", Query.INDEXES,
                row("IX_DOCS", "DOCS", "DOMAIN", "Mixed_Schema", "NO"),
                row("IX_STORAGE", "Renamed_Storage", "NORMAL", "Mixed_Schema", "NO"),
                row("IX_OTHER", "OTHER", "NORMAL", "Mixed_Schema", "NO"));
        jdbc.rows("Mixed_Schema", Query.FOREIGN_KEYS,
                row("FK_STORAGE", "Renamed_Storage", "Mixed_Schema", "DOCS"),
                row("FK_OTHER", "OTHER", "Mixed_Schema", "DOCS"));
        jdbc.rows("Mixed_Schema", Query.CONSTRAINT_INDEXES,
                row("PK_STORAGE", "Renamed_Storage"), row("PK_OTHER", "OTHER"));
        jdbc.rows("Mixed_Schema", Query.NAMED_CONSTRAINTS,
                row("CK_STORAGE", "Renamed_Storage"), row("CK_OTHER", "OTHER"));

        Snapshot snapshot = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of(exclusion)))
                .snapshot("Mixed_Schema");

        assertEquals(Set.of("DOCS", "OTHER"), snapshot.objects(Type.TABLE).keySet());
        assertEquals(Set.of("IX_DOCS", "IX_OTHER"), snapshot.objects(Type.INDEX).keySet(),
                "Der ausgeschlossene fachliche Index bleibt zum Schutz seiner Basistabelle im Inventar.");
        assertEquals(List.of(new ForeignKey("FK_OTHER", "OTHER", "Mixed_Schema", "DOCS")), snapshot.foreignKeys());
        assertEquals(Map.of("PK_OTHER", "OTHER"), snapshot.constraintIndexes());
        assertEquals(Map.of("CK_STORAGE", "Renamed_Storage", "CK_OTHER", "OTHER"), snapshot.constraintTables(),
                "Benannte Storageconstraints bleiben für schemaweite Namenskonflikte sichtbar.");
        assertEquals(0, jdbc.metadataCalls);
        jdbc.assertReleased();
    }

    @Test
    void domainIndexStorageExclusionUsesTheStorageOwnerAndDoesNotHideSameNamedUserTables() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("REFERENCE", Query.TABLES, row("SAME_NAME", null, "N", null));
        jdbc.rows("TARGET", Query.TABLES, row("SAME_NAME", null, "Y", null));
        jdbc.rows("TARGET", Query.SECONDARY_OBJECTS, row("SAME_NAME", "IX_DOCS", "DOCS"));
        OracleMetadata metadata = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of("IX_DOCS")));

        assertEquals(Set.of("SAME_NAME"), metadata.snapshot("REFERENCE").objects(Type.TABLE).keySet());
        assertTrue(metadata.snapshot("TARGET").objects(Type.TABLE).isEmpty());
        String sql = jdbc.sql.get(Query.SECONDARY_OBJECTS);
        assertTrue(sql.contains("s.secondary_object_owner = ?"));
        assertTrue(sql.contains("i.owner=s.index_owner AND i.index_name=s.index_name"),
                "Gleichnamige Indizes anderer Besitzer dürfen nicht verwechselt werden.");
        jdbc.assertReleased();
    }

    @Test
    void protectsConstraintNamesOwnedByExcludedDomainIndexStorage() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES,
                row("DOCS", null, "N", null), row("STORAGE_DOCS", null, "Y", null));
        jdbc.rows("TARGET", Query.SECONDARY_OBJECTS, row("STORAGE_DOCS", "IX_DOCS", "DOCS"));
        jdbc.rows("TARGET", Query.INDEXES, row("IX_DOCS", "DOCS", "DOMAIN", "TARGET", "NO"));
        jdbc.rows("TARGET", Query.NAMED_CONSTRAINTS, row("C_SHARED", "STORAGE_DOCS"));
        ExclusionFilter exclusions = new ExclusionFilter(List.of("IX_DOCS"));
        Snapshot target = new OracleMetadata(jdbc.connection, exclusions).snapshot("TARGET");
        Snapshot reference = new Snapshot(
                Map.of(Type.TABLE, Map.of("NEW_TABLE", new DbObject(Type.TABLE, "NEW_TABLE", null))),
                List.of(), Map.of(), Map.of(), Map.of("C_SHARED", "NEW_TABLE"));
        ComparisonScope scope = new ComparisonScope(reference, target, exclusions);

        assertFalse(target.objects(Type.TABLE).containsKey("STORAGE_DOCS"));
        assertEquals(Map.of("C_SHARED", "STORAGE_DOCS"), target.constraintTables());
        assertTrue(scope.actual().constraintTables().isEmpty(),
                "Der Storageconstraint bleibt ausschließlich zur Namensprüfung im vollständigen Inventar.");
        SQLException failure = assertThrows(SQLException.class,
                () -> scope.checkDependencies(Set.of("NEW_TABLE"), Set.of(), "REFERENCE", "TARGET"));

        assertTrue(failure.getMessage().contains("C_SHARED"));
        assertTrue(failure.getMessage().contains("STORAGE_DOCS"));
        jdbc.assertReleased();
    }

    @Test
    void excludingOnlyStorageDoesNotEnableAnUnsupportedDomainIndex() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES,
                row("DOCS", null, "N", null), row("STORAGE_DOCS", null, "Y", null));
        jdbc.rows("TARGET", Query.SECONDARY_OBJECTS, row("STORAGE_DOCS", "IX_DOCS", "DOCS"));
        jdbc.rows("TARGET", Query.INDEXES, row("IX_DOCS", "DOCS", "DOMAIN", "TARGET", "NO"));
        Path output = Files.writeString(directory.resolve("domain.sql"), "previous complete script");

        SQLException failure = assertThrows(SQLException.class,
                () -> new OracleSchemaComparator().writeSynchronizationScript(
                        jdbc.connection, "REFERENCE", "TARGET", output, List.of("STORAGE_*")));

        assertTrue(failure.getMessage().contains("Domain-Index"));
        assertTrue(failure.getMessage().contains("TARGET.IX_DOCS"));
        assertEquals("previous complete script", Files.readString(output));
        assertEquals(0, jdbc.metadataCalls);
        jdbc.assertReleased();
    }

    @Test
    void synchronizesOtherObjectsWithoutTouchingExcludedDomainIndexStorage() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES,
                row("DOCS", null, "N", null), row("STORAGE_DOCS", null, "Y", null),
                row("EXCESS_TABLE", null, "N", null));
        jdbc.rows("TARGET", Query.SECONDARY_OBJECTS, row("STORAGE_DOCS", "IX_DOCS", "DOCS"));
        jdbc.rows("TARGET", Query.INDEXES,
                row("IX_DOCS", "DOCS", "DOMAIN", "TARGET", "NO"),
                row("IX_STORAGE", "STORAGE_DOCS", "NORMAL", "TARGET", "NO"));
        jdbc.rows("TARGET", Query.FOREIGN_KEYS,
                row("FK_STORAGE", "STORAGE_DOCS", "TARGET", "DOCS"));
        Path output = directory.resolve("excluded-domain.sql");

        new OracleSchemaComparator().writeSynchronizationScript(
                jdbc.connection, "REFERENCE", "TARGET", output, List.of("DOCS"));

        String script = Files.readString(output, Charset.forName("windows-1252"));
        assertTrue(script.contains("DROP TABLE \"TARGET\".\"EXCESS_TABLE\";"));
        assertFalse(script.contains("DOCS"));
        assertFalse(script.contains("STORAGE"));
        assertEquals(0, jdbc.metadataCalls);
        jdbc.assertReleased();
    }

    @Test
    void collectsLocalViewDependenciesOnTablesAndViews() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("Mixed_Schema", Query.TABLES, row("BASE_TABLE", null, "N", null));
        jdbc.rows("Mixed_Schema", Query.VIEWS, row("BASE_VIEW"), row("KEEP_VIEW"));
        jdbc.rows("Mixed_Schema", Query.VIEW_DEPENDENCIES,
                row("BASE_VIEW", "BASE_TABLE"), row("KEEP_VIEW", "BASE_VIEW"));

        Snapshot snapshot = new OracleMetadata(jdbc.connection).snapshot("Mixed_Schema");

        assertEquals(Map.of("BASE_VIEW", Set.of("BASE_TABLE"), "KEEP_VIEW", Set.of("BASE_VIEW")),
                snapshot.viewDependencies());
        String sql = jdbc.sql.get(Query.VIEW_DEPENDENCIES);
        assertTrue(sql.contains("referenced_type IN ('VIEW', 'TABLE')"));
        assertTrue(sql.contains("referenced_link_name IS NULL"),
                "Gleichnamige Objekte über Datenbanklinks sind keine lokalen Abhängigkeiten.");
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

    @ParameterizedTest
    @ValueSource(strings = {"CLUSTER", "SECONDARY", "REFERENCE"})
    void retainsExcludedUnsupportedTablesInTheCompleteInventory(String variant) throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES, row("IGNORED_TABLE",
                variant.equals("CLUSTER") ? "CLUSTER_ONE" : null,
                variant.equals("SECONDARY") ? "Y" : "N",
                variant.equals("REFERENCE") ? "REFERENCE" : null));
        jdbc.rows("TARGET", Query.INDEXES, row("IX_IGNORED", "IGNORED_TABLE", "DOMAIN", "TARGET", "NO"));
        jdbc.rows("TARGET", Query.FOREIGN_KEYS, row("FK_IGNORED", "IGNORED_TABLE", "TARGET", "PARENT"));
        jdbc.rows("TARGET", Query.CONSTRAINT_INDEXES, row("PK_IGNORED", "IGNORED_TABLE"));

        Snapshot snapshot = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of("ignored_*")))
                .snapshot("TARGET");

        assertEquals(Set.of("IGNORED_TABLE"), snapshot.objects(Type.TABLE).keySet());
        assertEquals(Map.of("IX_IGNORED", new DbObject(Type.INDEX, "IX_IGNORED", "IGNORED_TABLE")),
                snapshot.objects(Type.INDEX));
        assertEquals(List.of(new ForeignKey("FK_IGNORED", "IGNORED_TABLE", "TARGET", "PARENT")), snapshot.foreignKeys());
        assertEquals(Map.of("PK_IGNORED", "IGNORED_TABLE"), snapshot.constraintIndexes());
        jdbc.assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOMAIN", "CROSS_SCHEMA", "BITMAP_JOIN"})
    void retainsExcludedUnsupportedIndexesInTheCompleteInventory(String variant) throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES, row("FACT", null, "N", null));
        jdbc.rows("TARGET", Query.INDEXES, row("IX_IGNORED", "FACT",
                variant.equals("DOMAIN") ? "DOMAIN" : "BITMAP",
                variant.equals("CROSS_SCHEMA") ? "OTHER" : "TARGET",
                variant.equals("BITMAP_JOIN") ? "YES" : "NO"));

        Snapshot snapshot = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of("ix_ignored")))
                .snapshot("TARGET");

        assertEquals(Map.of("IX_IGNORED", new DbObject(Type.INDEX, "IX_IGNORED", "FACT")), snapshot.objects(Type.INDEX));
        jdbc.assertReleased();
    }

    @Test
    void protectsIncomingForeignKeysOwnedByAnExcludedTable() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES, row("PARENT", null, "N", null), row("IGNORED_CHILD", null, "N", null));
        jdbc.rows("TARGET", Query.INCOMING_FOREIGN_KEYS, row("TARGET", "FK_CHILD_PARENT", "PARENT", "IGNORED_CHILD"));
        OracleMetadata metadata = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of("ignored_*")));
        Snapshot snapshot = metadata.snapshot("TARGET");

        SQLException failure = assertThrows(SQLException.class,
                () -> metadata.checkExternalForeignKeys("TARGET", Set.of("PARENT")));

        assertEquals(Set.of("PARENT", "IGNORED_CHILD"), snapshot.objects(Type.TABLE).keySet());
        assertTrue(failure.getMessage().contains("TARGET.FK_CHILD_PARENT"));
        assertTrue(failure.getMessage().contains("PARENT"));
        jdbc.assertReleased();
    }

    @Test
    void allowsIncomingForeignKeysOwnedByAManagedTable() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES, row("PARENT", null, "N", null), row("CHILD", null, "N", null));
        jdbc.rows("TARGET", Query.INCOMING_FOREIGN_KEYS, row("TARGET", "FK_CHILD_PARENT", "PARENT", "CHILD"));
        OracleMetadata metadata = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of("ignored_*")));
        metadata.snapshot("TARGET");

        assertDoesNotThrow(() -> metadata.checkExternalForeignKeys("TARGET", Set.of("PARENT")));

        jdbc.assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ix_ignored", "fact"})
    void protectsExcludedBitmapJoinIndexesAgainstChangesToOtherTables(String exclusion) throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES, row("FACT", null, "N", null), row("DIM", null, "N", null));
        jdbc.rows("TARGET", Query.INDEXES, row("IX_IGNORED", "FACT", "BITMAP", "TARGET", "YES"));
        OracleMetadata metadata = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of(exclusion)));
        metadata.snapshot("TARGET");

        assertDoesNotThrow(() -> metadata.checkExternalForeignKeys("TARGET", Set.of()));
        SQLException failure = assertThrows(SQLException.class,
                () -> metadata.checkExternalForeignKeys("TARGET", Set.of("DIM")));

        assertTrue(failure.getMessage().contains("TARGET.IX_IGNORED"));
        assertTrue(failure.getMessage().contains("Dimensionstabellen"));
        jdbc.assertReleased();
    }

    @Test
    void protectsExcludedBitmapJoinIndexEvenWhenItsFactTableIsOutsideTheInventory() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("TARGET", Query.TABLES, row("DIM", null, "N", null));
        jdbc.rows("TARGET", Query.INDEXES, row("IX_IGNORED", "MV_FACT", "BITMAP", "TARGET", "YES"));
        OracleMetadata metadata = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of("IX_IGNORED")));
        Snapshot snapshot = metadata.snapshot("TARGET");

        assertTrue(snapshot.objects(Type.INDEX).isEmpty());
        SQLException failure = assertThrows(SQLException.class,
                () -> metadata.checkExternalForeignKeys("TARGET", Set.of("DIM")));

        assertTrue(failure.getMessage().contains("TARGET.IX_IGNORED"));
        jdbc.assertReleased();
    }

    @Test
    void excludedBitmapJoinIndexInReferenceDoesNotPreventChangesToTargetTables() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("REFERENCE", Query.TABLES, row("FACT", null, "N", null));
        jdbc.rows("REFERENCE", Query.INDEXES, row("IX_IGNORED", "FACT", "BITMAP", "REFERENCE", "YES"));
        jdbc.rows("TARGET", Query.TABLES, row("DIM", null, "N", null));
        OracleMetadata metadata = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of("IX_IGNORED")));
        metadata.snapshot("REFERENCE");
        metadata.snapshot("TARGET");

        assertDoesNotThrow(() -> metadata.checkExternalForeignKeys("TARGET", Set.of("DIM")));

        jdbc.assertReleased();
    }

    @Test
    void retainsExplicitConstraintNamesOnExcludedTablesAndKeepsOwnersSeparate() throws Exception {
        JdbcDictionary jdbc = new JdbcDictionary();
        jdbc.rows("REFERENCE", Query.TABLES, row("NEW_TABLE", null, "N", null));
        jdbc.rows("REFERENCE", Query.NAMED_CONSTRAINTS, row("C_SHARED", "NEW_TABLE"));
        jdbc.rows("TARGET", Query.TABLES, row("ACTIVE_TABLE", null, "N", null), row("IGNORED_TABLE", null, "N", null));
        jdbc.rows("TARGET", Query.NAMED_CONSTRAINTS,
                row("C_SHARED", "IGNORED_TABLE"), row("PK_ACTIVE", "ACTIVE_TABLE"));
        OracleMetadata metadata = new OracleMetadata(jdbc.connection, new ExclusionFilter(List.of("IGNORED_*")));

        Snapshot reference = metadata.snapshot("REFERENCE");
        Snapshot target = metadata.snapshot("TARGET");

        assertEquals(Map.of("C_SHARED", "NEW_TABLE"), reference.constraintTables());
        assertEquals(Map.of("C_SHARED", "IGNORED_TABLE", "PK_ACTIVE", "ACTIVE_TABLE"), target.constraintTables());
        jdbc.assertReleased();
    }

    private static String[] row(String... cells) { return cells; }

    /** Ordnet JDBC-Abfragen ihren fachlichen Ergebnissen zu, ohne Oracle-DDL zu simulieren. */
    private enum Query {
        USERS("SELECT USERNAME "), LOGS("SELECT LOG_TABLE "), SECONDARY_OBJECTS("SELECT S.SECONDARY_OBJECT_NAME, "),
        TABLES("SELECT TABLE_NAME, "),
        INDEXES("SELECT INDEX_NAME, "), VIEWS("SELECT VIEW_NAME "), SEQUENCES("SELECT SEQUENCE_NAME "),
        FOREIGN_KEYS("SELECT CONSTRAINT_NAME, "), VIEW_DEPENDENCIES("SELECT NAME, "),
        CONSTRAINT_INDEXES("SELECT DISTINCT INDEX_NAME, "), INCOMING_FOREIGN_KEYS("SELECT FK.OWNER, "),
        NAMED_CONSTRAINTS("SELECT C.CONSTRAINT_NAME, ");

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
        private final Map<Query, String> sql = new EnumMap<>(Query.class);
        private int openStatements;
        private int openResultSets;
        private int metadataCalls;
        private boolean connectionClosed;
        private final Connection connection = proxy(Connection.class, (object, method, args) -> {
            return switch (method.getName()) {
                case "isClosed" -> connectionClosed;
                case "close" -> { connectionClosed = true; yield null; }
                case "prepareStatement" -> {
                    Query query = Query.of((String) args[0]);
                    sql.put(query, (String) args[0]);
                    yield statement(query);
                }
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
