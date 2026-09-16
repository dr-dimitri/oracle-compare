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
