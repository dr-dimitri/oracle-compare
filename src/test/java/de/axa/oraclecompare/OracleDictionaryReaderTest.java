package de.axa.oraclecompare;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft echte Dictionary-Zeilenformate und JDBC-Lebensdauer ohne eine Oracle-Instanz. */
class OracleDictionaryReaderTest {
    @Test void readsEmptySchemaWithOnlySelectAndWithoutOwningConnection() throws Exception {
        Jdbc jdbc = new Jdbc();
        SchemaDefinition definition = jdbc.read();
        assertEquals("APP", definition.owner());
        assertTrue(definition.tables().isEmpty());
        assertEquals(jdbc.statements, jdbc.closedStatements);
        assertEquals(jdbc.statements, jdbc.closedResults);
        assertFalse(jdbc.closedConnection);
        assertTrue(jdbc.sql.stream().allMatch(sql -> sql.stripLeading().startsWith("SELECT")));
    }

    @Test void bindsExactOwnerInsteadOfEmbeddingItInSql() throws Exception {
        Jdbc jdbc = new Jdbc();
        String owner = "O'wner\"x";
        new OracleDictionaryReader(jdbc.connection()).read(owner, ExclusionFilter.none());
        assertTrue(jdbc.bindings.stream().anyMatch(values -> values.contains(owner)));
        assertTrue(jdbc.sql.stream().noneMatch(sql -> sql.contains(owner)));
    }

    @Test void rejectsMissingOwnerBeforeInventoryQueries() {
        Jdbc jdbc = new Jdbc();
        jdbc.data.put("dba_users", List.of());
        SQLException failure = assertThrows(SQLException.class, jdbc::read);
        assertTrue(failure.getMessage().contains("APP"));
        assertEquals(1, jdbc.sql.size());
        assertEquals(1, jdbc.closedStatements);
    }

    @Test void readsInvisibleVirtualAndCharSemanticsWithoutTruncatingLongDefault() throws Exception {
        Jdbc jdbc = baseTable();
        String expression = "'" + "x".repeat(6000) + "'";
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "V", "DATA_TYPE", "VARCHAR2",
                "DATA_LENGTH", "80", "CHAR_LENGTH", "20", "CHAR_USED", "C", "NULLABLE", "Y",
                "VIRTUAL_COLUMN", "YES", "HIDDEN_COLUMN", "YES", "USER_GENERATED", "YES",
                "DEFAULT_ON_NULL", "NO", "COLLATION", "USING_NLS_COMP", "DATA_DEFAULT", expression));
        SchemaDefinition.Column column = jdbc.read().tables().get("T").columns().get(0);
        assertEquals(expression, column.defaultExpression());
        assertTrue(column.virtual());
        assertTrue(column.invisible());
        assertEquals("C", column.charUsed());
        assertEquals(20, column.charLength());
        assertTrue(jdbc.sql.stream().anyMatch(sql -> sql.contains("internal_column_id")));
    }

    @Test void identityHidesGeneratedSequenceDefault() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "ID", "DATA_TYPE", "NUMBER",
                "NULLABLE", "N", "IDENTITY_COLUMN", "YES", "DATA_DEFAULT", "\"APP\".\"ISEQ$$_1\".nextval"));
        jdbc.rows("dba_tab_identity_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "ID", "GENERATION_TYPE", "BY DEFAULT",
                "IDENTITY_OPTIONS", "START WITH: 1, INCREMENT BY: 1"));
        SchemaDefinition.Column column = jdbc.read().tables().get("T").columns().get(0);
        assertNull(column.defaultExpression());
        assertEquals("BY DEFAULT", column.identity().generationType());
    }

    @Test void identityByDefaultOnNullCombinesBothDictionaryViews() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "ID", "DATA_TYPE", "NUMBER",
                "NULLABLE", "N", "IDENTITY_COLUMN", "YES", "DEFAULT_ON_NULL", "YES"));
        jdbc.rows("dba_tab_identity_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "ID", "GENERATION_TYPE", "BY DEFAULT", "IDENTITY_OPTIONS", "START WITH: 1"));
        assertEquals("BY DEFAULT ON NULL", jdbc.read().tables().get("T").columns().get(0).identity().generationType());
    }

    @Test void missingIdentityMetadataFailsExplicitly() {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "ID", "DATA_TYPE", "NUMBER", "IDENTITY_COLUMN", "YES"));
        assertTrue(assertThrows(SQLException.class, jdbc::read).getMessage().contains("Identity-Definition"));
    }

    @Test void excludedTableKeepsSequenceDefaultsWithoutValidatingUnsupportedColumnMetadata() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "ID", "DATA_TYPE", "UNSUPPORTED_TYPE",
                "DATA_LENGTH", "too large", "DATA_PRECISION", "invalid", "DATA_DEFAULT", " \"APP\".\"S\".NEXTVAL "),
                row("TABLE_NAME", "T", "COLUMN_NAME", "NOTE", "DATA_DEFAULT", "NULL"));
        List<SchemaDefinition.Column> columns = jdbc.read(new ExclusionFilter(List.of("t"))).tables().get("T").columns();
        assertEquals(List.of("ID", "NOTE"), columns.stream().map(SchemaDefinition.Column::name).toList());
        assertEquals("\"APP\".\"S\".NEXTVAL", columns.get(0).defaultExpression());
        assertNull(columns.get(0).dataType());
        assertNull(columns.get(1).defaultExpression());
    }

    @Test void excludedIdentityNeverExposesItsInternalSequenceAsADependency() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "ID", "IDENTITY_COLUMN", "YES",
                "DATA_DEFAULT", "\"APP\".\"ISEQ$$_1\".NEXTVAL"));
        // Das ausgeschlossene Objekt benötigt auch bei fehlenden Identity-Details keine Validierung.
        SchemaDefinition.Column column = jdbc.read(new ExclusionFilter(List.of("T"))).tables().get("T").columns().get(0);
        assertNull(column.defaultExpression());
        assertNull(column.identity());
    }

    @Test void constraintExpressionAndGeneratedNameArePreserved() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_cons_columns", row("CONSTRAINT_NAME", "SYS_C1", "COLUMN_NAME", "ID"));
        jdbc.rows("dba_constraints", row("TABLE_NAME", "T", "CONSTRAINT_NAME", "SYS_C1", "CONSTRAINT_TYPE", "C",
                "SEARCH_CONDITION", "\"ID\" IS NOT NULL", "GENERATED", "GENERATED NAME", "STATUS", "ENABLED",
                "VALIDATED", "VALIDATED", "DEFERRABLE", "NOT DEFERRABLE"));
        SchemaDefinition.Constraint constraint = jdbc.read().tables().get("T").constraints().get(0);
        assertEquals(List.of("ID"), constraint.columns());
        assertEquals("\"ID\" IS NOT NULL", constraint.expression());
        assertTrue(constraint.generated());
        assertTrue(constraint.enabled());
    }

    @Test void resolvesForeignKeyAcrossSchemasAndRetainsColumnOrder() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.extra = (sql, bindings) -> {
            if (sql.startsWith("SELECT table_name FROM dba_constraints")) {
                assertEquals(List.of("OTHER", "P_UK"), bindings);
                return List.of(row("TABLE_NAME", "PARENT"));
            }
            if (sql.startsWith("SELECT column_name FROM dba_cons_columns")) return List.of(row("COLUMN_NAME", "B"), row("COLUMN_NAME", "A"));
            return null;
        };
        jdbc.rows("dba_cons_columns", row("CONSTRAINT_NAME", "FK", "COLUMN_NAME", "X"), row("CONSTRAINT_NAME", "FK", "COLUMN_NAME", "Y"));
        jdbc.rows("dba_constraints", row("TABLE_NAME", "T", "CONSTRAINT_NAME", "FK", "CONSTRAINT_TYPE", "R",
                "R_OWNER", "OTHER", "R_CONSTRAINT_NAME", "P_UK", "DELETE_RULE", "CASCADE", "DEFERRABLE", "DEFERRABLE", "DEFERRED", "DEFERRED"));
        SchemaDefinition.Constraint key = jdbc.read().tables().get("T").constraints().get(0);
        assertEquals("OTHER", key.referencedOwner());
        assertEquals("PARENT", key.referencedTable());
        assertEquals(List.of("B", "A"), key.referencedColumns());
        assertTrue(key.initiallyDeferred());
    }

    @Test void readsFunctionIndexAndQuotedOrdinaryColumn() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_indexes", row("INDEX_NAME", "IX", "INDEX_TYPE", "FUNCTION-BASED NORMAL", "TABLE_OWNER", "APP", "TABLE_NAME", "T", "GENERATED", "N"));
        jdbc.rows("dba_ind_expressions", row("COLUMN_POSITION", "1", "COLUMN_EXPRESSION", "UPPER(\"NAME\")"));
        jdbc.rows("dba_ind_columns", row("COLUMN_POSITION", "1", "COLUMN_NAME", "SYS_NC001$", "DESCEND", "ASC"),
                row("COLUMN_POSITION", "2", "COLUMN_NAME", "a\"b", "DESCEND", "DESC"));
        List<SchemaDefinition.IndexColumn> columns = jdbc.read().indexes().get("IX").columns();
        assertEquals("UPPER(\"NAME\")", columns.get(0).expression());
        assertEquals("\"a\"\"b\"", columns.get(1).expression());
        assertTrue(columns.get(1).descending());
    }

    @Test void excludedDomainIndexProtectsSecondaryStorageAndConstraintNames() throws Exception {
        Jdbc jdbc = domainFixture();
        SchemaDefinition schema = jdbc.read(new ExclusionFilter(List.of("ix_docs")));
        assertTrue(schema.excludedTables().contains("DR$IX_DOCS$I"));
        assertEquals("STORAGE_PK", schema.tables().get("DR$IX_DOCS$I").constraints().get(0).name());
        assertTrue(schema.indexes().containsKey("IX_DOCS"));
    }

    @Test void excludedCrossOwnerDomainIndexProtectsItsLocalSecondaryTable() throws Exception {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_tables", row("TABLE_NAME", "STORAGE_T", "SECONDARY", "Y"));
        jdbc.rows("dba_secondary_objects", row("INDEX_OWNER", "OTHER", "INDEX_NAME", "IX_DOCS",
                "SECONDARY_OBJECT_OWNER", "APP", "SECONDARY_OBJECT_NAME", "STORAGE_T", "BASE_TABLE_OWNER", "OTHER", "BASE_TABLE_NAME", "DOCS"));
        assertTrue(jdbc.read(new ExclusionFilter(List.of("IX_DOCS"))).excludedTables().contains("STORAGE_T"));
        assertTrue(jdbc.read(new ExclusionFilter(List.of("DOCS"))).excludedTables().contains("STORAGE_T"));
    }

    @Test void excludedBaseTableAlsoExcludesDomainStorage() throws Exception {
        SchemaDefinition schema = domainFixture().read(new ExclusionFilter(List.of("docs")));
        assertTrue(schema.excludedTables().containsAll(List.of("DOCS", "DR$IX_DOCS$I")));
    }

    @Test void selectedDomainStorageFailsBeforeFileGeneration() {
        assertThrows(SQLException.class, () -> domainFixture().read());
    }

    @Test void secureFileLobKeepsStorageAndIgnoresGeneratedSegmentName() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "DOCUMENT", "DATA_TYPE", "CLOB"));
        jdbc.rows("dba_lobs", row("TABLE_NAME", "T", "COLUMN_NAME", "DOCUMENT", "SEGMENT_NAME", "SYS_LOB1$$", "SEGMENT_GENERATED", "Y",
                "TABLESPACE_NAME", "LOB_DATA", "SECUREFILE", "YES", "COMPRESSION", "HIGH", "CACHE", "CACHEREADS", "DEDUPLICATION", "LOB", "IN_ROW", "NO"));
        jdbc.rows("dba_segments", row("INITIAL_EXTENT", "65536", "NEXT_EXTENT", "1048576", "MIN_EXTENTS", "1", "BUFFER_POOL", "KEEP"));
        SchemaDefinition.Lob lob = jdbc.read().tables().get("T").lobs().get(0);
        assertEquals("DOCUMENT", lob.column());
        assertNull(lob.segmentName());
        assertEquals("HIGH", lob.attributes().get("COMPRESSION"));
        assertEquals("65536", lob.attributes().get("INITIAL_EXTENT"));
        assertEquals("CACHEREADS", lob.attributes().get("CACHE"));
    }

    @Test void basicFileLobPreservesExplicitSegmentAndPctversion() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "DOCUMENT", "DATA_TYPE", "BLOB"));
        jdbc.rows("dba_lobs", row("TABLE_NAME", "T", "COLUMN_NAME", "DOCUMENT", "SEGMENT_NAME", "DOC_LOB", "SEGMENT_GENERATED", "N",
                "SECUREFILE", "NO", "PCTVERSION", "10", "CHUNK", "8192", "RETENTION_TYPE", "NO"));
        SchemaDefinition.Lob lob = jdbc.read().tables().get("T").lobs().get(0);
        assertEquals("DOC_LOB", lob.segmentName());
        assertEquals("10", lob.attributes().get("PCTVERSION"));
    }

    @Test void internalXmlLobCannotBecomeAnOrdinaryLobClause() {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "DOCUMENT", "DATA_TYPE", "XMLTYPE", "DATA_TYPE_OWNER", "SYS"));
        jdbc.rows("dba_lobs", row("TABLE_NAME", "T", "COLUMN_NAME", "SYS_NC00001$", "SEGMENT_NAME", "SYS_LOB1$$", "SECUREFILE", "YES"));
        assertTrue(assertThrows(SQLException.class, jdbc::read).getMessage().contains("interne LOB-Speicherung"));
    }

    @Test void unsupportedPartitionedLobFailsExplicitly() {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_lobs", row("TABLE_NAME", "T", "COLUMN_NAME", "D", "PARTITIONED", "YES"));
        assertTrue(assertThrows(SQLException.class, jdbc::read).getMessage().contains("LOB"));
    }

    @Test void lobsOutsideTheTableInventoryDoNotAbortComparison() throws Exception {
        Jdbc jdbc = baseTable();
        // DBA_TABLES-Inventur hat Materialized Views, Logs, Recycle-Bin und Nested Tables bereits ausgefiltert.
        jdbc.rows("dba_lobs", row("TABLE_NAME", "MV_DATA", "PARTITIONED", "YES"),
                row("TABLE_NAME", "MLOG$_DATA", "ENCRYPT", "YES"),
                row("TABLE_NAME", "BIN$DATA", "RETENTION_TYPE", "MAX"),
                row("TABLE_NAME", "NESTED_DATA", "COLUMN_NAME", "SYS_NC1$"));
        assertEquals(List.of("T"), List.copyOf(jdbc.read().tables().keySet()));
        assertTrue(jdbc.sql.stream().noneMatch(sql -> sql.contains("FROM dba_segments")));
    }

    @Test void excludingAllTablesSkipsTheirLobValidationAndUninventoriedLobs() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_lobs", row("TABLE_NAME", "T", "PARTITIONED", "YES"),
                row("TABLE_NAME", "MV_DATA", "PARTITIONED", "YES"));
        SchemaDefinition definition = jdbc.read(new ExclusionFilter(List.of("*")));
        assertTrue(definition.excludedTables().contains("T"));
        assertTrue(definition.tables().get("T").lobs().isEmpty());
    }

    @Test void rowArchivalIsDetectedViaItsHiddenSystemColumn() {
        Jdbc jdbc = baseTable();
        jdbc.extra = (sql, bindings) -> sql.contains("column_name = 'ORA_ARCHIVE_STATE'") ? List.of(row("TABLE_NAME", "T")) : null;
        assertTrue(assertThrows(SQLException.class, jdbc::read).getMessage().contains("ROW ARCHIVAL"));
    }

    @Test void explicitNullDefaultEqualsAbsentDefaultButNullLiteralRemains() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "T", "COLUMN_NAME", "A", "DATA_TYPE", "NUMBER", "DATA_DEFAULT", " NULL "),
                row("TABLE_NAME", "T", "COLUMN_NAME", "B", "DATA_TYPE", "VARCHAR2", "DATA_DEFAULT", "'NULL'"),
                row("TABLE_NAME", "T", "COLUMN_NAME", "V", "DATA_TYPE", "NUMBER", "VIRTUAL_COLUMN", "YES", "DATA_DEFAULT", "NULL"));
        List<SchemaDefinition.Column> columns = jdbc.read().tables().get("T").columns();
        assertNull(columns.get(0).defaultExpression());
        assertEquals("'NULL'", columns.get(1).defaultExpression());
        assertEquals("NULL", columns.get(2).defaultExpression());
    }

    @Test void selectedJoinIndexFailsButExcludedJoinIndexRetainsProtectionInfo() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_indexes", row("INDEX_NAME", "IX", "INDEX_TYPE", "BITMAP", "JOIN_INDEX", "YES", "TABLE_OWNER", "APP", "TABLE_NAME", "T"));
        assertThrows(SQLException.class, jdbc::read);
        assertEquals("YES", jdbc.read(new ExclusionFilter(List.of("IX"))).indexes().get("IX").attributes().get("JOIN_INDEX"));
    }

    @Test void externalDataPumpKeepsDirectoriesParametersAndLocations() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_external_tables", row("TABLE_NAME", "T", "TYPE_NAME", "ORACLE_DATAPUMP", "DEFAULT_DIRECTORY_NAME", "EXPORT_HOST",
                "ACCESS_TYPE", "CLOB", "ACCESS_PARAMETERS", "NOLOGFILE", "REJECT_LIMIT", "UNLIMITED", "PROPERTY", "ALL"));
        jdbc.rows("dba_external_locations", row("DIRECTORY_NAME", "EXPORT_HOST", "LOCATION", "first.dmp"), row("DIRECTORY_NAME", "ARCHIVE", "LOCATION", "second.dmp"));
        SchemaDefinition.ExternalTable external = jdbc.read().tables().get("T").external();
        assertEquals("ORACLE_DATAPUMP", external.type());
        assertEquals("NOLOGFILE", external.accessParameters());
        assertEquals(2, external.locations().size());
        assertEquals("ARCHIVE", external.locations().get(1).directory());
    }

    @Test void partitionedExternalTableIsRejectedBeforeLosingItsPartitionLocations() {
        Jdbc jdbc = partitionedExternalTable();
        SQLException failure = assertThrows(SQLException.class, jdbc::read);
        assertTrue(failure.getMessage().contains("APP.T"));
        assertTrue(failure.getMessage().contains("partitionierte externe Tabelle"));
        assertTrue(jdbc.sql.stream().noneMatch(sql -> sql.contains("FROM dba_external_locations")));
        assertTrue(jdbc.sql.stream().noneMatch(sql -> sql.contains("FROM dba_tab_partitions")));
        assertEquals(jdbc.statements, jdbc.closedStatements);
        assertEquals(jdbc.statements, jdbc.closedResults);
    }

    @Test void excludedPartitionedExternalTableDoesNotRequirePartitionOrLocationMetadata() throws Exception {
        Jdbc jdbc = partitionedExternalTable();
        SchemaDefinition definition = jdbc.read(new ExclusionFilter(List.of("t")));
        assertTrue(definition.excludedTables().contains("T"));
        assertNull(definition.tables().get("T").external());
        assertNull(definition.tables().get("T").partitioning());
    }

    @Test void rangePartitionBoundsAndBlockStorageAreReadCorrectly() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_part_tables", row("TABLE_NAME", "T", "PARTITIONING_TYPE", "RANGE", "SUBPARTITIONING_TYPE", "NONE",
                "DEF_TABLESPACE_NAME", "DATA", "DEF_INITIAL_EXTENT", "8", "DEF_NEXT_EXTENT", "DEFAULT"));
        jdbc.rows("dba_tablespaces", row("TABLESPACE_NAME", "DATA", "BLOCK_SIZE", "8192"));
        jdbc.rows("dba_part_key_columns", row("COLUMN_NAME", "BOOKING_DATE"));
        jdbc.rows("dba_tab_partitions", row("PARTITION_NAME", "P1", "PARTITION_POSITION", "1", "TABLESPACE_NAME", "DATA", "INITIAL_EXTENT", "65536", "MIN_EXTENT", "1", "HIGH_VALUE", "DATE '2027-01-01'"),
                row("PARTITION_NAME", "PF", "PARTITION_POSITION", "2", "HIGH_VALUE", "MAXVALUE"));
        SchemaDefinition.Table table = jdbc.read().tables().get("T");
        assertEquals("65536", table.attributes().get("INITIAL_EXTENT"));
        assertFalse(table.attributes().containsKey("NEXT_EXTENT"));
        assertEquals(List.of("BOOKING_DATE"), table.partitioning().keys());
        assertEquals("65536", table.partitioning().partitions().get(0).attributes().get("INITIAL_EXTENT"));
        assertEquals("1", table.partitioning().partitions().get(0).attributes().get("MIN_EXTENTS"));
        assertEquals("MAXVALUE", table.partitioning().partitions().get(1).highValue());
    }

    @Test void compositePartitionsPreserveChildrenAndConvertParentExtentBlocks() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_part_tables", row("TABLE_NAME", "T", "PARTITIONING_TYPE", "RANGE", "SUBPARTITIONING_TYPE", "LIST"));
        jdbc.rows("dba_tablespaces", row("TABLESPACE_NAME", "DATA", "BLOCK_SIZE", "8192"));
        jdbc.rows("dba_part_key_columns", row("COLUMN_NAME", "ID"));
        jdbc.rows("dba_subpart_key_columns", row("COLUMN_NAME", "REGION"));
        jdbc.rows("dba_tab_partitions", row("PARTITION_NAME", "P1", "COMPOSITE", "YES", "TABLESPACE_NAME", "DATA", "INITIAL_EXTENT", "8", "HIGH_VALUE", "MAXVALUE"));
        jdbc.rows("dba_tab_subpartitions", row("SUBPARTITION_NAME", "P1_EU", "HIGH_VALUE", "'EU'", "TABLESPACE_NAME", "DATA", "INITIAL_EXTENT", "65536"));
        SchemaDefinition.Partition parent = jdbc.read().tables().get("T").partitioning().partitions().get(0);
        assertEquals("65536", parent.attributes().get("INITIAL_EXTENT"));
        assertEquals("'EU'", parent.children().get(0).highValue());
        assertEquals("65536", parent.children().get(0).attributes().get("INITIAL_EXTENT"));
    }

    @Test void excludedUnsupportedPartitionsDoNotTriggerDefinitionValidation() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_part_tables", row("TABLE_NAME", "T", "PARTITIONING_TYPE", "REFERENCE", "DEF_INMEMORY", "ENABLED", "DEF_INITIAL_EXTENT", "8"));
        assertTrue(jdbc.read(new ExclusionFilter(List.of("T"))).excludedTables().contains("T"));
    }

    @Test void viewLongTextAndLocalDependenciesRemainComplete() throws Exception {
        Jdbc jdbc = new Jdbc();
        String query = "SELECT '" + "x".repeat(5000) + "' FROM T";
        jdbc.rows("dba_views", row("VIEW_NAME", "V", "TEXT", query, "BEQUEATH", "DEFINER"));
        jdbc.rows("dba_tab_cols", row("TABLE_NAME", "V", "COLUMN_NAME", "TEXT", "DATA_TYPE", "VARCHAR2"));
        jdbc.rows("dba_dependencies", row("NAME", "V", "REFERENCED_NAME", "T"));
        SchemaDefinition result = jdbc.read();
        assertEquals(query, result.views().get("V").text());
        assertEquals(List.of("TEXT"), result.views().get("V").columns());
        assertTrue(result.viewDependencies().get("V").contains("T"));
        assertTrue(jdbc.sql.stream().anyMatch(sql -> sql.contains("referenced_link_name IS NULL")));
    }

    @Test void selectedViewWithNonstandardDefaultCollationIsRejected() {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_views", row("VIEW_NAME", "V", "TEXT", "SELECT 'a' AS A FROM dual", "DEFAULT_COLLATION", "BINARY_CI"));
        assertTrue(assertThrows(SQLException.class, jdbc::read).getMessage().contains("View-DEFAULT COLLATION BINARY_CI"));
    }

    @Test void excludedViewMayRetainNonstandardDefaultCollation() throws Exception {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_views", row("VIEW_NAME", "V", "TEXT", "SELECT 'a' AS A FROM dual", "DEFAULT_COLLATION", "BINARY_CI"));
        assertTrue(jdbc.read(new ExclusionFilter(List.of("v"))).views().containsKey("V"));
    }

    @Test void ordinaryViewDefaultCollationRemainsSupported() throws Exception {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_views", row("VIEW_NAME", "V", "TEXT", "SELECT 'a' AS A FROM dual", "DEFAULT_COLLATION", "USING_NLS_COMP"));
        assertTrue(jdbc.read().views().containsKey("V"));
        assertTrue(jdbc.sql.stream().anyMatch(sql -> sql.contains("default_collation, text FROM dba_views")));
    }

    @Test void readerDoesNotDiscardBequeathCurrentUser() {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_views", row("VIEW_NAME", "V", "TEXT", "SELECT 1 FROM dual", "BEQUEATH", "CURRENT_USER"));
        assertTrue(assertThrows(SQLException.class, jdbc::read).getMessage().contains("BEQUEATH"));
    }

    @Test void selectedViewConstraintsAreRejectedAndExcludedNamesRemainProtected() throws Exception {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_views", row("VIEW_NAME", "V", "TEXT", "SELECT ID FROM T"));
        jdbc.rows("dba_constraints", row("TABLE_NAME", "V", "CONSTRAINT_NAME", "V_PK", "CONSTRAINT_TYPE", "P", "GENERATED", "USER NAME"));
        assertTrue(assertThrows(SQLException.class, jdbc::read).getMessage().contains("View-Constraints"));
        assertEquals("V", jdbc.read(new ExclusionFilter(List.of("V"))).constraintTables().get("V_PK"));
    }

    @Test void checkOptionViewIsRejectedAndItsExcludedConstraintNameRemainsProtected() throws Exception {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_views", row("VIEW_NAME", "V", "TEXT", "SELECT ID FROM T WHERE ID > 0"));
        Map<String, String> check = row("TABLE_NAME", "V", "CONSTRAINT_NAME", "V_CHECK", "CONSTRAINT_TYPE", "V", "GENERATED", "USER NAME");
        jdbc.extra = (sql, bindings) -> {
            if (sql.contains("FROM dba_constraints c WHERE")) {
                // Ein Dictionary-Stub muss hier den Typfilter beachten, sonst verdeckt er den ursprünglichen Fehler.
                return sql.contains("'V'") ? List.of(check) : List.of();
            }
            return null;
        };
        jdbc.rows("dba_constraints", check);
        assertTrue(assertThrows(SQLException.class, jdbc::read).getMessage().contains("View-Constraints"));
        SchemaDefinition excluded = jdbc.read(new ExclusionFilter(List.of("v")));
        assertTrue(excluded.views().containsKey("V"));
        assertEquals("V", excluded.constraintTables().get("V_CHECK"));
    }

    @Test void alteredVisibilityOnViewsCannotMisassignAliasColumns() {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_views", row("VIEW_NAME", "V", "TEXT", "SELECT A, B FROM T"));
        jdbc.extra = (sql, bindings) -> sql.contains("SELECT DISTINCT c.table_name FROM dba_tab_cols") ? List.of(row("TABLE_NAME", "V")) : null;
        assertTrue(assertThrows(SQLException.class, jdbc::read).getMessage().contains("Spaltensichtbarkeit"));
    }

    @Test void compositeLocalIndexExtentValuesRemainBytes() throws Exception {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_indexes", row("INDEX_NAME", "IX", "INDEX_TYPE", "NORMAL", "TABLE_OWNER", "APP", "TABLE_NAME", "T"));
        jdbc.rows("dba_ind_columns", row("COLUMN_NAME", "ID", "COLUMN_POSITION", "1", "DESCEND", "ASC"));
        jdbc.rows("dba_part_indexes", row("INDEX_NAME", "IX", "PARTITIONING_TYPE", "RANGE", "SUBPARTITIONING_TYPE", "HASH", "LOCALITY", "LOCAL"));
        jdbc.rows("dba_part_key_columns", row("COLUMN_NAME", "ID"));
        jdbc.rows("dba_subpart_key_columns", row("COLUMN_NAME", "ID"));
        jdbc.rows("dba_ind_partitions", row("PARTITION_NAME", "P1", "COMPOSITE", "YES", "TABLESPACE_NAME", "DATA", "INITIAL_EXTENT", "65536", "HIGH_VALUE", "MAXVALUE"));
        jdbc.rows("dba_ind_subpartitions", row("SUBPARTITION_NAME", "P1_A", "TABLESPACE_NAME", "DATA", "INITIAL_EXTENT", "65536"));
        jdbc.rows("dba_tablespaces", row("TABLESPACE_NAME", "DATA", "BLOCK_SIZE", "8192"));
        SchemaDefinition.Partition partition = jdbc.read().indexes().get("IX").partitioning().partitions().get(0);
        assertEquals("65536", partition.attributes().get("INITIAL_EXTENT"));
        assertEquals("65536", partition.children().get(0).attributes().get("INITIAL_EXTENT"));
    }

    @Test void sequenceLargeIntegerValuesAreNotRounded() throws Exception {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_sequences", row("SEQUENCE_NAME", "S", "MIN_VALUE", "1", "MAX_VALUE", "9999999999999999999999999999",
                "INCREMENT_BY", "1", "CACHE_SIZE", "20", "LAST_NUMBER", "100", "CYCLE_FLAG", "N", "ORDER_FLAG", "Y"));
        SchemaDefinition.Sequence sequence = jdbc.read().sequences().get("S");
        assertEquals("9999999999999999999999999999", sequence.maxValue());
        assertTrue(sequence.ordered());
        assertEquals("100", sequence.lastNumber());
    }

    @Test void modelDefensivelyCopiesRowsAndCollections() throws Exception {
        SchemaDefinition result = baseTable().read();
        assertThrows(UnsupportedOperationException.class, () -> result.tables().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.tables().get("T").attributes().put("A", "B"));
        assertThrows(UnsupportedOperationException.class, () -> result.tables().get("T").columns().clear());
    }

    private static Jdbc baseTable() {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_tables", row("TABLE_NAME", "T", "TEMPORARY", "N"));
        return jdbc;
    }

    private static Jdbc partitionedExternalTable() {
        Jdbc jdbc = baseTable();
        jdbc.rows("dba_part_tables", row("TABLE_NAME", "T", "PARTITIONING_TYPE", "RANGE", "SUBPARTITIONING_TYPE", "NONE"));
        jdbc.rows("dba_external_tables", row("TABLE_NAME", "T", "TYPE_NAME", "ORACLE_LOADER",
                "DEFAULT_DIRECTORY_NAME", "EXPORT_HOST", "ACCESS_TYPE", "CLOB", "REJECT_LIMIT", "UNLIMITED"));
        return jdbc;
    }

    private static Jdbc domainFixture() {
        Jdbc jdbc = new Jdbc();
        jdbc.rows("dba_tables", row("TABLE_NAME", "DOCS"), row("TABLE_NAME", "DR$IX_DOCS$I", "SECONDARY", "Y"));
        jdbc.rows("dba_indexes", row("INDEX_NAME", "IX_DOCS", "TABLE_NAME", "DOCS", "TABLE_OWNER", "APP", "INDEX_TYPE", "DOMAIN"));
        jdbc.rows("dba_secondary_objects", row("INDEX_OWNER", "APP", "INDEX_NAME", "IX_DOCS", "SECONDARY_OBJECT_OWNER", "APP", "SECONDARY_OBJECT_NAME", "DR$IX_DOCS$I"));
        jdbc.rows("dba_constraints", row("TABLE_NAME", "DR$IX_DOCS$I", "CONSTRAINT_NAME", "STORAGE_PK", "CONSTRAINT_TYPE", "P", "INDEX_NAME", "STORAGE_PK"));
        return jdbc;
    }

    private static Map<String, String> row(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put(entries[i], entries[i + 1]);
        return result;
    }

    /** Ein Dictionary-Stub mit echten Spaltenlabels und gezieltem SQL-/Ressourcenprotokoll. */
    private static final class Jdbc {
        final Map<String, List<Map<String, String>>> data = new LinkedHashMap<>();
        final List<String> sql = new ArrayList<>();
        final List<List<String>> bindings = new ArrayList<>();
        BiFunction<String, List<String>, List<Map<String, String>>> extra = (query, values) -> null;
        int statements, closedStatements, closedResults;
        boolean closedConnection;
        Jdbc() { rows("dba_users", row("USERNAME", "APP")); }
        @SafeVarargs final void rows(String view, Map<String, String>... rows) { data.put(view, List.of(rows)); }
        SchemaDefinition read() throws SQLException { return read(ExclusionFilter.none()); }
        SchemaDefinition read(ExclusionFilter filter) throws SQLException { return new OracleDictionaryReader(connection()).read("APP", filter); }
        Connection connection() {
            return proxy(Connection.class, (method, args) -> switch (method) {
                case "prepareStatement" -> statement((String) args[0]);
                case "close" -> { closedConnection = true; yield null; }
                default -> throw new AssertionError("Unzulässiger Connection-Aufruf: " + method);
            });
        }
        private PreparedStatement statement(String query) {
            sql.add(query); statements++;
            List<String> values = new ArrayList<>();
            return proxy(PreparedStatement.class, (method, args) -> switch (method) {
                case "setString" -> { while (values.size() < (Integer) args[0]) values.add(null); values.set((Integer) args[0] - 1, (String) args[1]); yield null; }
                case "executeQuery" -> {
                    bindings.add(List.copyOf(values));
                    List<Map<String, String>> rows = extra.apply(query, values);
                    if (rows == null) {
                        rows = List.of();
                        String lower = query.toLowerCase(java.util.Locale.ROOT);
                        int from = lower.indexOf("from ") + 5;
                        String view = lower.substring(from).split("\\s+")[0];
                        rows = data.getOrDefault(view, List.of());
                        if (query.contains("SELECT c.owner, c.table_name") || query.contains("column_name = 'ORA_ARCHIVE_STATE'")
                                || query.contains("SELECT DISTINCT c.table_name FROM dba_tab_cols")) rows = List.of();
                    }
                    yield result(rows);
                }
                case "close" -> { closedStatements++; yield null; }
                default -> throw new AssertionError("Unzulässiger Statement-Aufruf: " + method);
            });
        }
        private ResultSet result(List<Map<String, String>> rows) {
            List<String> columns = new ArrayList<>();
            rows.forEach(row -> row.keySet().forEach(column -> { if (!columns.contains(column)) columns.add(column); }));
            int[] position = {-1};
            ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (method, args) -> switch (method) {
                case "getColumnCount" -> columns.size();
                case "getColumnLabel" -> columns.get((Integer) args[0] - 1);
                default -> throw new AssertionError("Unzulässiger Metadaten-Aufruf: " + method);
            });
            return proxy(ResultSet.class, (method, args) -> switch (method) {
                case "next" -> ++position[0] < rows.size();
                case "getMetaData" -> metadata;
                case "getString" -> rows.get(position[0]).get(columns.get((Integer) args[0] - 1));
                case "close" -> { closedResults++; yield null; }
                default -> throw new AssertionError("Unzulässiger ResultSet-Aufruf: " + method);
            });
        }
    }

    private interface Invocation { Object call(String method, Object[] arguments) throws Throwable; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.call(method.getName(), args == null ? new Object[0] : args));
    }
}
