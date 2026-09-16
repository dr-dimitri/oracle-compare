package de.axa.oraclecompare;

import static de.axa.oraclecompare.SchemaDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Prüft ausführbare Planphasen und Ausschlussschutz an eigenständigen Schema-Fachmodellen. */
class SchemaComparisonPlannerTest {
    @Test
    void equalSchemasNeedNoObjectDdl() throws Exception {
        assertNoChanges(plan(new Fixture("SOURCE"), new Fixture("TARGET")));
    }

    @Test
    void unchangedPlanHasNoReportedOperationsAndDefensivelyCopiesThem() throws Exception {
        ComparisonPlan result = comparisonPlan(new Fixture("SOURCE"), new Fixture("TARGET"));
        assertEquals("SOURCE", result.referenceSchema());
        assertEquals("TARGET", result.targetSchema());
        assertTrue(result.operations().isEmpty());
        assertNoChanges(result.script());
        List<ComparisonPlan.Operation> mutable = new ArrayList<>();
        ComparisonPlan copy = new ComparisonPlan("SOURCE", "TARGET", result.script(), mutable);
        mutable.add(new ComparisonPlan.Operation(ComparisonPlan.Action.DROP, ComparisonPlan.ObjectType.TABLE,
                "T", null, "DROP TABLE T;"));
        assertTrue(copy.operations().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> copy.operations().addAll(mutable));
    }

    @Test
    void reportsCreationAndValidationInTheExactSqlExecutionOrder() throws Exception {
        Fixture source = new Fixture("SOURCE").table(table("T", pk("T_PK"), fk("T_FK", "SOURCE", "T")))
                .sequence(sequence("ID_SEQ", "100"))
                .index(index("T_IX", "SOURCE", "T", "ID", false));
        source.view("T_VIEW", "SELECT ID FROM \"SOURCE\".\"T\"", "T");
        Fixture target = new Fixture("TARGET");

        ComparisonPlan result = comparisonPlan(source, target);

        assertEquals(List.of("CREATE SEQUENCE ID_SEQ", "CREATE TABLE T", "CREATE INDEX T_IX ON T",
                "CREATE CONSTRAINT T_PK ON T", "CREATE CONSTRAINT T_FK ON T", "CREATE VIEW T_VIEW",
                "COMPILE VIEW T_VIEW", "VALIDATE SCHEMA TARGET"), operationLabels(result));
        assertEquals(plan(source, target), result.script());
        assertOperationsMatchScript(result);
    }

    @Test
    void createsAllTablesAndKeysBeforeCyclicAndSelfReferencingForeignKeys() throws Exception {
        Fixture source = new Fixture("SOURCE");
        source.table(table("A_CHILD", pk("A_PK"), fk("A_Z_FK", "SOURCE", "Z_PARENT"), fk("A_SELF_FK", "SOURCE", "A_CHILD")));
        source.table(table("Z_PARENT", pk("Z_PK"), fk("Z_A_FK", "SOURCE", "A_CHILD")));

        String sql = plan(source, new Fixture("TARGET"));

        before(sql, "CREATE TABLE \"TARGET\".\"Z_PARENT\"", "ADD CONSTRAINT \"A_PK\"");
        before(sql, "ADD CONSTRAINT \"Z_PK\"", "ADD CONSTRAINT \"A_SELF_FK\"");
        before(sql, "ADD CONSTRAINT \"Z_PK\"", "ADD CONSTRAINT \"A_Z_FK\"");
        before(sql, "ADD CONSTRAINT \"Z_PK\"", "ADD CONSTRAINT \"Z_A_FK\"");
        assertTrue(sql.contains("REFERENCES \"TARGET\".\"Z_PARENT\" (\"ID\")"));
        assertFalse(sql.contains("REFERENCES \"SOURCE\""));
    }

    @Test
    void releasesForeignKeysAndIndexesBeforeAlteringColumnsThenRestoresThem() throws Exception {
        Fixture source = new Fixture("SOURCE");
        Fixture target = new Fixture("TARGET");
        source.table(new Table("P", List.of(number("ID"), varchar("LABEL", 100)), List.of(pk("P_PK")), Map.of(), null, null));
        target.table(new Table("P", List.of(number("ID"), varchar("LABEL", 50)), List.of(pk("P_PK")), Map.of(), null, null));
        source.table(table("C", fk("C_P_FK", "SOURCE", "P")));
        target.table(table("C", fk("C_P_FK", "TARGET", "P")));
        source.index(index("P_LABEL_IX", "SOURCE", "P", "LABEL", false));
        target.index(index("P_LABEL_IX", "TARGET", "P", "LABEL", false));

        ComparisonPlan result = comparisonPlan(source, target);
        String sql = result.script();

        before(sql, "DROP CONSTRAINT \"C_P_FK\"", "DROP CONSTRAINT \"P_PK\"");
        before(sql, "DROP INDEX \"TARGET\".\"P_LABEL_IX\"", "MODIFY (\"LABEL\" VARCHAR2(100 CHAR))");
        before(sql, "MODIFY (\"LABEL\" VARCHAR2(100 CHAR))", "CREATE INDEX \"TARGET\".\"P_LABEL_IX\"");
        before(sql, "ADD CONSTRAINT \"P_PK\"", "ADD CONSTRAINT \"C_P_FK\"");
        assertEquals(List.of("DROP CONSTRAINT C_P_FK ON C", "DROP INDEX P_LABEL_IX ON P", "DROP CONSTRAINT P_PK ON P",
                "ALTER TABLE P", "CREATE INDEX P_LABEL_IX ON P", "CREATE CONSTRAINT P_PK ON P",
                "CREATE CONSTRAINT C_P_FK ON C"), operationLabels(result));
        assertOperationsMatchScript(result);
    }

    @Test
    void releasesMovedConstraintNameBeforeAddingItToEarlierTable() throws Exception {
        Constraint check = check("CK_MOVED", "\"ID\" >= 0", false);
        Fixture source = new Fixture("SOURCE").table(table("A", check)).table(table("Z"));
        Fixture target = new Fixture("TARGET").table(table("A")).table(table("Z", check));

        String sql = plan(source, target);

        before(sql, "ALTER TABLE \"TARGET\".\"Z\" DROP CONSTRAINT \"CK_MOVED\"",
                "ALTER TABLE \"TARGET\".\"A\" ADD CONSTRAINT \"CK_MOVED\"");
    }

    @Test
    void dropsExtraViewsAndIndexesBeforeTheirTablesAndDropsExtraSequences() throws Exception {
        Fixture target = new Fixture("TARGET").table(table("EXTRA_TABLE"));
        target.index(index("EXTRA_IX", "TARGET", "EXTRA_TABLE", "ID", false));
        target.view("EXTRA_VIEW", "SELECT ID FROM \"TARGET\".\"EXTRA_TABLE\"", "EXTRA_TABLE");
        target.sequence(sequence("EXTRA_SEQ", "20"));

        ComparisonPlan result = comparisonPlan(new Fixture("SOURCE"), target);
        String sql = result.script();

        before(sql, "DROP VIEW \"TARGET\".\"EXTRA_VIEW\"", "DROP TABLE \"TARGET\".\"EXTRA_TABLE\"");
        before(sql, "DROP INDEX \"TARGET\".\"EXTRA_IX\"", "DROP TABLE \"TARGET\".\"EXTRA_TABLE\"");
        assertTrue(sql.contains("DROP SEQUENCE \"TARGET\".\"EXTRA_SEQ\";"));
        assertEquals(List.of("DROP VIEW EXTRA_VIEW", "DROP INDEX EXTRA_IX ON EXTRA_TABLE", "DROP TABLE EXTRA_TABLE",
                "DROP SEQUENCE EXTRA_SEQ"), operationLabels(result));
        assertOperationsMatchScript(result);
    }

    @Test
    void reportsEachTableAlterAndDistinguishesViewReplacementFromCreation() throws Exception {
        Fixture source = new Fixture("SOURCE").table(new Table("T", List.of(number("ID"), varchar("LABEL", 100), number("EXTRA")), List.of(), Map.of(), null, null));
        Fixture target = new Fixture("TARGET").table(new Table("T", List.of(number("ID"), varchar("LABEL", 50)), List.of(), Map.of(), null, null));
        source.view("OLD_VIEW", "SELECT ID + 1 FROM \"SOURCE\".\"T\"", "T");
        target.view("OLD_VIEW", "SELECT ID FROM \"TARGET\".\"T\"", "T");
        source.view("NEW_VIEW", "SELECT ID FROM \"SOURCE\".\"T\"", "T");
        source.sequence(new Sequence("ID_SEQ", "1", "9999999999999999999999999999", "1", "50", false, false, "1", Map.of()));
        target.sequence(sequence("ID_SEQ", "20"));

        ComparisonPlan result = comparisonPlan(source, target);

        assertEquals(List.of("ALTER SEQUENCE ID_SEQ", "ALTER TABLE T", "ALTER TABLE T", "CREATE VIEW NEW_VIEW",
                "ALTER VIEW OLD_VIEW", "COMPILE VIEW NEW_VIEW", "COMPILE VIEW OLD_VIEW", "VALIDATE SCHEMA TARGET"), operationLabels(result));
        assertOperationsMatchScript(result);
    }

    @Test
    void excludedViewProtectsItsTransitiveBaseTable() {
        Fixture target = new Fixture("TARGET").table(table("BASE_TABLE"));
        target.view("BRIDGE_VIEW", "SELECT ID FROM BASE_TABLE", "BASE_TABLE");
        target.view("KEEP_VIEW", "SELECT ID FROM BRIDGE_VIEW", "BRIDGE_VIEW");
        SQLException failure = assertThrows(SQLException.class,
                () -> plan(new Fixture("SOURCE"), target, "keep_*"));
        assertTrue(failure.getMessage().contains("KEEP_VIEW"));
    }

    @Test
    void excludedIndexProtectsItsTableFromColumnChanges() {
        Fixture source = new Fixture("SOURCE").table(new Table("T", List.of(number("ID"), number("NEW_COL")), List.of(), Map.of(), null, null));
        Fixture target = new Fixture("TARGET").table(table("T"));
        target.index(index("KEEP_IX", "TARGET", "T", "ID", false));
        SQLException failure = assertThrows(SQLException.class, () -> plan(source, target, "keep_ix"));
        assertTrue(failure.getMessage().contains("KEEP_IX"));
    }

    @Test
    void incomingForeignKeyFromAnotherSchemaPreventsParentChanges() {
        Fixture source = new Fixture("SOURCE").table(new Table("P", List.of(number("ID"), number("NEW_COL")), List.of(), Map.of(), null, null));
        Fixture target = new Fixture("TARGET").table(table("P"));
        target.incoming = List.of(new ForeignKeyReference("OTHER", "CHILD", "EXT_FK", "TARGET", "P"));
        SQLException failure = assertThrows(SQLException.class, () -> plan(source, target));
        assertTrue(failure.getMessage().contains("EXT_FK"));
    }

    @Test
    void exclusionsApplyCaseInsensitivelyToBothSchemasAndDependentIndexes() throws Exception {
        Fixture source = new Fixture("SOURCE").table(table("KEEP_TABLE")).table(table("NEW_TABLE"));
        Fixture target = new Fixture("TARGET").table(table("KEEP_TABLE"));
        target.index(index("DIFFERENT_INDEX_NAME", "TARGET", "KEEP_TABLE", "ID", false));
        target.sequence(sequence("KEEP_SEQ", "10"));
        String sql = plan(source, target, "keep_*");
        assertTrue(sql.contains("CREATE TABLE \"TARGET\".\"NEW_TABLE\""));
        assertFalse(sql.contains("KEEP_TABLE"));
        assertFalse(sql.contains("KEEP_SEQ"));
        assertFalse(sql.contains("DIFFERENT_INDEX_NAME"));
    }

    @Test
    void generatedConstraintMatchesExplicitNameProducedByPreviousSynchronization() throws Exception {
        Fixture source = new Fixture("SOURCE").table(table("T", notNull("SYS_C001", true)));
        Fixture target = new Fixture("TARGET").table(table("T", notNull("ORACMP_C_EXISTING", false)));
        assertNoChanges(plan(source, target));
    }

    @Test
    void generatedConstraintNamesDoNotCollideAcrossIndependentlyCreatedDatabases() throws Exception {
        Fixture source = new Fixture("SOURCE").table(table("A", notNull("SYS_C001", true)))
                .table(table("B", notNull("SYS_C002", true)));
        Fixture target = new Fixture("TARGET").table(table("A", notNull("SYS_C002", true)));

        String first = plan(source, target);

        assertFalse(first.contains("DROP CONSTRAINT \"SYS_C002\""));
        var matcher = Pattern.compile("ALTER TABLE \\\"TARGET\\\"\\.\\\"B\\\" MODIFY \\(\\\"ID\\\" CONSTRAINT \\\"([^\\\"]+)\\\" NOT NULL").matcher(first);
        assertTrue(matcher.find(), first);
        String createdName = matcher.group(1);
        assertNotEquals("SYS_C002", createdName);
        target.table(table("B", notNull(createdName, false)));
        assertNoChanges(plan(source, target));
    }

    @Test
    void generatedBackingIndexKeepsEquivalentExistingIndexName() throws Exception {
        Constraint desiredKey = primaryKey("SYS_C001", true, "SOURCE", "SYS_I001");
        Constraint existingKey = primaryKey("APP_PK", false, "TARGET", "APP_PK_IX");
        Fixture source = new Fixture("SOURCE").table(table("T", desiredKey));
        Fixture target = new Fixture("TARGET").table(table("T", existingKey));
        source.index(index("SYS_I001", "SOURCE", "T", "ID", true));
        target.index(index("APP_PK_IX", "TARGET", "T", "ID", false));
        assertNoChanges(plan(source, target));
    }

    @Test
    void ignoresSequenceRuntimePositionAndCreatesSequencesBeforeColumnDefaults() throws Exception {
        Fixture source = new Fixture("SOURCE").sequence(sequence("ID_SEQ", "1000"));
        Fixture target = new Fixture("TARGET").sequence(sequence("ID_SEQ", "20"));
        assertNoChanges(plan(source, target));
        Column id = new Column("ID", "NUMBER", null, 22, null, null, null, null,
                "\"SOURCE\".\"ID_SEQ\".NEXTVAL", true, false, false, false, null, null);
        source.table(new Table("T", List.of(id), List.of(), Map.of(), null, null));
        String sql = plan(source, new Fixture("TARGET"));
        before(sql, "CREATE SEQUENCE \"TARGET\".\"ID_SEQ\"", "CREATE TABLE \"TARGET\".\"T\"");
        assertTrue(sql.contains("DEFAULT \"TARGET\".\"ID_SEQ\".NEXTVAL"));
    }

    @Test
    void createsBaseViewsBeforeDependentViewsAndCompilesAfterCreation() throws Exception {
        Fixture source = new Fixture("SOURCE").table(table("T"));
        source.view("Z_BASE", "SELECT ID FROM \"SOURCE\".\"T\"", "T");
        source.view("A_DEPENDENT", "SELECT ID FROM \"SOURCE\".\"Z_BASE\"", "Z_BASE");
        String sql = plan(source, new Fixture("TARGET"));
        before(sql, "CREATE OR REPLACE VIEW \"TARGET\".\"Z_BASE\"", "CREATE OR REPLACE VIEW \"TARGET\".\"A_DEPENDENT\"");
        before(sql, "CREATE OR REPLACE VIEW \"TARGET\".\"A_DEPENDENT\"", "ALTER VIEW \"TARGET\".\"Z_BASE\" COMPILE");
        assertTrue(sql.contains("RAISE_APPLICATION_ERROR"));
    }

    @Test
    void keepsLobDefinitionsWhenNormalizingExistingGeneratedConstraints() throws Exception {
        Lob lob = new Lob("CONTENT", null, Map.of("SECUREFILE", "YES", "COMPRESSION", "NO", "DEDUPLICATION", "NO"));
        List<Column> columns = List.of(number("ID"), new Column("CONTENT", "CLOB", null, 4000, null, null,
                null, null, null, true, false, false, false, null, null));
        Fixture source = new Fixture("SOURCE").table(new Table("T", columns, List.of(notNull("SYS_C001", true)), Map.of(), null, null, List.of(lob)));
        Fixture target = new Fixture("TARGET").table(new Table("T", columns, List.of(notNull("SYS_C002", true)), Map.of(), null, null, List.of(lob)));
        assertNoChanges(plan(source, target));
    }

    private static String plan(Fixture source, Fixture target, String... exclusions) throws SQLException {
        return new SchemaComparisonPlanner().plan(source.schema(), target.schema(), new ExclusionFilter(List.of(exclusions)));
    }

    private static ComparisonPlan comparisonPlan(Fixture source, Fixture target, String... exclusions) throws SQLException {
        return new SchemaComparisonPlanner().comparisonPlan(source.schema(), target.schema(), new ExclusionFilter(List.of(exclusions)));
    }

    private static List<String> operationLabels(ComparisonPlan plan) {
        return plan.operations().stream().map(operation -> operation.action() + " " + operation.objectType() + " "
                + operation.objectName() + (operation.tableName() == null ? "" : " ON " + operation.tableName())).toList();
    }

    /** Vergleicht vollständig zerlegte SQL-Anweisungen; Session-Einstellungen sind keine Objektänderung. */
    private static void assertOperationsMatchScript(ComparisonPlan plan) throws Exception {
        List<String> expected = new ArrayList<>();
        for (ComparisonPlan.Operation operation : plan.operations()) expected.addAll(OracleSqlScript.statements(operation.sql()));
        List<String> actual = OracleSqlScript.statements(plan.script());
        assertEquals(expected, actual.subList(1, actual.size()));
    }

    private static void before(String sql, String first, String second) {
        int firstPosition = sql.indexOf(first), secondPosition = sql.indexOf(second);
        assertTrue(firstPosition >= 0, () -> "Fehlende erste Anweisung: " + first + "\n" + sql);
        assertTrue(secondPosition >= 0, () -> "Fehlende zweite Anweisung: " + second + "\n" + sql);
        assertTrue(firstPosition < secondPosition, () -> "Falsche Reihenfolge: " + first + " / " + second + "\n" + sql);
    }

    private static void assertNoChanges(String sql) {
        assertTrue(sql.contains("PROMPT Keine Unterschiede gefunden."), sql);
        assertFalse(sql.contains("DROP "), sql);
        assertFalse(sql.contains("CREATE "), sql);
        assertFalse(sql.contains("ALTER TABLE"), sql);
    }

    private static Column number(String name) {
        return new Column(name, "NUMBER", null, 22, null, null, null, null, null, true, false, false, false, null, null);
    }

    private static Column varchar(String name, int length) {
        return new Column(name, "VARCHAR2", null, length, null, null, "C", length, null, true, false, false, false, null, null);
    }

    private static Table table(String name, Constraint... constraints) {
        return new Table(name, List.of(number("ID")), List.of(constraints), Map.of(), null, null);
    }

    private static Constraint pk(String name) { return primaryKey(name, false, null, null); }

    private static Constraint primaryKey(String name, boolean generated, String owner, String index) {
        return new Constraint(name, "P", List.of("ID"), null, null, null, List.of(), null,
                false, false, true, true, false, generated, owner, index);
    }

    private static Constraint fk(String name, String owner, String table) {
        return new Constraint(name, "R", List.of("ID"), null, owner, table, List.of("ID"), "NO ACTION",
                false, false, true, true, false, false, null, null);
    }

    private static Constraint check(String name, String expression, boolean generated) {
        return new Constraint(name, "C", List.of("ID"), expression, null, null, List.of(), null,
                false, false, true, true, false, generated, null, null);
    }

    private static Constraint notNull(String name, boolean generated) { return check(name, "\"ID\" IS NOT NULL", generated); }

    private static Index index(String name, String owner, String table, String column, boolean generated) {
        return new Index(name, owner, table, false, List.of(new IndexColumn(SqlText.identifier(column), false)),
                Map.of("INDEX_TYPE", "NORMAL", "GENERATED", generated ? "Y" : "N"), null);
    }

    private static Sequence sequence(String name, String lastNumber) {
        return new Sequence(name, "1", "9999999999999999999999999999", "1", "20", false, false, lastNumber, Map.of());
    }

    /** Hält die Szenarien lesbar; erst schema() erstellt das unveränderliche Produktionsmodell. */
    private static final class Fixture {
        final String owner;
        final Map<String, Table> tables = new LinkedHashMap<>();
        final Map<String, Index> indexes = new LinkedHashMap<>();
        final Map<String, View> views = new LinkedHashMap<>();
        final Map<String, Sequence> sequences = new LinkedHashMap<>();
        final Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        List<ForeignKeyReference> incoming = List.of();

        Fixture(String owner) { this.owner = owner; }
        Fixture table(Table table) { tables.put(table.name(), table); return this; }
        Fixture index(Index index) { indexes.put(index.name(), index); return this; }
        Fixture sequence(Sequence sequence) { sequences.put(sequence.name(), sequence); return this; }
        void view(String name, String text, String... references) {
            views.put(name, new View(name, List.of("ID"), text));
            dependencies.put(name, Set.of(references));
        }
        SchemaDefinition schema() {
            return new SchemaDefinition(owner, tables, indexes, views, sequences, dependencies, incoming, Set.of());
        }
    }
}
