package de.axa.oraclecompare;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static de.axa.oraclecompare.SchemaDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prüft ausführbare SQL-Bausteine anhand von Dictionary-Definitionen einschließlich Spezialfällen. */
class OracleSqlRendererTest {
    private final OracleSqlRenderer renderer = new OracleSqlRenderer("SOURCE", "TARGET");

    @Test void datatypeRenderingPreservesCharSemanticsAndUnboundedNumberScale() throws Exception {
        assertEquals("VARCHAR2(40 CHAR)", renderer.dataType(column("TEXT", "VARCHAR2", 160, null, null, "C", 40, null)));
        assertEquals("VARCHAR2(160 BYTE)", renderer.dataType(column("TEXT", "VARCHAR2", 160, null, null, "B", 40, null)));
        assertEquals("NUMBER(*,0)", renderer.dataType(column("ID", "NUMBER", 22, null, 0, null, null, null)));
        assertEquals("NUMBER(10,2)", renderer.dataType(column("ID", "NUMBER", 22, 10, 2, null, null, null)));
        assertEquals("TIMESTAMP(6) WITH LOCAL TIME ZONE", renderer.dataType(column("TS", "TIMESTAMP(6) WITH LOCAL TIME ZONE", null, null, null, null, null, null)));
    }

    @Test void defaultsRemapLocalSequenceButKeepLiteralContents() throws Exception {
        String ddl = renderer.createTable(table(List.of(
                column("ID", "NUMBER", 22, null, null, null, null, "\"SOURCE\".\"S\".NEXTVAL"),
                column("VALUE", "VARCHAR2", 80, null, null, "C", 20, "'SOURCE.S; bleibt'"))));
        assertTrue(ddl.contains("DEFAULT \"TARGET\".\"S\".NEXTVAL"));
        assertTrue(ddl.contains("'SOURCE.S; bleibt'"));
        assertEquals(1, OracleSqlScript.statements(ddl).size());
    }

    @Test void virtualColumnKeepsExplicitTypeCollationAndVisibility() throws Exception {
        Column column = new Column("CALC", "VARCHAR2", null, 80, null, null, "C", 20,
                "UPPER(\"TEXT\")", true, true, true, false, "BINARY_CI", null);
        assertEquals("\"CALC\" VARCHAR2(20 CHAR) COLLATE \"BINARY_CI\" INVISIBLE GENERATED ALWAYS AS (UPPER(\"TEXT\")) VIRTUAL",
                renderer.column(column));
    }

    @Test void externalTableQuotesLocationsAndUsesExportHost() throws Exception {
        Table table = new Table("EXT", List.of(column("ID", "NUMBER", 22, null, null, null, null, null)), List.of(), Map.of(), null,
                new ExternalTable("ORACLE_DATAPUMP", "EXPORT_HOST", "NOLOGFILE", "UNLIMITED",
                        List.of(new Location("EXPORT_HOST", "file'one.dmp"), new Location("OTHER_DIR", "two.dmp"))));
        String ddl = renderer.createTable(table);
        assertTrue(ddl.contains("TYPE ORACLE_DATAPUMP DEFAULT DIRECTORY \"EXPORT_HOST\""));
        assertTrue(ddl.contains("'file''one.dmp', \"OTHER_DIR\":'two.dmp'"));
        assertTrue(ddl.contains("REJECT LIMIT UNLIMITED"));
        assertEquals(1, OracleSqlScript.statements(ddl).size());
    }

    @Test void compositeRangeListKeepsSqlBoundsAndNamedChildren() throws Exception {
        Partitioning definition = new Partitioning("RANGE", List.of("BOOKED"), "LIST", List.of("REGION"), null, false,
                List.of(new Partition("P_NEXT", "DATE '2027-01-01'", Map.of("TABLESPACE_NAME", "DATA"),
                        List.of(new Partition("P_EU", "'EU'", Map.of("TABLESPACE_NAME", "EU_DATA"), List.of()),
                                new Partition("P_OTHER", "DEFAULT", Map.of(), List.of())))));
        String ddl = renderer.partitioning(definition, false);
        assertTrue(ddl.contains("PARTITION BY RANGE (\"BOOKED\") SUBPARTITION BY LIST (\"REGION\")"));
        assertTrue(ddl.contains("PARTITION \"P_NEXT\" VALUES LESS THAN (DATE '2027-01-01')"));
        assertTrue(ddl.contains("SUBPARTITION \"P_EU\" VALUES ('EU') TABLESPACE \"EU_DATA\""));
        assertTrue(ddl.contains("SUBPARTITION \"P_OTHER\" VALUES (DEFAULT)"));
    }

    @Test void localIndexDoesNotRepeatTablePartitionBounds() throws Exception {
        Partitioning partitioning = new Partitioning("RANGE", List.of("ID"), "NONE", List.of(), null, true,
                List.of(new Partition("P_MAX", "MAXVALUE", Map.of("TABLESPACE_NAME", "INDEX_DATA"), List.of())));
        Index index = new Index("IX", "SOURCE", "T", false, List.of(new IndexColumn("UPPER(\"NAME\")", true)),
                Map.of("INDEX_TYPE", "FUNCTION-BASED NORMAL", "VISIBILITY", "INVISIBLE"), partitioning);
        String ddl = renderer.index(index);
        assertTrue(ddl.contains("ON \"TARGET\".\"T\" (UPPER(\"NAME\") DESC)"));
        assertTrue(ddl.contains("LOCAL ("));
        assertTrue(ddl.contains("PARTITION \"P_MAX\" TABLESPACE \"INDEX_DATA\""));
        assertFalse(ddl.contains("VALUES LESS THAN"));
        assertTrue(ddl.endsWith("INVISIBLE;"));
        assertEquals(1, OracleSqlScript.statements(ddl).size());
    }

    @Test void foreignKeyRendersCompositeReferenceAndConstraintState() throws Exception {
        Constraint key = new Constraint("FK", "R", List.of("A", "B"), null, "SOURCE", "PARENT", List.of("X", "Y"),
                "SET NULL", true, true, false, false, true, false, null, null);
        String ddl = renderer.constraint("CHILD", key);
        assertTrue(ddl.contains("FOREIGN KEY (\"A\", \"B\") REFERENCES \"TARGET\".\"PARENT\" (\"X\", \"Y\") ON DELETE SET NULL"));
        assertTrue(ddl.contains("DEFERRABLE INITIALLY DEFERRED RELY DISABLE NOVALIDATE"));
        assertEquals(1, OracleSqlScript.statements(ddl).size());
    }

    @Test void secureFileLobRendersStorageCompressionDedupAndRetention() throws Exception {
        Lob lob = new Lob("DOCUMENT", null, Map.of("SECUREFILE", "YES", "TABLESPACE_NAME", "LOB_DATA",
                "COMPRESSION", "HIGH", "DEDUPLICATION", "LOB", "CACHE", "CACHEREADS", "RETENTION_TYPE", "MIN",
                "RETENTION_VALUE", "3600", "IN_ROW", "NO", "INITIAL_EXTENT", "65536"));
        Table table = new Table("T", List.of(column("DOCUMENT", "CLOB", null, null, null, null, null, null)),
                List.of(), Map.of(), null, null, List.of(lob));
        String ddl = renderer.createTable(table);
        assertTrue(ddl.contains("LOB (\"DOCUMENT\") STORE AS SECUREFILE"));
        assertTrue(ddl.contains("TABLESPACE \"LOB_DATA\""));
        assertTrue(ddl.contains("STORAGE (INITIAL 65536)"));
        assertTrue(ddl.contains("DISABLE STORAGE IN ROW CACHE READS COMPRESS HIGH DEDUPLICATE RETENTION MIN 3600"));
        assertEquals(1, OracleSqlScript.statements(ddl).size());
    }

    @Test void basicFileLobPreservesNamedSegmentChunkAndPctversion() throws Exception {
        String ddl = renderer.lob(new Lob("DOCUMENT", "DOC_LOB", Map.of("SECUREFILE", "NO", "PCTVERSION", "10",
                "CHUNK", "8192", "CACHE", "NO", "RETENTION_TYPE", "NO", "LOGGING", "YES")));
        assertTrue(ddl.contains("STORE AS BASICFILE \"DOC_LOB\""));
        assertTrue(ddl.contains("CHUNK 8192"));
        assertTrue(ddl.contains("PCTVERSION 10"));
        assertTrue(ddl.contains("NOCACHE"));
        assertFalse(ddl.contains("RETENTION"));
    }

    @Test void changesToLobStorageFailBeforeWritingDestructiveSql() {
        Table actual = new Table("T", List.of(column("D", "CLOB", null, null, null, null, null, null)), List.of(), Map.of(), null, null,
                List.of(new Lob("D", null, Map.of("SECUREFILE", "YES", "TABLESPACE_NAME", "OLD_LOB"))));
        Table desired = new Table("T", actual.columns(), List.of(), Map.of(), null, null,
                List.of(new Lob("D", null, Map.of("SECUREFILE", "YES", "TABLESPACE_NAME", "NEW_LOB"))));
        assertTrue(assertThrows(SQLException.class, () -> renderer.alterTable(actual, desired, new OracleSqlRenderer("TARGET", "TARGET")))
                .getMessage().contains("LOB"));
    }

    @Test void removingDefaultEmitsExplicitDefaultNullWithoutRebuildingTable() throws Exception {
        Table actual = table(List.of(column("ID", "NUMBER", 22, null, null, null, null, "1")));
        Table desired = table(List.of(column("ID", "NUMBER", 22, null, null, null, null, null)));
        List<String> statements = renderer.alterTable(actual, desired, new OracleSqlRenderer("TARGET", "TARGET"));
        assertEquals(List.of("ALTER TABLE \"TARGET\".\"T\" MODIFY (\"ID\" NUMBER DEFAULT NULL);"), statements);
    }

    @Test void trailingViewLineCommentCannotConsumeSqlTerminator() throws Exception {
        String ddl = renderer.view(new View("V", List.of("VALUE"), "SELECT 1 FROM dual -- erhalten\n"));
        assertEquals(1, OracleSqlScript.statements(ddl).size());
        assertTrue(ddl.contains("-- erhalten"));
    }

    @Test void alteringSequenceNeverResetsCurrentValue() throws Exception {
        Sequence sequence = new Sequence("S", "1", "9999999999999999999999999999", "2", "0", false, false, "9999", Map.of());
        String alter = renderer.sequence(sequence, false);
        assertFalse(alter.contains("START WITH"));
        assertTrue(alter.contains("INCREMENT BY 2"));
        assertTrue(alter.contains("NOCACHE"));
        assertTrue(renderer.sequence(sequence, true).contains("START WITH 9999"));
    }

    @Test void relyAndDeferrableNotNullExpressionsRemainCheckConstraints() throws Exception {
        for (Constraint constraint : List.of(nullCheck("CK_RELY", false, true), nullCheck("CK_DEFERRED", true, false))) {
            assertNull(OracleSqlRenderer.notNullColumn(constraint));
            String ddl = renderer.constraint("T", constraint);
            assertTrue(ddl.contains(" ADD CONSTRAINT "));
            assertTrue(ddl.contains("CHECK (\"ID\" IS NOT NULL)"));
            assertFalse(ddl.contains(" MODIFY "));
        }
    }

    @Test void rejectsAmbiguousNotNullDefinitionsBeforeTableCreation() {
        Table ambiguous = ambiguousNotNullTable();
        SQLException failure = assertThrows(SQLException.class, () -> renderer.createTable(ambiguous));
        assertTrue(failure.getMessage().contains("T.ID"));
        assertTrue(failure.getMessage().contains("mehrdeutige NOT-NULL-/CHECK"));
    }

    @Test void rejectsAmbiguousExistingOrDesiredNotNullDefinitionsBeforeAlter() {
        Table plain = table(ambiguousNotNullTable().columns());
        OracleSqlRenderer oldRenderer = new OracleSqlRenderer("TARGET", "TARGET");
        assertThrows(SQLException.class, () -> renderer.alterTable(plain, ambiguousNotNullTable(), oldRenderer));
        assertThrows(SQLException.class, () -> renderer.alterTable(ambiguousNotNullTable(), plain, oldRenderer));
    }

    @Test void standardTargetOmitsImplicitCollationButRejectsUnsupportedNamedCollation() throws Exception {
        OracleSqlRenderer standard = new OracleSqlRenderer("SOURCE", "TARGET", false);
        Column text = collated("USING_NLS_COMP", false);
        Table definition = new Table("T", List.of(text), List.of(), Map.of("DEFAULT_COLLATION", "USING_NLS_COMP"), null, null);
        String ddl = standard.createTable(definition);
        assertFalse(ddl.contains("COLLATE"));
        assertFalse(ddl.contains("COLLATION"));
        assertTrue(ddl.contains("VARCHAR2(30 CHAR)"));
        assertFalse(standard.column(collated("USING_NLS_COMP", true)).contains("COLLATE"));
        assertThrows(SQLException.class, () -> standard.column(collated("BINARY_CI", false)));
        assertThrows(SQLException.class, () -> standard.createTable(new Table("T", List.of(text), List.of(),
                Map.of("DEFAULT_COLLATION", "BINARY_CI"), null, null)));
    }

    @Test void extendedTargetPreservesExplicitCollationsIncludingVirtualColumns() throws Exception {
        OracleSqlRenderer extended = new OracleSqlRenderer("SOURCE", "TARGET", true);
        assertTrue(extended.column(collated("USING_NLS_COMP", false)).contains("COLLATE \"USING_NLS_COMP\""));
        assertTrue(extended.column(collated("USING_NLS_COMP", true)).contains("COLLATE \"USING_NLS_COMP\""));
        assertTrue(extended.column(collated("BINARY_CI", false)).contains("COLLATE \"BINARY_CI\""));
    }

    @Test void plannerUsesTargetCollationCapabilityForBothComparisonAndCreation() throws Exception {
        Table definition = new Table("T", List.of(collated("USING_NLS_COMP", false)), List.of(),
                Map.of("DEFAULT_COLLATION", "USING_NLS_COMP"), null, null);
        SchemaDefinition source = schema("SOURCE", Map.of("T", definition));
        SchemaComparisonPlanner planner = new SchemaComparisonPlanner(false);
        String script = planner.plan(source, schema("TARGET", Map.of()), ExclusionFilter.none());
        assertTrue(script.contains("CREATE TABLE"));
        assertFalse(script.contains("COLLATE"));
        assertFalse(script.contains("COLLATION"));
        assertTrue(planner.plan(source, schema("TARGET", Map.of("T", definition)), ExclusionFilter.none())
                .contains("Keine Unterschiede gefunden"));
    }

    @Test void dropsVirtualColumnBeforeItsPhysicalBaseColumn() throws Exception {
        Table actual = table(List.of(number("ID"), number("BASE"), virtual("V", "\"BASE\"+1")));
        List<String> sql = renderer.alterTable(actual, table(List.of(number("ID"))), new OracleSqlRenderer("TARGET", "TARGET"));
        assertEquals(List.of("ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"V\";",
                "ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"BASE\";"), sql);
    }

    @Test void replacingAllColumnsNeverLeavesTableWithoutColumns() throws Exception {
        List<String> sql = renderer.alterTable(table(List.of(number("OLD"))),
                table(List.of(number("NEW"))), new OracleSqlRenderer("TARGET", "TARGET"));
        assertEquals(List.of("ALTER TABLE \"TARGET\".\"T\" ADD (\"NEW\" NUMBER);",
                "ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"OLD\";"), sql);
    }

    @Test void addingPhysicalAndVirtualColumnsTogetherPreservesDesiredColumnOrder() throws Exception {
        List<String> sql = renderer.alterTable(table(List.of(number("OLD"))),
                table(List.of(number("BASE"), virtual("V", "\"BASE\"+1"))), new OracleSqlRenderer("TARGET", "TARGET"));
        assertEquals(2, sql.size());
        assertEquals("ALTER TABLE \"TARGET\".\"T\" ADD (\"BASE\" NUMBER, \"V\" NUMBER GENERATED ALWAYS AS (\"BASE\"+1) VIRTUAL);", sql.get(0));
        assertTrue(sql.get(1).contains("DROP COLUMN \"OLD\""));
    }

    @Test void replacingLongColumnDropsTheOldLongBeforeAddingItsReplacement() throws Exception {
        Column oldLong = column("OLD", "LONG", null, null, null, null, null, null);
        Column newLong = column("NEW", "LONG", null, null, null, null, null, null);
        List<String> sql = renderer.alterTable(table(List.of(number("ID"), oldLong)),
                table(List.of(number("ID"), newLong)), new OracleSqlRenderer("TARGET", "TARGET"));
        assertEquals(List.of("ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"OLD\";",
                "ALTER TABLE \"TARGET\".\"T\" ADD (\"NEW\" LONG);"), sql);
    }

    @Test void replacingOneOfThousandColumnsFreesItsSlotBeforeAdd() throws Exception {
        List<Column> actual = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) actual.add(number("C" + i));
        List<Column> desired = new java.util.ArrayList<>(actual.subList(1, actual.size()));
        desired.add(number("NEW"));
        List<String> sql = renderer.alterTable(table(actual), table(desired), new OracleSqlRenderer("TARGET", "TARGET"));
        assertEquals(List.of("ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"C0\";",
                "ALTER TABLE \"TARGET\".\"T\" ADD (\"NEW\" NUMBER);"), sql);
    }

    @Test void completeLongOnlyReplacementRequiresExplicitMigrationInsteadOfInvalidSql() {
        Column oldLong = column("OLD", "LONG", null, null, null, null, null, null);
        Column newLong = column("NEW", "LONG", null, null, null, null, null, null);
        SQLException failure = assertThrows(SQLException.class, () -> renderer.alterTable(table(List.of(oldLong)),
                table(List.of(newLong)), new OracleSqlRenderer("TARGET", "TARGET")));
        assertTrue(failure.getMessage().contains("Spaltenwechsel"));
    }

    @Test void retainedVirtualColumnDoesNotPermitDroppingTheLastPhysicalColumnBeforeAdd() throws Exception {
        Column virtual = virtual("V", "1");
        List<String> sql = renderer.alterTable(table(List.of(number("OLD"), virtual)),
                table(List.of(virtual, number("NEW"))), new OracleSqlRenderer("TARGET", "TARGET"));
        assertEquals(List.of("ALTER TABLE \"TARGET\".\"T\" ADD (\"NEW\" NUMBER);",
                "ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"OLD\";"), sql);
    }

    @Test void completeColumnReplacementKeepsAVisibleColumnRatherThanAnInvisibleNonLongColumn() throws Exception {
        Column invisible = new Column("HIDDEN", "NUMBER", null, 22, null, null, null, null, null,
                true, false, true, false, null, null);
        Column oldLong = column("OLD", "LONG", null, null, null, null, null, null);
        List<String> sql = renderer.alterTable(table(List.of(oldLong, invisible)), table(List.of(number("NEW"))),
                new OracleSqlRenderer("TARGET", "TARGET"));
        assertEquals(List.of("ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"HIDDEN\";",
                "ALTER TABLE \"TARGET\".\"T\" ADD (\"NEW\" NUMBER);",
                "ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"OLD\";"), sql);
    }

    @Test void removingTheLastVisibleVirtualColumnFailsBeforeWritingInvalidDdl() {
        Column invisible = new Column("HIDDEN", "NUMBER", null, 22, null, null, null, null, null,
                true, false, true, false, null, null);
        SQLException failure = assertThrows(SQLException.class, () -> renderer.alterTable(table(List.of(virtual("V", "1"), invisible)),
                table(List.of(number("NEW"))), new OracleSqlRenderer("TARGET", "TARGET")));
        assertTrue(failure.getMessage().contains("sichtbaren Spalten"));
    }

    @Test void readOnlyIsAppliedAfterColumnDropsAndRestoredForExistingReadOnlyTable() throws Exception {
        for (String initialMode : List.of("YES", "NO")) {
            Table actual = new Table("T", List.of(number("ID"), number("OLD")), List.of(), Map.of("READ_ONLY", initialMode), null, null);
            Table desired = new Table("T", List.of(number("ID")), List.of(), Map.of("READ_ONLY", "YES"), null, null);
            List<String> sql = renderer.alterTable(actual, desired, new OracleSqlRenderer("TARGET", "TARGET"));
            assertEquals("ALTER TABLE \"TARGET\".\"T\" READ ONLY;", sql.get(sql.size() - 1));
            assertEquals("ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"OLD\";", sql.get(sql.size() - 2));
            if (initialMode.equals("YES")) assertEquals("ALTER TABLE \"TARGET\".\"T\" READ WRITE;", sql.get(0));
            else assertEquals(2, sql.size());
        }
    }

    @Test void changingReadOnlyToReadWriteDoesNotRestoreOldMode() throws Exception {
        Table actual = new Table("T", List.of(number("ID"), number("OLD")), List.of(), Map.of("READ_ONLY", "YES"), null, null);
        Table desired = new Table("T", List.of(number("ID")), List.of(), Map.of("READ_ONLY", "NO"), null, null);
        List<String> sql = renderer.alterTable(actual, desired, new OracleSqlRenderer("TARGET", "TARGET"));
        assertEquals(List.of("ALTER TABLE \"TARGET\".\"T\" READ WRITE;", "ALTER TABLE \"TARGET\".\"T\" DROP COLUMN \"OLD\";"), sql);
    }

    @Test void globalHashIndexOnlyDeclaresTablespacesPerPartitionAndKeepsIndexCompression() throws Exception {
        Partitioning partitions = new Partitioning("HASH", List.of("ID"), "NONE", List.of(), null, false,
                List.of(new Partition("P1", null, Map.of("TABLESPACE_NAME", "INDEX_DATA", "COMPRESSION", "ENABLED"), List.of()),
                        new Partition("P2", null, Map.of("TABLESPACE_NAME", "INDEX_DATA", "COMPRESSION", "ENABLED"), List.of())));
        Index index = new Index("IX", "SOURCE", "T", false, List.of(new IndexColumn("\"ID\"", false)),
                Map.of("INDEX_TYPE", "NORMAL", "COMPRESSION", "ENABLED", "PREFIX_LENGTH", "1"), partitions);
        String ddl = renderer.index(index);
        assertTrue(ddl.contains("COMPRESS 1 GLOBAL PARTITION BY HASH"));
        assertTrue(ddl.contains("PARTITION \"P1\" TABLESPACE \"INDEX_DATA\",\n"));
        assertFalse(ddl.substring(ddl.indexOf("GLOBAL PARTITION")).contains("COMPRESS"));
    }

    @Test void globalHashIndexDoesNotSilentlyDiscardDifferentPartitionCompression() {
        Partitioning partitions = new Partitioning("HASH", List.of("ID"), "NONE", List.of(), null, false,
                List.of(new Partition("P1", null, Map.of("COMPRESSION", "DISABLED"), List.of())));
        Index index = new Index("IX", "SOURCE", "T", false, List.of(new IndexColumn("\"ID\"", false)),
                Map.of("INDEX_TYPE", "NORMAL", "COMPRESSION", "ENABLED", "PREFIX_LENGTH", "1"), partitions);
        SQLException failure = assertThrows(SQLException.class, () -> renderer.index(index));
        assertTrue(failure.getMessage().contains("IX.P1"));
        assertTrue(failure.getMessage().contains("Kompression"));
    }

    private static Column collated(String collation, boolean virtual) {
        return new Column("TEXT", "VARCHAR2", null, 120, null, null, "C", 30,
                virtual ? "CAST('text' AS VARCHAR2(30 CHAR))" : null, true, virtual, false, false, collation, null);
    }

    private static Column number(String name) { return column(name, "NUMBER", 22, null, null, null, null, null); }

    private static Column virtual(String name, String expression) {
        return new Column(name, "NUMBER", null, 22, null, null, null, null, expression, true, true, false, false, null, null);
    }

    private static SchemaDefinition schema(String owner, Map<String, Table> tables) {
        return new SchemaDefinition(owner, tables, Map.of(), Map.of(), Map.of(), Map.of(), List.of(), java.util.Set.of());
    }

    private static Table ambiguousNotNullTable() {
        return new Table("T", List.of(column("ID", "NUMBER", 22, null, null, null, null, null)),
                List.of(nullCheck("ID_NN", false, false), nullCheck("ID_CK", false, false)), Map.of(), null, null);
    }

    private static Constraint nullCheck(String name, boolean deferrable, boolean rely) {
        return new Constraint(name, "C", List.of("ID"), "\"ID\" IS NOT NULL", null, null, List.of(), null,
                deferrable, false, true, true, rely, false, null, null);
    }

    private static Column column(String name, String type, Integer length, Integer precision, Integer scale,
                                 String semantics, Integer charLength, String defaultValue) {
        return new Column(name, type, null, length, precision, scale, semantics, charLength, defaultValue,
                true, false, false, false, null, null);
    }

    private static Table table(List<Column> columns) { return new Table("T", columns, List.of(), Map.of(), null, null); }
}
