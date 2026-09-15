package de.axa.oraclecompare;

import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static de.axa.oraclecompare.SchemaModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prüft die feste Zuordnung von Inventaren und Oracle-Aufrufen zu zwei getrennten Datenquellen. */
class OracleSchemaComparatorConnectionsTest {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private final OracleSchemaComparator comparator = new OracleSchemaComparator();

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"APP, APP", "REF_APP, TARGET_APP"})
    void readsEachSchemaFromItsOwnSourceAndConvertsDifferencesOnlyInTheTarget(String referenceSchema,
                                                                            String targetSchema) throws Exception {
        SourceMetadata reference = source(true, referenceSchema, targetSchema);
        SourceMetadata target = source(false, targetSchema, targetSchema);
        Path output = directory.resolve("two-sources.sql");

        comparator.writeSynchronizationScript(reference, referenceSchema, target, targetSchema, output, List.of());

        String script = Files.readString(output, WINDOWS_1252);
        assertTrue(script.contains("CREATE TABLE " + SqlText.qualified(targetSchema, "NEW_TABLE")));
        assertTrue(script.contains("DROP TABLE " + SqlText.qualified(targetSchema, "EXCESS_TABLE") + ";"));
        assertTrue(script.contains(target.alterSql()));
        assertTrue(script.contains("DROP CONSTRAINT \"FK_OLD\";"));
        assertTrue(script.contains("ADD CONSTRAINT \"FK_NEW\""));
        assertEquals(1, reference.snapshotCalls);
        assertEquals(1, target.snapshotCalls);
        assertEquals(List.of("CHILD", "EXISTING", "PARENT"), reference.sxmlNames);
        assertEquals(reference.sxmlNames, target.sxmlNames);
        assertEquals(List.of("NEW_TABLE"), reference.ddlNames);
        assertTrue(target.ddlNames.isEmpty());
        assertEquals(List.of("FK_NEW"), reference.foreignKeyNames);
        assertEquals(List.of("FK_OLD"), target.foreignKeyNames);
        assertEquals(0, reference.comparisonCalls);
        assertEquals(3, target.comparisonCalls);
        assertEquals(0, reference.conversionCalls);
        assertEquals(1, target.conversionCalls);
        assertEquals(Set.of("EXISTING", "NEW_TABLE", "EXCESS_TABLE"), target.checkedTables);
        assertNull(reference.checkedTables);
    }

    @Test
    void appliesExclusionsToBothIndependentInventoriesWithTheSameSchemaName() throws Exception {
        SourceMetadata reference = source(true, "APP", "APP");
        SourceMetadata target = source(false, "APP", "APP");
        reference.add(new DbObject(Type.SEQUENCE, "IGNORE_NEW", null));
        target.add(new DbObject(Type.VIEW, "ignore_OLD", null));
        reference.add(new DbObject(Type.TABLE, "IGNORE_CHANGED", null));
        target.add(new DbObject(Type.TABLE, "IGNORE_CHANGED", null));
        Path output = directory.resolve("two-filtered-sources.sql");

        comparator.writeSynchronizationScript(reference, "APP", target, "APP", output, List.of("IgNoRe_*"));

        String script = Files.readString(output, WINDOWS_1252);
        assertFalse(script.contains("IGNORE_"));
        assertFalse(script.contains("ignore_"));
        assertTrue(script.contains("CREATE TABLE \"APP\".\"NEW_TABLE\""));
        assertTrue(script.contains("DROP TABLE \"APP\".\"EXCESS_TABLE\";"));
        assertEquals(List.of("NEW_TABLE"), reference.ddlNames);
        assertEquals(List.of("CHILD", "EXISTING", "PARENT"), reference.sxmlNames);
        assertEquals(reference.sxmlNames, target.sxmlNames);
        assertEquals(Set.of("EXISTING", "NEW_TABLE", "EXCESS_TABLE"), target.checkedTables);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsEitherClosedConnectionWithoutWritingOrChangingConnectionOwnership(boolean referenceClosed) throws Exception {
        ConnectionProbe reference = new ConnectionProbe(referenceClosed, false);
        ConnectionProbe target = new ConnectionProbe(!referenceClosed, false);
        Path output = Files.writeString(directory.resolve("closed.sql"), "previous complete script");

        assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                reference.connection, "APP", target.connection, "APP", output));

        assertEquals("previous complete script", Files.readString(output));
        assertEquals(0, reference.prepareCalls);
        assertEquals(0, target.prepareCalls);
        assertEquals(0, reference.ownershipCalls);
        assertEquals(0, target.ownershipCalls);
    }

    @Test
    void identicalSchemaOnTheSameConnectionIsStillRejected() {
        ConnectionProbe connection = new ConnectionProbe(false, false);

        assertThrows(IllegalArgumentException.class, () -> comparator.writeSynchronizationScript(
                connection.connection, "APP", connection.connection, "APP", directory.resolve("same.sql")));

        assertEquals(0, connection.prepareCalls);
        assertEquals(0, connection.ownershipCalls);
        assertFalse(Files.exists(directory.resolve("same.sql")));
    }

    @Test
    void metadataFailurePreservesBothConnectionsAndTheExistingFile() throws Exception {
        ConnectionProbe reference = new ConnectionProbe(false, true);
        ConnectionProbe target = new ConnectionProbe(false, false);
        Path output = Files.writeString(directory.resolve("failed.sql"), "previous complete script");

        SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                reference.connection, "APP", target.connection, "APP", output, List.of()));

        assertEquals("reference inventory unavailable", failure.getMessage());
        assertEquals(1, reference.prepareCalls);
        assertEquals(0, target.prepareCalls);
        assertEquals(0, reference.ownershipCalls);
        assertEquals(0, target.ownershipCalls);
        assertEquals("previous complete script", Files.readString(output));
    }

    private static SourceMetadata source(boolean reference, String ownSchema, String targetSchema) {
        SourceMetadata metadata = new SourceMetadata(reference, ownSchema, targetSchema);
        for (String table : List.of("PARENT", "CHILD", "EXISTING", reference ? "NEW_TABLE" : "EXCESS_TABLE")) {
            metadata.add(new DbObject(Type.TABLE, table, null));
        }
        metadata.keys.add(new ForeignKey(reference ? "FK_NEW" : "FK_OLD", "CHILD", ownSchema, "PARENT"));
        return metadata;
    }

    /** Eine Instanz kennt genau ein Inventar und lehnt jeden Aufruf für die falsche Quelle ab. */
    private static final class SourceMetadata implements Metadata {
        final boolean reference;
        final String ownSchema;
        final String targetSchema;
        final Map<Type, Map<String, DbObject>> objects = new EnumMap<>(Type.class);
        final List<ForeignKey> keys = new ArrayList<>();
        final List<String> sxmlNames = new ArrayList<>();
        final List<String> ddlNames = new ArrayList<>();
        final List<String> foreignKeyNames = new ArrayList<>();
        int snapshotCalls;
        int comparisonCalls;
        int conversionCalls;
        Set<String> checkedTables;

        SourceMetadata(boolean reference, String ownSchema, String targetSchema) {
            this.reference = reference;
            this.ownSchema = ownSchema;
            this.targetSchema = targetSchema;
        }

        void add(DbObject object) {
            objects.computeIfAbsent(object.type(), ignored -> new LinkedHashMap<>()).put(object.name(), object);
        }

        String alterSql() {
            return "ALTER TABLE " + SqlText.qualified(targetSchema, "EXISTING") + " ADD LABEL VARCHAR2(40);";
        }

        @Override
        public Snapshot snapshot(String schema) {
            assertEquals(ownSchema, schema);
            snapshotCalls++;
            return new Snapshot(objects, keys, Map.of());
        }

        @Override
        public String sxml(String schema, String target, DbObject object) {
            assertEquals(ownSchema, schema);
            assertEquals(targetSchema, target, "Das Remapping muss auch beim Referenzzugriff auf das Zielschema zeigen.");
            assertEquals(object, objects.get(object.type()).get(object.name()));
            sxmlNames.add(object.name());
            return (reference ? "REFERENCE_SOURCE:" : "TARGET_SOURCE:") + object.name();
        }

        @Override
        public String ddl(String schema, String target, DbObject object) {
            assertTrue(reference, "CREATE-DDL muss aus der Referenzdatenbank stammen.");
            assertEquals(ownSchema, schema);
            assertEquals(targetSchema, target);
            assertEquals(Type.TABLE, object.type());
            assertEquals("NEW_TABLE", object.name());
            ddlNames.add(object.name());
            return "CREATE TABLE " + SqlText.qualified(target, object.name()) + " (ID NUMBER PRIMARY KEY);";
        }

        @Override
        public String foreignKeyDdl(String schema, String target, ForeignKey key) {
            assertEquals(ownSchema, schema);
            assertEquals(targetSchema, target);
            assertTrue(keys.contains(key), "Fremdschlüssel-DDL darf nur für das Inventar dieser Quelle gelesen werden.");
            foreignKeyNames.add(key.name());
            return "ALTER TABLE " + SqlText.qualified(target, key.tableName()) + " ADD CONSTRAINT "
                    + SqlText.identifier(key.name()) + " FOREIGN KEY (ID) REFERENCES "
                    + SqlText.qualified(target, key.referencedTableName()) + " (ID);";
        }

        @Override
        public String alterXml(Type type, String actual, String desired) {
            assertFalse(reference, "DBMS_METADATA_DIFF muss auf der Zieldatenbank laufen.");
            assertEquals(Type.TABLE, type);
            assertTrue(actual.startsWith("TARGET_SOURCE:"));
            assertTrue(desired.startsWith("REFERENCE_SOURCE:"));
            assertEquals(actual.substring(actual.indexOf(':')), desired.substring(desired.indexOf(':')));
            comparisonCalls++;
            return actual.endsWith(":EXISTING")
                    ? "<ALTER_XML><SQL_LIST_ITEM><TEXT>" + alterSql() + "</TEXT></SQL_LIST_ITEM></ALTER_XML>"
                    : "<ALTER_XML/>";
        }

        @Override
        public String alterDdl(Type type, String alterXml) {
            assertFalse(reference, "ALTERDDL muss auf der Zieldatenbank konvertiert werden.");
            assertEquals(Type.TABLE, type);
            assertTrue(alterXml.contains(alterSql()));
            conversionCalls++;
            return alterSql();
        }

        @Override
        public void checkExternalForeignKeys(String target, Set<String> changedTables) {
            assertFalse(reference, "Externe Referenzen müssen in der Zieldatenbank geprüft werden.");
            assertEquals(targetSchema, target);
            checkedTables = Set.copyOf(changedTables);
        }
    }

    /** Nur die erwarteten Connection-Leseaufrufe sind erlaubt; Besitz-/Transaktionsänderungen fallen auf. */
    private static final class ConnectionProbe {
        final Connection connection;
        int prepareCalls;
        int ownershipCalls;

        ConnectionProbe(boolean closed, boolean failInventory) {
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "isClosed" -> closed;
                        case "prepareStatement" -> {
                            prepareCalls++;
                            if (failInventory) throw new SQLException("reference inventory unavailable");
                            throw new AssertionError("Diese Verbindung darf noch kein Inventar lesen.");
                        }
                        case "close", "commit", "rollback", "setAutoCommit" -> {
                            ownershipCalls++;
                            throw new AssertionError("Der Aufrufer behält die Verbindungs- und Transaktionsverwaltung.");
                        }
                        default -> throw new AssertionError("Unerwarteter Connection-Aufruf: " + method.getName());
                    });
        }
    }
}
