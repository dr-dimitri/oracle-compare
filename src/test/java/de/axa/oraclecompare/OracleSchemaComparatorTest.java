package de.axa.oraclecompare;

import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.InputSource;

import static de.axa.oraclecompare.SchemaModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prüft Abgleichsrichtung, ausführbare Objekt-Reihenfolge und Dateiintegrität ohne Oracle-Server. */
class OracleSchemaComparatorTest {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final String REFERENCE = "REFERENCE";
    private static final String TARGET = "TARGET";
    private final OracleSchemaComparator comparator = new OracleSchemaComparator();

    @TempDir
    Path directory;

    @Test
    void writesWindows1252BytesAndNormalizesMixedLineEndingsToCrlf() throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        FakeMetadata metadata = new FakeMetadata(snapshot(table), snapshot());
        metadata.createDdls.put(table, "-- Größe €\r\nCREATE TABLE \"TARGET\".\"ITEMS\" (\nID NUMBER\r);\n");

        String script = write(metadata, "windows.sql");
        byte[] bytes = Files.readAllBytes(directory.resolve("windows.sql"));

        assertTrue(script.contains("-- Größe €\r\nCREATE TABLE \"TARGET\".\"ITEMS\" (\r\nID NUMBER\r\n);"));
        assertEquals((byte) 0xF6, bytes[script.indexOf('ö')]);
        assertEquals((byte) 0xDF, bytes[script.indexOf('ß')]);
        assertEquals((byte) 0x80, bytes[script.indexOf('€')]);
        String withoutCrlf = script.replace("\r\n", "");
        assertFalse(withoutCrlf.contains("\r"));
        assertFalse(withoutCrlf.contains("\n"));
    }

    @Test
    void unmappableCharactersPreserveTheExistingFileAndRemoveTemporaryFiles() throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        FakeMetadata metadata = new FakeMetadata(snapshot(table), snapshot());
        metadata.createDdls.put(table, "CREATE TABLE \"TARGET\".\"ITEMS\" (\"漢\" NUMBER);");
        Path output = Files.writeString(directory.resolve("existing.sql"), "previous complete script\r\n");
        byte[] previous = Files.readAllBytes(output);

        assertThrows(CharacterCodingException.class,
                () -> comparator.writeSynchronizationScript(metadata, REFERENCE, TARGET, output));

        assertArrayEquals(previous, Files.readAllBytes(output));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(output), files.toList());
        }
    }

    @Test
    void comparesTheTargetToTheReferenceAndUsesTheConvertedAlterDdl() throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        FakeMetadata metadata = new FakeMetadata(snapshot(table), snapshot(table));
        metadata.change(Type.TABLE, "ALTER TABLE \"TARGET\".\"ITEMS\" ADD \"LABEL\" VARCHAR2(80);");

        String script = write(metadata, "direction.sql");

        assertEquals(List.of(new Comparison(Type.TABLE, "TARGET:TABLE:ITEMS", "REFERENCE:TABLE:ITEMS")),
                metadata.comparisons);
        assertTrue(script.contains("ALTER TABLE \"TARGET\".\"ITEMS\" ADD \"LABEL\" VARCHAR2(80);"));
        assertTrue(script.contains("ALTER SESSION SET CURRENT_SCHEMA = \"TARGET\";"));
        assertFalse(script.contains("CREATE TABLE"));
        assertFalse(script.contains("DROP TABLE"));
        assertEquals(Set.of("ITEMS"), metadata.checkedTables);
    }

    @Test
    void equalSchemasProduceNoObjectDdlIncludingForeignKeyOrViewRecompilation() throws Exception {
        Snapshot schema = snapshot(List.of(new ForeignKey("FK_CHILD_PARENT", "CHILD")), Map.of(),
                object(Type.SEQUENCE, "IDS"), object(Type.TABLE, "PARENT"), object(Type.TABLE, "CHILD"),
                new DbObject(Type.INDEX, "IX_CHILD", "CHILD"), object(Type.VIEW, "CHILD_VIEW"));
        FakeMetadata metadata = new FakeMetadata(schema, schema);

        String script = write(metadata, "noop.sql");

        assertTrue(script.contains("PROMPT Keine Unterschiede gefunden."));
        assertEquals(List.of(), objectStatements(script));
        assertTrue(metadata.checkedTables.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freesConstraintNamesBeforeMovingThemToExistingOrNewTables(boolean newTable) throws Exception {
        DbObject a = object(Type.TABLE, "A");
        DbObject b = object(Type.TABLE, "B");
        FakeMetadata metadata = new FakeMetadata(snapshot(a, b), newTable ? snapshot(b) : snapshot(a, b));
        String drop = "ALTER TABLE \"TARGET\".\"B\" DROP CONSTRAINT \"CK_VALUE\"";
        String add = "ALTER TABLE \"TARGET\".\"A\" ADD CONSTRAINT \"CK_VALUE\" CHECK (ID > 0)";
        metadata.change(b, drop);
        if (newTable) {
            metadata.createDdls.put(a, "CREATE TABLE \"TARGET\".\"A\" (ID NUMBER CONSTRAINT \"CK_VALUE\" CHECK (ID > 0));");
        } else {
            metadata.change(a, add);
        }

        String script = write(metadata, "moved-constraint-" + newTable + ".sql");

        assertBefore(script, drop, newTable ? "CREATE TABLE \"TARGET\".\"A\"" : add);
        assertEquals(1, script.lines().filter(line -> line.equals(drop + ";")).count());
        assertEquals(Set.of("A", "B"), metadata.checkedTables);
    }

    @Test
    void stagesMixedConstraintOperationsAndPreservesSqlWithSemicolons() throws Exception {
        DbObject a = object(Type.TABLE, "A");
        DbObject b = object(Type.TABLE, "B");
        FakeMetadata metadata = new FakeMetadata(snapshot(a, b), snapshot(a, b));
        String dropA = "ALTER TABLE \"TARGET\".\"A\" DROP CONSTRAINT \"CK_A\"";
        String dropB = "ALTER TABLE \"TARGET\".\"B\" DROP CONSTRAINT \"CK_B\"";
        String column = "ALTER TABLE \"TARGET\".\"A\" ADD LABEL VARCHAR2(40) DEFAULT 'a;b'";
        String addA = "ALTER TABLE \"TARGET\".\"A\" ADD CONSTRAINT \"CK_B\" CHECK (LABEL <> 'DROP CONSTRAINT;')";
        String addB = "ALTER TABLE \"TARGET\".\"B\" ADD CONSTRAINT \"CK_A\" CHECK (ID > 0)";
        metadata.change(a, column, dropA, addA);
        metadata.change(b, dropB, addB);

        String script = write(metadata, "mixed-constraints.sql");

        assertBefore(script, dropA, addB);
        assertBefore(script, dropB, column);
        assertBefore(script, column, addA);
        for (String sql : List.of(dropA, dropB, column, addA, addB)) {
            assertEquals(1, script.lines().filter(line -> line.equals(sql + ";")).count(), sql);
        }
        assertEquals(4, metadata.convertedDocuments.size(), "Zwei gemischte Dokumente erfordern je zwei Oracle-Konvertierungen.");
    }

    @Test
    void conversionFailureAfterConstraintDropsWerePlannedPreservesTheFile() throws Exception {
        DbObject table = object(Type.TABLE, "T");
        FakeMetadata metadata = new FakeMetadata(snapshot(table), snapshot(table));
        String add = "ALTER TABLE \"TARGET\".\"T\" ADD CONSTRAINT \"CK_NEW\" CHECK (ID > 0)";
        metadata.change(table, "ALTER TABLE \"TARGET\".\"T\" DROP CONSTRAINT \"CK_OLD\"", add);
        metadata.conversionFailureSql = add;
        Path output = Files.writeString(directory.resolve("preserved.sql"), "existing script");

        assertThrows(SQLException.class,
                () -> comparator.writeSynchronizationScript(metadata, REFERENCE, TARGET, output));

        assertEquals("existing script", Files.readString(output));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(output), files.toList());
        }
    }

    @Test
    void createsSequencesBeforeTablesThenIndexesForeignKeysAndDependentViews() throws Exception {
        DbObject baseView = object(Type.VIEW, "Z_BASE");
        DbObject dependentView = object(Type.VIEW, "A_DEPENDENT");
        Snapshot desired = snapshot(List.of(new ForeignKey("FK_CHILD_PARENT", "CHILD")),
                Map.of("A_DEPENDENT", Set.of("Z_BASE")),
                dependentView, new DbObject(Type.INDEX, "IX_CHILD", "CHILD"),
                object(Type.TABLE, "CHILD"), object(Type.TABLE, "PARENT"),
                baseView, object(Type.SEQUENCE, "IDS"));
        FakeMetadata metadata = new FakeMetadata(desired, snapshot());
        metadata.createDdls.put(baseView,
                "CREATE OR REPLACE VIEW \"TARGET\".\"Z_BASE\" AS SELECT ID FROM \"TARGET\".\"CHILD\";");
        metadata.createDdls.put(dependentView,
                "CREATE OR REPLACE VIEW \"TARGET\".\"A_DEPENDENT\" AS SELECT ID FROM \"TARGET\".\"Z_BASE\";");

        String script = write(metadata, "nested/create.sql");

        assertBefore(script, "CREATE SEQUENCE", "CREATE TABLE");
        assertBefore(script, "CREATE TABLE \"TARGET\".\"CHILD\"", "CREATE INDEX");
        assertBefore(script, "CREATE TABLE \"TARGET\".\"PARENT\"", "CREATE INDEX");
        assertBefore(script, "CREATE INDEX", "ADD CONSTRAINT \"FK_CHILD_PARENT\"");
        assertBefore(script, "ADD CONSTRAINT \"FK_CHILD_PARENT\"", "CREATE OR REPLACE VIEW");
        assertBefore(script, "CREATE OR REPLACE VIEW \"TARGET\".\"Z_BASE\"",
                "CREATE OR REPLACE VIEW \"TARGET\".\"A_DEPENDENT\"");
        assertBefore(script, "CREATE OR REPLACE VIEW \"TARGET\".\"A_DEPENDENT\"",
                "ALTER VIEW \"TARGET\".\"Z_BASE\" COMPILE;");
        assertBefore(script, "ALTER VIEW \"TARGET\".\"Z_BASE\" COMPILE;",
                "ALTER VIEW \"TARGET\".\"A_DEPENDENT\" COMPILE;");
        assertBefore(script, "ALTER VIEW \"TARGET\".\"A_DEPENDENT\" COMPILE;", "RAISE_APPLICATION_ERROR");
        assertTrue(script.contains("END;\r\n/"), "Der Validierungsblock braucht den SQL*Plus-Ausführungsterminator.");
    }

    @Test
    void createsAnAlphabeticallyEarlierChildWithoutItsForeignKeyUntilTheParentExists() throws Exception {
        ForeignKey key = new ForeignKey("FK_CHILD_PARENT", "A_CHILD");
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(key), Map.of(),
                object(Type.TABLE, "Z_PARENT"), object(Type.TABLE, "A_CHILD")), snapshot());
        metadata.foreignKeyParents.put(key.name(), "Z_PARENT");

        String script = write(metadata, "child-before-parent.sql");

        // Die Tabellen dürfen alphabetisch entstehen: Erst die getrennte FK-Phase braucht die Eltern.
        assertBefore(script, "CREATE TABLE \"TARGET\".\"A_CHILD\"", "CREATE TABLE \"TARGET\".\"Z_PARENT\"");
        assertForeignKeysAfter(script, metadata,
                "CREATE TABLE \"TARGET\".\"A_CHILD\" (ID NUMBER PRIMARY KEY);",
                "CREATE TABLE \"TARGET\".\"Z_PARENT\" (ID NUMBER PRIMARY KEY);");
    }

    @Test
    void createsAllTablesBeforeForeignKeysInAMultilevelChain() throws Exception {
        List<ForeignKey> keys = List.of(new ForeignKey("FK_LEAF_MIDDLE", "A_LEAF"),
                new ForeignKey("FK_MIDDLE_ROOT", "M_MIDDLE"));
        FakeMetadata metadata = new FakeMetadata(snapshot(keys, Map.of(),
                object(Type.TABLE, "A_LEAF"), object(Type.TABLE, "M_MIDDLE"), object(Type.TABLE, "Z_ROOT")), snapshot());
        metadata.foreignKeyParents.put("FK_LEAF_MIDDLE", "M_MIDDLE");
        metadata.foreignKeyParents.put("FK_MIDDLE_ROOT", "Z_ROOT");

        String script = write(metadata, "foreign-key-chain.sql");

        assertForeignKeysAfter(script, metadata,
                "CREATE TABLE \"TARGET\".\"A_LEAF\" (ID NUMBER PRIMARY KEY);",
                "CREATE TABLE \"TARGET\".\"M_MIDDLE\" (ID NUMBER PRIMARY KEY);",
                "CREATE TABLE \"TARGET\".\"Z_ROOT\" (ID NUMBER PRIMARY KEY);");
    }

    @Test
    void createsMutuallyReferencingTablesBeforeAddingEitherForeignKey() throws Exception {
        List<ForeignKey> keys = List.of(new ForeignKey("FK_A_B", "A"), new ForeignKey("FK_B_A", "B"));
        FakeMetadata metadata = new FakeMetadata(snapshot(keys, Map.of(),
                object(Type.TABLE, "A"), object(Type.TABLE, "B")), snapshot());
        metadata.foreignKeyParents.put("FK_A_B", "B");
        metadata.foreignKeyParents.put("FK_B_A", "A");

        String script = write(metadata, "cyclic-foreign-keys.sql");

        assertForeignKeysAfter(script, metadata,
                "CREATE TABLE \"TARGET\".\"A\" (ID NUMBER PRIMARY KEY);",
                "CREATE TABLE \"TARGET\".\"B\" (ID NUMBER PRIMARY KEY);");
    }

    @Test
    void addsASelfReferencingForeignKeyAfterCreatingItsTable() throws Exception {
        ForeignKey key = new ForeignKey("FK_NODE_NODE", "NODE");
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(key), Map.of(), object(Type.TABLE, "NODE")), snapshot());
        metadata.foreignKeyParents.put(key.name(), "NODE");

        String script = write(metadata, "self-referencing-table.sql");

        assertForeignKeysAfter(script, metadata, "CREATE TABLE \"TARGET\".\"NODE\" (ID NUMBER PRIMARY KEY);");
    }

    @Test
    void completesSeparatePrimaryAndUniqueKeyStatementsBeforeAnyForeignKey() throws Exception {
        DbObject parent = object(Type.TABLE, "Z_PARENT");
        List<ForeignKey> keys = List.of(new ForeignKey("FK_A_PARENT", "A_CHILD"), new ForeignKey("FK_B_PARENT", "B_CHILD"));
        FakeMetadata metadata = new FakeMetadata(snapshot(keys, Map.of(), parent,
                object(Type.TABLE, "A_CHILD"), object(Type.TABLE, "B_CHILD")), snapshot());
        metadata.foreignKeyParents.put("FK_A_PARENT", parent.name());
        metadata.foreignKeyParents.put("FK_B_PARENT", parent.name());
        String createParent = "CREATE TABLE \"TARGET\".\"Z_PARENT\" (PK_ID NUMBER, ID NUMBER);";
        String primaryKey = "ALTER TABLE \"TARGET\".\"Z_PARENT\" ADD CONSTRAINT \"PK_PARENT\" PRIMARY KEY (PK_ID);";
        String uniqueKey = "ALTER TABLE \"TARGET\".\"Z_PARENT\" ADD CONSTRAINT \"UK_PARENT\" UNIQUE (ID);";
        // CONSTRAINTS_AS_ALTER liefert die referenzierbaren Schlüssel nach CREATE im selben DDL-CLOB.
        metadata.createDdls.put(parent, String.join("\n", createParent, primaryKey, uniqueKey));

        String script = write(metadata, "separate-parent-keys.sql");

        assertBefore(script, createParent, primaryKey);
        assertBefore(script, primaryKey, uniqueKey);
        assertForeignKeysAfter(script, metadata, createParent, primaryKey, uniqueKey,
                "CREATE TABLE \"TARGET\".\"A_CHILD\" (ID NUMBER PRIMARY KEY);",
                "CREATE TABLE \"TARGET\".\"B_CHILD\" (ID NUMBER PRIMARY KEY);");
    }

    @Test
    void changesAnExistingParentKeyBeforeRestoringForeignKeysAndLinkingANewChild() throws Exception {
        DbObject parent = object(Type.TABLE, "Z_PARENT");
        DbObject existingChild = object(Type.TABLE, "B_EXISTING_CHILD");
        DbObject newChild = object(Type.TABLE, "A_NEW_CHILD");
        ForeignKey existingKey = new ForeignKey("FK_EXISTING_PARENT", existingChild.name());
        ForeignKey newKey = new ForeignKey("FK_NEW_PARENT", newChild.name());
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(existingKey, newKey), Map.of(), parent, existingChild, newChild),
                snapshot(List.of(existingKey), Map.of(), parent, existingChild));
        metadata.foreignKeyParents.put(existingKey.name(), parent.name());
        metadata.foreignKeyParents.put(newKey.name(), parent.name());
        String dropParentKey = "ALTER TABLE \"TARGET\".\"Z_PARENT\" DROP CONSTRAINT \"PK_OLD_PARENT\"";
        String addParentKey = "ALTER TABLE \"TARGET\".\"Z_PARENT\" ADD CONSTRAINT \"PK_PARENT\" PRIMARY KEY (ID)";
        metadata.change(parent, dropParentKey, addParentKey);

        String script = write(metadata, "changed-parent-key-and-new-child.sql");

        assertBefore(script, "ALTER TABLE \"TARGET\".\"B_EXISTING_CHILD\" DROP CONSTRAINT \"FK_EXISTING_PARENT\";", dropParentKey);
        assertBefore(script, dropParentKey, addParentKey);
        assertForeignKeysAfter(script, metadata, addParentKey + ";",
                "CREATE TABLE \"TARGET\".\"A_NEW_CHILD\" (ID NUMBER PRIMARY KEY);");
        assertFalse(script.contains("CREATE TABLE \"TARGET\".\"Z_PARENT\""));
        assertFalse(script.contains("CREATE TABLE \"TARGET\".\"B_EXISTING_CHILD\""));
    }

    @Test
    void dropsExtraObjectsInDependencyOrderAndAvoidsDroppingAnIndexTwice() throws Exception {
        DbObject retained = object(Type.TABLE, "KEEP");
        Snapshot actual = snapshot(List.of(new ForeignKey("FK_OLD_KEEP", "OBSOLETE")),
                Map.of("A_DEPENDENT", Set.of("Z_BASE")),
                retained, object(Type.TABLE, "OBSOLETE"), object(Type.SEQUENCE, "OLD_IDS"),
                new DbObject(Type.INDEX, "IX_EXTRA", "KEEP"),
                new DbObject(Type.INDEX, "IX_REMOVED_WITH_TABLE", "OBSOLETE"),
                object(Type.VIEW, "Z_BASE"), object(Type.VIEW, "A_DEPENDENT"));
        FakeMetadata metadata = new FakeMetadata(snapshot(retained), actual);
        metadata.foreignKeyParents.put("FK_OLD_KEEP", "KEEP");

        String script = write(metadata, "drop.sql");

        assertBefore(script, "DROP CONSTRAINT \"FK_OLD_KEEP\"", "DROP VIEW \"TARGET\".\"A_DEPENDENT\"");
        assertBefore(script, "DROP VIEW \"TARGET\".\"A_DEPENDENT\"", "DROP VIEW \"TARGET\".\"Z_BASE\"");
        assertBefore(script, "DROP VIEW \"TARGET\".\"Z_BASE\"", "DROP INDEX \"TARGET\".\"IX_EXTRA\"");
        assertBefore(script, "DROP INDEX \"TARGET\".\"IX_EXTRA\"", "DROP TABLE \"TARGET\".\"OBSOLETE\"");
        assertBefore(script, "DROP TABLE \"TARGET\".\"OBSOLETE\"", "DROP SEQUENCE \"TARGET\".\"OLD_IDS\"");
        assertFalse(script.contains("DROP INDEX \"TARGET\".\"IX_REMOVED_WITH_TABLE\""));
        assertFalse(script.contains("DROP TABLE \"TARGET\".\"KEEP\""));
        assertEquals(Set.of("OBSOLETE"), metadata.checkedTables);
    }

    @ParameterizedTest
    @EnumSource(value = Type.class, names = {"TABLE", "SEQUENCE"})
    void unsupportedChangesPreserveTheExistingFileAndDoNotLeaveTemporaryFiles(Type type) throws Exception {
        DbObject existing = object(type, "UNSUPPORTED");
        FakeMetadata metadata = new FakeMetadata(snapshot(object(Type.SEQUENCE, "NEW_IDS"), existing), snapshot(existing));
        metadata.alterXmlByType.put(type, unsupported("column conversion cannot be expressed as ALTER"));
        Path output = Files.writeString(directory.resolve("existing.sql"), "previous complete script\n");

        SQLException failure = assertThrows(SQLException.class,
                () -> comparator.writeSynchronizationScript(metadata, REFERENCE, TARGET, output));

        assertTrue(failure.getMessage().contains("UNSUPPORTED"));
        assertTrue(failure.getMessage().contains("column conversion"));
        assertEquals("previous complete script\n", Files.readString(output));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(output), files.toList());
        }
    }

    @Test
    void replacesAnIndexWhenItsDefinitionCannotBeAltered() throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        DbObject index = new DbObject(Type.INDEX, "IX_ITEMS", "ITEMS");
        FakeMetadata metadata = new FakeMetadata(snapshot(table, index), snapshot(table, index));
        metadata.alterXmlByType.put(Type.INDEX, unsupported("index column list"));
        metadata.createDdls.put(index, "CREATE INDEX \"TARGET\".\"IX_ITEMS\" ON \"TARGET\".\"ITEMS\" (ID, LABEL);");

        String script = write(metadata, "index.sql");

        assertBefore(script, "DROP INDEX \"TARGET\".\"IX_ITEMS\";",
                "CREATE INDEX \"TARGET\".\"IX_ITEMS\" ON \"TARGET\".\"ITEMS\" (ID, LABEL);");
        assertFalse(script.contains("ALTER INDEX"));
        assertFalse(script.contains("DROP TABLE"));
    }

    @Test
    void recreatesAnUnchangedIndependentIndexAroundTableDdl() throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        DbObject index = new DbObject(Type.INDEX, "IX_ITEMS", "ITEMS");
        Snapshot schema = snapshot(table, index);
        FakeMetadata metadata = new FakeMetadata(schema, schema);
        metadata.change(Type.TABLE, "ALTER TABLE \"TARGET\".\"ITEMS\" DROP COLUMN ID;\n"
                + "ALTER TABLE \"TARGET\".\"ITEMS\" ADD ID NUMBER;");

        String script = write(metadata, "table-index.sql");

        assertBefore(script, "DROP INDEX \"TARGET\".\"IX_ITEMS\";",
                "ALTER TABLE \"TARGET\".\"ITEMS\" DROP COLUMN ID;");
        assertBefore(script, "ALTER TABLE \"TARGET\".\"ITEMS\" ADD ID NUMBER;",
                "CREATE INDEX \"TARGET\".\"IX_ITEMS\" ON \"TARGET\".\"ITEMS\" (ID);");
        assertEquals(1, script.lines().filter(line -> line.equals("DROP INDEX \"TARGET\".\"IX_ITEMS\";")).count());
        assertEquals(1, script.lines().filter(line -> line.startsWith("CREATE INDEX \"TARGET\".\"IX_ITEMS\"")).count());
    }

    @Test
    void releasesAFormerPrimaryKeyIndexAfterTheConstraintChangeBeforeCreatingAnIndependentIndex() throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        DbObject index = new DbObject(Type.INDEX, "PK_ITEMS", "ITEMS");
        Snapshot actual = new Snapshot(snapshot(table).objects(), List.of(), Map.of(), Map.of("PK_ITEMS", "ITEMS"));
        FakeMetadata metadata = new FakeMetadata(snapshot(table, index), actual);
        metadata.change(Type.TABLE, "ALTER TABLE \"TARGET\".\"ITEMS\" DROP PRIMARY KEY;");

        String script = write(metadata, "constraint-index.sql");

        assertBefore(script, "ALTER TABLE \"TARGET\".\"ITEMS\" DROP PRIMARY KEY;",
                "EXECUTE IMMEDIATE 'DROP INDEX \"TARGET\".\"PK_ITEMS\"';");
        assertBefore(script, "EXECUTE IMMEDIATE 'DROP INDEX \"TARGET\".\"PK_ITEMS\"';",
                "CREATE INDEX \"TARGET\".\"PK_ITEMS\"");
        assertTrue(script.contains("IF SQLCODE <> -1418 THEN RAISE; END IF;"),
                "Nur ein bereits von DROP PRIMARY KEY entfernter Index darf ignoriert werden.");
        assertTrue(script.contains("END;\r\n/\r\n\r\nCREATE INDEX"), "Der geschützte DROP muss vor CREATE ausgeführt werden.");
        assertFalse(script.lines().anyMatch(line -> line.equals("DROP INDEX \"TARGET\".\"PK_ITEMS\";")));
    }

    @ParameterizedTest
    @EnumSource(value = Type.class, names = {"INDEX", "VIEW"})
    void knownOracleNonAlterableErrorsUseTheObjectSpecificReplacement(Type type) throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        DbObject changed = new DbObject(type, "CHANGED", type == Type.INDEX ? "ITEMS" : null);
        Snapshot schema = snapshot(table, changed);
        FakeMetadata metadata = new FakeMetadata(schema, schema);
        int oracleCode = type == Type.INDEX ? 39287 : 39308;
        metadata.compareFailures.put(type, new SQLException("Oracle cannot express this difference as ALTER", "99999", oracleCode));

        String script = write(metadata, "nonalterable-" + type + ".sql");

        if (type == Type.INDEX) {
            assertBefore(script, "DROP INDEX \"TARGET\".\"CHANGED\";", "CREATE INDEX \"TARGET\".\"CHANGED\"");
        } else {
            assertTrue(script.contains("CREATE OR REPLACE VIEW \"TARGET\".\"CHANGED\""));
            assertFalse(script.contains("DROP VIEW"), "CREATE OR REPLACE muss bestehende View-Grants erhalten.");
        }
        assertEquals(List.of(changed), metadata.ddlRequests);
    }

    @ParameterizedTest
    @EnumSource(value = Type.class, names = {"INDEX", "VIEW"})
    void missingPrivilegesAbortWithoutAttemptingReplacementOrOverwritingTheFile(Type type) throws Exception {
        DbObject changed = new DbObject(type, "PROTECTED", type == Type.INDEX ? "ITEMS" : null);
        Snapshot schema = snapshot(object(Type.TABLE, "ITEMS"), changed);
        FakeMetadata metadata = new FakeMetadata(schema, schema);
        SQLException oracleFailure = new SQLException("ORA-01031: insufficient privileges", "42000", 1031);
        metadata.compareFailures.put(type, oracleFailure);
        Path output = Files.writeString(directory.resolve("protected.sql"), "previous complete script\n");

        SQLException failure = assertThrows(SQLException.class,
                () -> comparator.writeSynchronizationScript(metadata, REFERENCE, TARGET, output));

        assertEquals(1031, failure.getErrorCode());
        assertEquals("42000", failure.getSQLState());
        assertSame(oracleFailure, failure.getCause());
        assertTrue(metadata.ddlRequests.isEmpty(), "Zugriffsfehler erlauben keinen DROP/CREATE-Ersatz.");
        assertEquals("previous complete script\n", Files.readString(output));
    }

    @Test
    void recompilesUnchangedViewsAfterTableChangesAndChecksTheirValidity() throws Exception {
        Snapshot schema = snapshot(object(Type.TABLE, "ITEMS"), object(Type.VIEW, "ITEMS_VIEW"));
        FakeMetadata metadata = new FakeMetadata(schema, schema);
        metadata.change(Type.TABLE, "ALTER TABLE \"TARGET\".\"ITEMS\" ADD \"LABEL\" VARCHAR2(80);");

        String script = write(metadata, "view.sql");

        assertFalse(script.contains("CREATE OR REPLACE VIEW"));
        assertBefore(script, "ALTER TABLE", "ALTER VIEW \"TARGET\".\"ITEMS_VIEW\" COMPILE;");
        assertBefore(script, "ALTER VIEW \"TARGET\".\"ITEMS_VIEW\" COMPILE;", "SELECT COUNT(*) INTO invalid_count");
        assertTrue(script.contains("owner = 'TARGET' AND object_type = 'VIEW' AND status <> 'VALID'"));
        assertTrue(script.contains("RAISE_APPLICATION_ERROR(-20002"));
    }

    @Test
    void externalForeignKeysAbortBeforeCreatingTheOutputDirectory() throws Exception {
        DbObject retained = object(Type.TABLE, "ITEMS");
        FakeMetadata metadata = new FakeMetadata(snapshot(retained), snapshot(retained, object(Type.TABLE, "OLD_ITEMS")));
        metadata.change(Type.TABLE, "ALTER TABLE \"TARGET\".\"ITEMS\" ADD \"LABEL\" VARCHAR2(80);");
        metadata.externalFailure = new SQLException("OTHER.CHILD references TARGET.ITEMS", "42000", 20001);
        Path output = directory.resolve("not-created/sync.sql");

        SQLException failure = assertThrows(SQLException.class,
                () -> comparator.writeSynchronizationScript(metadata, REFERENCE, TARGET, output));

        assertSame(metadata.externalFailure, failure);
        assertEquals(Set.of("ITEMS", "OLD_ITEMS"), metadata.checkedTables);
        assertFalse(Files.exists(output.getParent()));
    }

    @Test
    void outputDoesNotDependOnDictionaryIterationOrder() throws Exception {
        List<DbObject> desired = List.of(object(Type.SEQUENCE, "Z_IDS"), object(Type.SEQUENCE, "A_IDS"),
                object(Type.TABLE, "Z_CHILD"), object(Type.TABLE, "A_PARENT"), object(Type.TABLE, "PARENT"),
                new DbObject(Type.INDEX, "Z_INDEX", "Z_CHILD"), new DbObject(Type.INDEX, "A_INDEX", "A_PARENT"),
                object(Type.VIEW, "Z_VIEW"), object(Type.VIEW, "A_VIEW"));
        List<DbObject> actual = List.of(object(Type.SEQUENCE, "OLD_Z_IDS"), object(Type.SEQUENCE, "OLD_A_IDS"),
                object(Type.TABLE, "OLD_Z_TABLE"), object(Type.TABLE, "OLD_A_TABLE"), object(Type.TABLE, "PARENT"),
                object(Type.VIEW, "OLD_Z_VIEW"), object(Type.VIEW, "OLD_A_VIEW"));
        List<ForeignKey> desiredKeys = List.of(new ForeignKey("Z_FK", "Z_CHILD"), new ForeignKey("A_FK", "A_PARENT"));
        List<ForeignKey> actualKeys = List.of(new ForeignKey("OLD_Z_FK", "OLD_Z_TABLE"), new ForeignKey("OLD_A_FK", "OLD_A_TABLE"));
        FakeMetadata forward = new FakeMetadata(snapshot(desiredKeys, Map.of(), desired.toArray(DbObject[]::new)),
                snapshot(actualKeys, Map.of(), actual.toArray(DbObject[]::new)));
        FakeMetadata reverse = new FakeMetadata(snapshot(reversed(desiredKeys), Map.of(), reversed(desired).toArray(DbObject[]::new)),
                snapshot(reversed(actualKeys), Map.of(), reversed(actual).toArray(DbObject[]::new)));

        assertEquals(write(forward, "forward.sql"), write(reverse, "reverse.sql"));
    }

    @ParameterizedTest
    @EnumSource(Type.class)
    void excludesExactAndWildcardNamesFromCreateAlterAndDropForEveryObjectType(Type type) throws Exception {
        DbObject base = object(Type.TABLE, "BASE");
        String tableName = type == Type.INDEX ? base.name() : null;
        DbObject exact = new DbObject(type, "EXACT", tableName);
        DbObject ignoredNew = new DbObject(type, "SKIP_NEW", tableName);
        DbObject ignoredOld = new DbObject(type, "SKIP_OLD", tableName);
        DbObject ignoredChanged = new DbObject(type, "SKIP_CHANGED", tableName);
        DbObject created = new DbObject(type, "INCLUDED_NEW", tableName);
        DbObject removed = new DbObject(type, "INCLUDED_OLD", tableName);
        DbObject changed = new DbObject(type, "INCLUDED_CHANGED", tableName);
        FakeMetadata metadata = new FakeMetadata(snapshot(base, exact, ignoredNew, ignoredChanged, created, changed),
                snapshot(base, ignoredOld, ignoredChanged, removed, changed));
        String alteration = "ALTER " + type + " \"TARGET\".\"INCLUDED_CHANGED\" "
                + (type == Type.SEQUENCE ? "CACHE 50" : "ADD LABEL VARCHAR2(40)");
        metadata.change(changed, alteration);

        String script = write(metadata, "exclusions-" + type + ".sql", List.of("eXaCt", "sKiP_*"));

        for (DbObject excluded : List.of(exact, ignoredNew, ignoredOld, ignoredChanged)) {
            assertFalse(script.contains(excluded.name()), excluded.name());
            assertFalse(metadata.ddlRequests.contains(excluded), "Ausgeschlossene Objekte brauchen kein CREATE-DDL.");
            assertFalse(metadata.sxmlRequests.contains(excluded), "Ausgeschlossene Objekte brauchen kein Vergleichs-SXML.");
        }
        assertTrue(script.contains("\"INCLUDED_NEW\""));
        assertTrue(script.contains("DROP " + type + " \"TARGET\".\"INCLUDED_OLD\";"));
        assertTrue(script.contains(type == Type.TABLE || type == Type.SEQUENCE ? alteration
                : "CREATE " + (type == Type.VIEW ? "OR REPLACE VIEW" : "INDEX") + " \"TARGET\".\"INCLUDED_CHANGED\""));
        assertTrue(metadata.ddlRequests.contains(created));
        assertEquals(1, metadata.comparisons.stream()
                .filter(comparison -> comparison.actual().equals("TARGET:" + type + ":INCLUDED_CHANGED")).count());
    }

    @Test
    void excludedTableAlsoProtectsItsIndexesAndForeignKeysDuringOtherTableChanges() throws Exception {
        DbObject archived = object(Type.TABLE, "ARCHIVE");
        DbObject changing = object(Type.TABLE, "CHANGING");
        DbObject parent = object(Type.TABLE, "SAFE_PARENT");
        DbObject protectedIndex = new DbObject(Type.INDEX, "IX_ARCHIVE", archived.name());
        ForeignKey protectedKey = new ForeignKey("FK_ARCHIVE_PARENT", archived.name(), TARGET, parent.name());
        ForeignKey activeKey = new ForeignKey("FK_CHANGING_PARENT", changing.name(), TARGET, parent.name());
        Snapshot actual = snapshot(List.of(protectedKey, activeKey), Map.of(), archived, changing, parent, protectedIndex);
        Snapshot desired = snapshot(List.of(
                new ForeignKey(protectedKey.name(), archived.name(), REFERENCE, parent.name()),
                new ForeignKey(activeKey.name(), changing.name(), REFERENCE, parent.name())), Map.of(),
                archived, changing, parent, protectedIndex);
        FakeMetadata metadata = new FakeMetadata(desired, actual);
        metadata.foreignKeyParents.put(protectedKey.name(), parent.name());
        metadata.foreignKeyParents.put(activeKey.name(), parent.name());
        metadata.change(changing, "ALTER TABLE \"TARGET\".\"CHANGING\" ADD LABEL VARCHAR2(40)");

        String script = write(metadata, "excluded-table-dependencies.sql", List.of("archive"));

        assertFalse(script.contains("ARCHIVE"));
        assertFalse(metadata.sxmlRequests.contains(archived));
        assertFalse(metadata.sxmlRequests.contains(protectedIndex));
        assertFalse(metadata.foreignKeyRequests.stream().anyMatch(key -> key.name().equals(protectedKey.name())));
        assertTrue(script.contains("DROP CONSTRAINT \"FK_CHANGING_PARENT\";"));
        assertTrue(script.contains("ADD CONSTRAINT \"FK_CHANGING_PARENT\""));
        assertEquals(Set.of(changing.name()), metadata.checkedTables);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void excludedIndependentIndexBlocksAlterOrDropOfItsTableAndPreservesTheFile(boolean removeTable) throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        DbObject index = new DbObject(Type.INDEX, "IX_PROTECTED", table.name());
        FakeMetadata metadata = new FakeMetadata(removeTable ? snapshot() : snapshot(table, index), snapshot(table, index));
        if (!removeTable) metadata.change(table, "ALTER TABLE \"TARGET\".\"ITEMS\" DROP COLUMN LABEL");
        Path output = Files.writeString(directory.resolve("protected-index.sql"), "previous complete script");

        SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                metadata, REFERENCE, TARGET, output, List.of("ix_protected")));

        assertTrue(failure.getMessage().contains(index.name()));
        assertTrue(failure.getMessage().contains(table.name()));
        assertEquals("previous complete script", Files.readString(output));
        assertFalse(metadata.sxmlRequests.contains(index));
        assertFalse(metadata.ddlRequests.contains(index));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(output), files.toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void excludedPrimaryKeyIndexBlocksChangesAndCreationThroughTableDdl(boolean newTable) throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        Snapshot keyedTable = new Snapshot(snapshot(table).objects(), List.of(), Map.of(), Map.of("PK_ITEMS", table.name()));
        FakeMetadata metadata = new FakeMetadata(newTable ? keyedTable : snapshot(table), newTable ? snapshot() : keyedTable);
        if (!newTable) metadata.change(table, "ALTER TABLE \"TARGET\".\"ITEMS\" DROP PRIMARY KEY");
        Path output = Files.writeString(directory.resolve("protected-pk.sql"), "previous complete script");

        SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                metadata, REFERENCE, TARGET, output, List.of("pk_*")));

        assertTrue(failure.getMessage().contains("PK_ITEMS"));
        assertTrue(failure.getMessage().contains(table.name()));
        assertEquals("previous complete script", Files.readString(output));
    }

    @Test
    void foreignKeyFromAnExcludedChildBlocksChangesToItsReferencedParent() throws Exception {
        DbObject child = object(Type.TABLE, "KEEP_CHILD");
        DbObject parent = object(Type.TABLE, "PARENT");
        ForeignKey actualKey = new ForeignKey("FK_CHILD_PARENT", child.name(), TARGET, parent.name());
        ForeignKey desiredKey = new ForeignKey(actualKey.name(), child.name(), REFERENCE, parent.name());
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(desiredKey), Map.of(), child, parent),
                snapshot(List.of(actualKey), Map.of(), child, parent));
        metadata.change(parent, "ALTER TABLE \"TARGET\".\"PARENT\" DROP PRIMARY KEY");
        Path output = Files.writeString(directory.resolve("protected-child.sql"), "previous complete script");

        SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                metadata, REFERENCE, TARGET, output, List.of("keep_*")));

        assertTrue(failure.getMessage().contains(actualKey.name()));
        assertTrue(failure.getMessage().contains(parent.name()));
        assertEquals("previous complete script", Files.readString(output));
        assertTrue(metadata.foreignKeyRequests.isEmpty());
    }

    @Test
    void foreignKeyCannotReferenceAnExcludedParentThatDoesNotExistInTheTarget() throws Exception {
        DbObject child = object(Type.TABLE, "CHILD");
        DbObject parent = object(Type.TABLE, "KEEP_PARENT");
        ForeignKey key = new ForeignKey("FK_CHILD_PARENT", child.name(), REFERENCE, parent.name());
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(key), Map.of(), child, parent), snapshot());
        Path output = directory.resolve("not-created/protected-parent.sql");

        SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                metadata, REFERENCE, TARGET, output, List.of("KEEP_PARENT")));

        assertTrue(failure.getMessage().contains(key.name()));
        assertTrue(failure.getMessage().contains(parent.name()));
        assertFalse(Files.exists(output.getParent()));
        assertFalse(metadata.ddlRequests.contains(parent));
    }

    @Test
    void foreignKeyMayReferenceAnExcludedParentThatAlreadyExistsInTheTarget() throws Exception {
        DbObject child = object(Type.TABLE, "CHILD");
        DbObject parent = object(Type.TABLE, "KEEP_PARENT");
        ForeignKey key = new ForeignKey("FK_CHILD_PARENT", child.name(), REFERENCE, parent.name());
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(key), Map.of(), child, parent), snapshot(parent));
        metadata.foreignKeyParents.put(key.name(), parent.name());

        String script = write(metadata, "existing-excluded-parent.sql", List.of("KEEP_PARENT"));

        assertTrue(script.contains("CREATE TABLE \"TARGET\".\"CHILD\""));
        assertTrue(script.contains("REFERENCES \"TARGET\".\"KEEP_PARENT\""));
        assertFalse(script.contains("CREATE TABLE \"TARGET\".\"KEEP_PARENT\""));
        assertFalse(metadata.sxmlRequests.contains(parent));
        assertEquals(List.of(child), metadata.ddlRequests);
    }

    @Test
    void excludedViewsAreNeitherCompiledNorValidatedAndTheirCyclesAreIgnored() throws Exception {
        DbObject table = object(Type.TABLE, "ITEMS");
        DbObject protectedA = object(Type.VIEW, "KEEP_A");
        DbObject protectedB = object(Type.VIEW, "KEEP_B");
        DbObject protectedNew = object(Type.VIEW, "KEEP_NEW");
        DbObject selected = object(Type.VIEW, "ITEMS_VIEW");
        Map<String, Set<String>> cycles = Map.of("KEEP_A", Set.of("KEEP_B"), "KEEP_B", Set.of("KEEP_A"));
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(), cycles, table, protectedA, protectedB, protectedNew, selected),
                snapshot(List.of(), cycles, table, protectedA, protectedB, selected));
        metadata.change(table, "ALTER TABLE \"TARGET\".\"ITEMS\" ADD LABEL VARCHAR2(40)");

        String script = write(metadata, "excluded-views.sql", List.of("keep_*"));

        assertFalse(script.contains("KEEP_"));
        assertFalse(metadata.sxmlRequests.contains(protectedA));
        assertFalse(metadata.sxmlRequests.contains(protectedB));
        assertFalse(metadata.ddlRequests.contains(protectedNew));
        assertTrue(script.contains("ALTER VIEW \"TARGET\".\"ITEMS_VIEW\" COMPILE;"));
        assertTrue(script.contains("object_name IN ('ITEMS_VIEW')"));
    }

    @Test
    void newTableCannotReuseAConstraintNameOwnedByAnExcludedTargetTable() throws Exception {
        DbObject protectedTable = object(Type.TABLE, "KEEP_TABLE");
        DbObject newTable = object(Type.TABLE, "NEW_TABLE");
        Snapshot desired = new Snapshot(snapshot(newTable).objects(), List.of(), Map.of(), Map.of(),
                Map.of("C_SHARED", newTable.name()));
        Snapshot actual = new Snapshot(snapshot(protectedTable).objects(), List.of(), Map.of(), Map.of(),
                Map.of("C_SHARED", protectedTable.name()));
        FakeMetadata metadata = new FakeMetadata(desired, actual);
        metadata.createDdls.put(newTable, "CREATE TABLE \"TARGET\".\"NEW_TABLE\" "
                + "(ID NUMBER CONSTRAINT \"C_SHARED\" CHECK (ID > 0));");
        Path output = Files.writeString(directory.resolve("constraint-collision.sql"), "previous complete script");

        SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                metadata, REFERENCE, TARGET, output, List.of("KEEP_*")));

        assertTrue(failure.getMessage().contains("C_SHARED"));
        assertTrue(failure.getMessage().contains(protectedTable.name()));
        assertEquals("previous complete script", Files.readString(output));
    }

    @Test
    void foreignKeyOnlyChangeCannotReuseACheckConstraintNameOnAnExcludedTargetTable() throws Exception {
        DbObject protectedTable = object(Type.TABLE, "KEEP_TABLE");
        DbObject child = object(Type.TABLE, "CHILD");
        DbObject parent = object(Type.TABLE, "PARENT");
        ForeignKey key = new ForeignKey("C_SHARED", child.name(), REFERENCE, parent.name());
        Snapshot desired = new Snapshot(snapshot(child, parent).objects(), List.of(key), Map.of(), Map.of(),
                Map.of(key.name(), child.name()));
        Snapshot actual = new Snapshot(snapshot(protectedTable, child, parent).objects(), List.of(), Map.of(), Map.of(),
                Map.of(key.name(), protectedTable.name()));
        FakeMetadata metadata = new FakeMetadata(desired, actual);
        Path output = Files.writeString(directory.resolve("fk-constraint-collision.sql"), "previous complete script");

        SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                metadata, REFERENCE, TARGET, output, List.of("KEEP_*")));

        assertTrue(failure.getMessage().contains(key.name()));
        assertTrue(failure.getMessage().contains(protectedTable.name()));
        assertTrue(metadata.ddlRequests.isEmpty(), "Die Tabellen selbst bleiben bei einer reinen FK-Änderung unverändert.");
        assertEquals("previous complete script", Files.readString(output));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedViewMayDependOnAnExcludedViewOnlyIfThatViewExistsInTheTarget(boolean parentExists) throws Exception {
        DbObject protectedView = object(Type.VIEW, "KEEP_VIEW");
        DbObject selectedView = object(Type.VIEW, "SELECTED_VIEW");
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(), Map.of(selectedView.name(), Set.of(protectedView.name())),
                protectedView, selectedView), parentExists ? snapshot(protectedView) : snapshot());
        metadata.createDdls.put(selectedView, "CREATE OR REPLACE VIEW \"TARGET\".\"SELECTED_VIEW\" "
                + "AS SELECT ID FROM \"TARGET\".\"KEEP_VIEW\";");
        Path output = directory.resolve("view-dependency.sql");

        if (parentExists) {
            String script = write(metadata, output.getFileName().toString(), List.of("KEEP_VIEW"));
            assertTrue(script.contains("CREATE OR REPLACE VIEW \"TARGET\".\"SELECTED_VIEW\""));
            assertTrue(script.contains("ALTER VIEW \"TARGET\".\"SELECTED_VIEW\" COMPILE;"));
            assertFalse(script.contains("ALTER VIEW \"TARGET\".\"KEEP_VIEW\" COMPILE;"));
            assertFalse(script.contains("CREATE OR REPLACE VIEW \"TARGET\".\"KEEP_VIEW\""));
            assertTrue(script.contains("object_name IN ('SELECTED_VIEW')"));
        } else {
            SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                    metadata, REFERENCE, TARGET, output, List.of("KEEP_VIEW")));
            assertTrue(failure.getMessage().contains(protectedView.name()));
            assertTrue(failure.getMessage().contains(selectedView.name()));
            assertFalse(Files.exists(output));
        }
        assertFalse(metadata.ddlRequests.contains(protectedView));
        assertFalse(metadata.sxmlRequests.contains(protectedView));
    }

    @ParameterizedTest
    @CsvSource({"TABLE,false,false", "TABLE,false,true", "TABLE,true,false", "TABLE,true,true",
            "VIEW,false,false", "VIEW,false,true", "VIEW,true,false", "VIEW,true,true"})
    void protectsExcludedTargetViewsAgainstDirectAndIndirectBaseChanges(Type baseType, boolean modify,
                                                                       boolean indirect) throws Exception {
        DbObject base = object(baseType, "BASE_OBJECT");
        DbObject protectedView = object(Type.VIEW, "KEEP_VIEW");
        DbObject bridge = object(Type.VIEW, "BRIDGE_VIEW");
        Map<String, Set<String>> dependencies = indirect
                ? Map.of(protectedView.name(), Set.of(bridge.name()), bridge.name(), Set.of(base.name()))
                : Map.of(protectedView.name(), Set.of(base.name()));
        List<DbObject> actualObjects = new ArrayList<>(List.of(base, protectedView));
        List<DbObject> desiredObjects = new ArrayList<>();
        if (modify) desiredObjects.add(base);
        if (indirect) {
            actualObjects.add(bridge);
            desiredObjects.add(bridge);
        }
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(), dependencies, desiredObjects.toArray(DbObject[]::new)),
                snapshot(List.of(), dependencies, actualObjects.toArray(DbObject[]::new)));
        if (modify) {
            if (baseType == Type.TABLE) metadata.change(base, "ALTER TABLE \"TARGET\".\"BASE_OBJECT\" DROP COLUMN LABEL");
            else metadata.alterXmlByActual.put(TARGET + ":" + base.type() + ":" + base.name(),
                    unsupported("Changed view projection"));
        }
        Path output = Files.writeString(directory.resolve("protected-view.sql"), "previous complete script");

        SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                metadata, REFERENCE, TARGET, output, List.of("keep_*")));

        assertTrue(failure.getMessage().contains(protectedView.name()));
        assertTrue(failure.getMessage().contains(base.name()));
        assertEquals("previous complete script", Files.readString(output));
        assertFalse(metadata.sxmlRequests.contains(protectedView));
        assertFalse(metadata.ddlRequests.contains(protectedView));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(output), files.toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedViewDependenciesAllowNoopAndUnrelatedChanges(boolean unrelatedChange) throws Exception {
        DbObject base = object(Type.TABLE, "BASE_TABLE");
        DbObject unrelated = object(Type.TABLE, "UNRELATED_TABLE");
        DbObject protectedView = object(Type.VIEW, "KEEP_VIEW");
        DbObject bridge = object(Type.VIEW, "BRIDGE_VIEW");
        Snapshot actual = snapshot(List.of(), Map.of(protectedView.name(), Set.of(bridge.name()),
                bridge.name(), Set.of(base.name())), base, protectedView, bridge, unrelated);
        FakeMetadata metadata = new FakeMetadata(actual, actual);
        if (unrelatedChange) metadata.change(unrelated, "ALTER TABLE \"TARGET\".\"UNRELATED_TABLE\" ADD LABEL VARCHAR2(40)");

        String script = write(metadata, "unaffected-view.sql", List.of("keep_*"));

        assertFalse(script.contains("KEEP_VIEW"));
        if (unrelatedChange) assertTrue(script.contains("ALTER TABLE \"TARGET\".\"UNRELATED_TABLE\""));
        else assertTrue(objectStatements(script).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedViewRequiresItsExcludedBaseTableInTheTarget(boolean parentExists) throws Exception {
        DbObject base = object(Type.TABLE, "KEEP_TABLE");
        DbObject view = object(Type.VIEW, "SELECTED_VIEW");
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(), Map.of(view.name(), Set.of(base.name())), base, view),
                parentExists ? snapshot(base) : snapshot());
        metadata.createDdls.put(view, "CREATE OR REPLACE VIEW \"TARGET\".\"SELECTED_VIEW\" AS SELECT ID FROM \"TARGET\".\"KEEP_TABLE\";");
        Path output = directory.resolve("excluded-base.sql");

        if (parentExists) {
            String script = write(metadata, output.getFileName().toString(), List.of("keep_*"));
            assertTrue(script.contains("CREATE OR REPLACE VIEW \"TARGET\".\"SELECTED_VIEW\""));
            assertFalse(script.contains("CREATE TABLE"));
        } else {
            SQLException failure = assertThrows(SQLException.class, () -> comparator.writeSynchronizationScript(
                    metadata, REFERENCE, TARGET, output, List.of("keep_*")));
            assertTrue(failure.getMessage().contains(view.name()));
            assertTrue(failure.getMessage().contains(base.name()));
            assertFalse(Files.exists(output));
        }
        assertFalse(metadata.sxmlRequests.contains(base));
        assertFalse(metadata.ddlRequests.contains(base));
    }

    @ParameterizedTest
    @CsvSource({"TABLE,VIEW", "VIEW,TABLE"})
    void excludedViewBaseMayHaveAnotherRelationTypeInTheTarget(Type desiredType, Type actualType) throws Exception {
        DbObject desiredBase = object(desiredType, "KEEP_DATA");
        DbObject actualBase = object(actualType, "KEEP_DATA");
        DbObject view = object(Type.VIEW, "SELECTED_VIEW");
        Map<String, Set<String>> dependencies = Map.of(view.name(), Set.of(desiredBase.name()));
        FakeMetadata metadata = new FakeMetadata(snapshot(List.of(), dependencies, desiredBase, view),
                snapshot(actualBase));
        metadata.createDdls.put(view, "CREATE OR REPLACE VIEW \"TARGET\".\"SELECTED_VIEW\" AS SELECT ID FROM \"TARGET\".\"KEEP_DATA\";");

        String script = write(metadata, "different-excluded-base-type.sql", List.of("keep_*"));

        assertTrue(script.contains(metadata.createDdls.get(view)));
        assertFalse(script.contains("CREATE TABLE"));
        assertFalse(script.contains("DROP "));
        assertEquals(List.of(view), metadata.ddlRequests);
        assertTrue(metadata.sxmlRequests.isEmpty());
    }

    private String write(FakeMetadata metadata, String fileName) throws Exception {
        Path output = directory.resolve(fileName);
        comparator.writeSynchronizationScript(metadata, REFERENCE, TARGET, output);
        return Files.readString(output, WINDOWS_1252);
    }

    private String write(FakeMetadata metadata, String fileName, List<String> excludedObjects) throws Exception {
        Path output = directory.resolve(fileName);
        comparator.writeSynchronizationScript(metadata, REFERENCE, TARGET, output, excludedObjects);
        return Files.readString(output, WINDOWS_1252);
    }

    private static DbObject object(Type type, String name) {
        return new DbObject(type, name, null);
    }

    private static Snapshot snapshot(DbObject... objects) {
        return snapshot(List.of(), Map.of(), objects);
    }

    /** Bewahrt absichtlich die Eingabereihenfolge, um die Sortierung des Planers zu prüfen. */
    private static Snapshot snapshot(List<ForeignKey> keys, Map<String, Set<String>> dependencies, DbObject... objects) {
        Map<Type, Map<String, DbObject>> byType = new EnumMap<>(Type.class);
        for (DbObject object : objects) {
            byType.computeIfAbsent(object.type(), ignored -> new LinkedHashMap<>()).put(object.name(), object);
        }
        return new Snapshot(byType, keys, dependencies);
    }

    private static <T> List<T> reversed(List<T> items) {
        List<T> result = new ArrayList<>(items);
        java.util.Collections.reverse(result);
        return result;
    }

    private static void assertBefore(String script, String first, String second) {
        int firstPosition = script.indexOf(first);
        int secondPosition = script.indexOf(second);
        assertTrue(firstPosition >= 0, () -> "Anweisung fehlt: " + first);
        assertTrue(secondPosition >= 0, () -> "Anweisung fehlt: " + second);
        assertTrue(firstPosition < secondPosition, () -> first + " muss vor " + second + " stehen.");
    }

    /** Jeder FK muss genau einmal und erst nach allen vorausgesetzten Tabellen-/Schlüsselanweisungen entstehen. */
    private static void assertForeignKeysAfter(String script, FakeMetadata metadata, String... prerequisites) {
        for (ForeignKey key : metadata.desired.foreignKeys()) {
            String sql = metadata.foreignKeyDdl(REFERENCE, TARGET, key);
            assertEquals(1, script.lines().filter(line -> line.equals(sql)).count(), key.name());
            for (String prerequisite : prerequisites) assertBefore(script, prerequisite, sql);
        }
    }

    private static List<String> objectStatements(String script) {
        return script.lines().filter(line -> line.startsWith("CREATE ") || line.startsWith("DROP ")
                || line.startsWith("ALTER TABLE ") || line.startsWith("ALTER INDEX ")
                || line.startsWith("ALTER SEQUENCE ") || line.startsWith("ALTER VIEW ")).toList();
    }

    private static String unsupported(String reason) {
        return "<ALTER_XML><NOT_ALTERABLE>" + reason + "</NOT_ALTERABLE></ALTER_XML>";
    }

    private record Comparison(Type type, String actual, String desired) { }

    /** Liefert kontrollierte Oracle-Ergebnisse; die Planung und Dateiausgabe bleiben vollständig echt. */
    private static final class FakeMetadata implements Metadata {
        final Snapshot desired;
        final Snapshot actual;
        final Map<Type, String> alterXmlByType = new EnumMap<>(Type.class);
        final Map<Type, String> alterDdls = new EnumMap<>(Type.class);
        final Map<String, String> alterXmlByActual = new LinkedHashMap<>();
        final Map<String, String> statementDdls = new LinkedHashMap<>();
        final List<String> convertedDocuments = new ArrayList<>();
        final Map<Type, SQLException> compareFailures = new EnumMap<>(Type.class);
        final Map<DbObject, String> createDdls = new LinkedHashMap<>();
        final Map<String, String> foreignKeyParents = new LinkedHashMap<>();
        final List<Comparison> comparisons = new ArrayList<>();
        final List<DbObject> sxmlRequests = new ArrayList<>();
        final List<DbObject> ddlRequests = new ArrayList<>();
        final List<ForeignKey> foreignKeyRequests = new ArrayList<>();
        Set<String> checkedTables;
        SQLException externalFailure;
        String conversionFailureSql;

        FakeMetadata(Snapshot desired, Snapshot actual) {
            this.desired = desired;
            this.actual = actual;
        }

        void change(Type type, String sql) {
            alterXmlByType.put(type, "<ALTER_XML><SQL_LIST_ITEM><TEXT>" + sql + "</TEXT></SQL_LIST_ITEM></ALTER_XML>");
            alterDdls.put(type, sql);
        }

        /** Modelliert Oracles Einzelstatements im XML, einschließlich gemischter SQL_LIST-Gruppen. */
        void change(DbObject object, String... statements) {
            StringBuilder xml = new StringBuilder("<ALTER_XML xmlns='http://xmlns.oracle.com/ku' version='1.0'><ALTER_LIST><ALTER_LIST_ITEM><SQL_LIST>");
            for (String sql : statements) {
                xml.append("<SQL_LIST_ITEM><TEXT>").append(sql.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                        .append("</TEXT></SQL_LIST_ITEM>");
                statementDdls.put(sql, sql + ";");
            }
            xml.append("</SQL_LIST></ALTER_LIST_ITEM></ALTER_LIST></ALTER_XML>");
            alterXmlByActual.put(TARGET + ":" + object.type() + ":" + object.name(), xml.toString());
        }

        @Override
        public Snapshot snapshot(String schema) {
            assertTrue(schema.equals(REFERENCE) || schema.equals(TARGET));
            return schema.equals(REFERENCE) ? desired : actual;
        }

        @Override
        public String sxml(String schema, String target, DbObject object) {
            assertEquals(TARGET, target, "Beide Metadatenstände müssen in das Zielschema remappt werden.");
            sxmlRequests.add(object);
            return schema + ":" + object.type() + ":" + object.name();
        }

        @Override
        public String ddl(String schema, String target, DbObject object) {
            assertEquals(REFERENCE, schema, "Neue Definitionen müssen aus der Referenz stammen.");
            assertEquals(TARGET, target);
            ddlRequests.add(object);
            if (createDdls.containsKey(object)) return createDdls.get(object);
            String name = SqlText.qualified(target, object.name());
            return switch (object.type()) {
                case SEQUENCE -> "CREATE SEQUENCE " + name + " START WITH 1;";
                case TABLE -> "CREATE TABLE " + name + " (ID NUMBER PRIMARY KEY);";
                case INDEX -> "CREATE INDEX " + name + " ON " + SqlText.qualified(target, object.tableName()) + " (ID);";
                case VIEW -> "CREATE OR REPLACE VIEW " + name + " AS SELECT 1 AS ID FROM DUAL;";
            };
        }

        @Override
        public String foreignKeyDdl(String schema, String target, ForeignKey key) {
            assertEquals(TARGET, target);
            foreignKeyRequests.add(key);
            return "ALTER TABLE " + SqlText.qualified(target, key.tableName())
                    + " ADD CONSTRAINT " + SqlText.identifier(key.name()) + " FOREIGN KEY (ID) REFERENCES "
                    + SqlText.qualified(target, foreignKeyParents.getOrDefault(key.name(), "PARENT")) + " (ID);";
        }

        @Override
        public String alterXml(Type type, String actual, String desired) throws SQLException {
            comparisons.add(new Comparison(type, actual, desired));
            if (compareFailures.containsKey(type)) throw compareFailures.get(type);
            if (alterXmlByActual.containsKey(actual)) return alterXmlByActual.get(actual);
            return alterXmlByType.getOrDefault(type, "<ALTER_XML/>");
        }

        @Override
        public String alterDdl(Type type, String alterXml) throws SQLException {
            if (!statementDdls.isEmpty()) {
                convertedDocuments.add(alterXml);
                try {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(true);
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(alterXml)));
                    var nodes = doc.getElementsByTagNameNS("*", "TEXT");
                    List<String> statements = new ArrayList<>();
                    for (int i = 0; i < nodes.getLength(); i++) {
                        String sql = nodes.item(i).getTextContent();
                        if (sql.equals(conversionFailureSql)) throw new SQLException("Conversion failed");
                        assertTrue(statementDdls.containsKey(sql), "Die Konvertierung muss vollständige, unveränderte Statements erhalten.");
                        statements.add(statementDdls.get(sql));
                    }
                    return String.join("\n", statements);
                } catch (SQLException e) {
                    throw e;
                } catch (Exception e) {
                    throw new AssertionError("Ungültiges Konvertierungsdokument", e);
                }
            }
            return alterDdls.get(type);
        }

        @Override
        public void checkExternalForeignKeys(String target, Set<String> changedTables) throws SQLException {
            assertEquals(TARGET, target);
            checkedTables = Set.copyOf(changedTables);
            if (externalFailure != null) throw externalFailure;
        }
    }
}
