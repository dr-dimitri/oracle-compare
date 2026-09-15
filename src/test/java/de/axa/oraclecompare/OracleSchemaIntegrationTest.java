package de.axa.oraclecompare;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Testet Fixture-Parametrisierung und Vorprüfungen ohne Oracle oder serverseitige Dateizugriffe. */
class OracleSchemaIntegrationTest {
    @Test
    void loadsSeparateSchemaNeutralFixturesAndKeepsForeignKeyCases() throws Exception {
        String reference = String.join(";\n", OracleSchemaIntegration.fixture("reference.sql", "Mixed\"Reference"));
        String target = String.join(";\n", OracleSchemaIntegration.fixture("target.sql", "OtherTarget"));
        assertFalse(reference.contains("${"));
        assertFalse(target.contains("${"));
        assertFalse(reference.contains("ORACMP_REF"));
        assertFalse(target.contains("ORACMP_TARGET"));
        assertTrue(reference.contains("\"Mixed\"\"Reference\".PARENT"));
        assertFalse(reference.contains("OtherTarget"));
        assertFalse(target.contains("Mixed"));
        assertTrue(reference.contains("A_FK_SELF_FK"));
        assertTrue(reference.contains("X_FK_Y_FK"));
        assertTrue(reference.contains("Y_FK_X_FK"));
        assertTrue(reference.contains("CONSTRAINT CK_MOVED CHECK"));
        assertTrue(target.contains("CONSTRAINT CK_MOVED CHECK"));
        assertTrue(reference.contains("PARTITION BY RANGE"));
        assertTrue(reference.contains("PARTITION BY LIST"));
        assertTrue(reference.contains("GLOBAL PARTITION BY RANGE"));
        assertTrue(reference.contains("PART_RANGE_SALES(REGION) LOCAL"));
        assertTrue(target.contains("EXTRA_PART_TABLE"));
    }

    @Test
    void externalFixturesUseSameGeneratedFileWithIndependentSchemaAndTableNames() throws Exception {
        String file = "oracle_compare_0123456789abcdef0123456789abcdef.dmp";
        List<String> source = OracleSchemaIntegration.externalFixture("Ref", "EXT_DATAPUMP", file);
        List<String> target = OracleSchemaIntegration.externalFixture("Target", "ORACMP_EXTERNAL_SEED", file);
        assertEquals(1, source.size());
        assertEquals(1, target.size());
        assertTrue(source.get(0).contains("\"Ref\".\"EXT_DATAPUMP\""));
        assertTrue(target.get(0).contains("\"Target\".\"ORACMP_EXTERNAL_SEED\""));
        for (String sql : List.of(source.get(0), target.get(0))) {
            assertTrue(sql.contains("TYPE ORACLE_DATAPUMP"));
            assertTrue(sql.contains("DEFAULT DIRECTORY EXPORT_HOST"));
            assertTrue(sql.contains("LOCATION ('" + file + "')"));
            assertFalse(sql.contains("${"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> OracleSchemaIntegration.externalFixture("Ref", "EXT", "unexpected'; DROP TABLE T;--"));
    }

    @Test
    void checksBothSchemasBeforeIssuingAnyDdl() {
        List<String> checked = new ArrayList<>();
        Connection reference = emptyCheckConnection("MixedReference", false, checked);
        Connection target = emptyCheckConnection("MixedTarget", true, checked);
        SQLException error = assertThrows(SQLException.class,
                () -> OracleSchemaIntegration.run(reference, target, Path.of("unused.sql")));
        assertEquals(List.of("MixedReference", "MixedTarget"), checked);
        assertTrue(error.getMessage().contains("leeres Schema"));
        assertTrue(error.getMessage().contains("MixedTarget.EXISTING_OBJECT"));
    }

    @Test
    void rejectsSameConnectionBeforeDatabaseMutation() {
        Connection connection = emptyCheckConnection("Test", false, new ArrayList<>());
        assertThrows(IllegalArgumentException.class,
                () -> OracleSchemaIntegration.run(connection, connection, Path.of("unused.sql")));
    }

    @Test
    void rejectsMissingCurrentSchemaBeforeDatabaseMutation() {
        Connection connection = emptyCheckConnection(null, false, new ArrayList<>());
        SQLException error = assertThrows(SQLException.class,
                () -> OracleSchemaIntegration.run(connection,
                        emptyCheckConnection("Target", false, new ArrayList<>()), Path.of("unused.sql")));
        assertTrue(error.getMessage().contains("kein aktuelles Schema"));
    }

    @Test
    void directoryAccessFailureStopsBeforeFixtureDdl() {
        List<String> checked = new ArrayList<>();
        Connection basicReference = emptyCheckConnection("Reference", false, checked);
        Connection reference = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareCall")) {
                        assertTrue(((String) args[0]).contains("UTL_FILE.FGETATTR('EXPORT_HOST'"));
                        throw new SQLException("Directory access denied", "42000", 29289);
                    }
                    return Proxy.getInvocationHandler(basicReference).invoke(basicReference, method, args);
                });
        SQLException error = assertThrows(SQLException.class,
                () -> OracleSchemaIntegration.run(reference,
                        emptyCheckConnection("Target", false, checked), Path.of("unused.sql")));
        assertEquals(List.of("Reference", "Target"), checked);
        assertEquals(29289, error.getErrorCode());
    }

    @Test
    void recognizesTwoConnectionsToTheSameSchemaAndRemovesOnlyItsProbe() {
        List<String> ddl = new ArrayList<>();
        SQLException error = assertThrows(SQLException.class,
                () -> OracleSchemaIntegration.run(probeConnection(false, ddl),
                        probeConnection(true, ddl), Path.of("unused.sql")));
        assertTrue(error.getMessage().contains("dasselbe Testschema"));
        assertEquals(2, ddl.size());
        assertTrue(ddl.get(0).startsWith("CREATE TABLE \"Same\".\"OC_PROBE_"));
        assertEquals(ddl.get(0).replace("CREATE TABLE ", "DROP TABLE ").replace(" (ID NUMBER)", " PURGE"), ddl.get(1));
    }

    /** Der Zielstub sieht die Probe nur im gemeinsam genutzten Schema, nie während der Leerprüfung. */
    private Connection probeConnection(boolean probeVisible, List<String> ddl) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (connection, operation, args) -> switch (operation.getName()) {
                    case "isClosed" -> false;
                    case "getSchema" -> "Same";
                    case "createStatement" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                            (statement, action, values) -> switch (action.getName()) {
                                case "execute" -> { ddl.add((String) values[0]); yield false; }
                                case "close" -> null;
                                default -> throw new AssertionError(action.getName());
                            });
                    case "prepareStatement" -> {
                        boolean isProbeQuery = ((String) args[0]).contains("object_name = ?");
                        yield Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (statement, action, values) -> switch (action.getName()) {
                                    case "setString", "close" -> null;
                                    case "executeQuery" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                                            (rows, read, columns) -> switch (read.getName()) {
                                                case "next" -> isProbeQuery && probeVisible;
                                                case "close" -> null;
                                                default -> throw new AssertionError(read.getName());
                                            });
                                    default -> throw new AssertionError(action.getName());
                                });
                    }
                    default -> throw new AssertionError("Unerwartete Connectionänderung: " + operation.getName());
                });
    }

    private Connection emptyCheckConnection(String schema, boolean occupied, List<String> checked) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (connection, operation, args) -> switch (operation.getName()) {
                    case "isClosed" -> false;
                    case "getSchema" -> schema;
                    case "prepareStatement" -> {
                        assertTrue(((String) args[0]).contains("FROM dba_objects"));
                        yield Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (statement, action, values) -> switch (action.getName()) {
                                    case "setString" -> { assertEquals(schema, values[1]); checked.add((String) values[1]); yield null; }
                                    case "close" -> null;
                                    case "executeQuery" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                                            (rows, read, columns) -> switch (read.getName()) {
                                                case "next" -> occupied;
                                                case "getString" -> columns[0].equals(1) ? "EXISTING_OBJECT" : "TABLE";
                                                case "close" -> null;
                                                default -> throw new AssertionError(read.getName());
                                            });
                                    default -> throw new AssertionError(action.getName());
                                });
                    }
                    default -> throw new AssertionError("Unerwartete Connectionänderung: " + operation.getName());
                });
    }
}
