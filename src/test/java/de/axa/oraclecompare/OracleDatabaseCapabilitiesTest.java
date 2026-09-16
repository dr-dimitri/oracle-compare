package de.axa.oraclecompare;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft Zielparameter einschließlich Fehlerpfaden ohne Änderungen an der JDBC-Connection. */
class OracleDatabaseCapabilitiesTest {
    @ParameterizedTest
    @CsvSource({
            "STANDARD, 19.0.0, false",
            "EXTENDED, 19.0.0, true",
            "EXTENDED, 12.2.0.0.0, true",
            "EXTENDED, 12.1.0.2.0, false",
            "EXTENDED, 11.2.0.4.0, false",
            "EXTENDED, 21.0, true",
            "standard, 12.2, false"
    })
    void detectsBothRequiredCapabilitiesAndClosesQueryResources(String maxStringSize, String compatible,
                                                               boolean supported) throws Exception {
        Database database = new Database(maxStringSize, compatible);

        assertEquals(supported, OracleDatabaseCapabilities.read(database.connection).supportsDataBoundCollation());

        database.assertReleased();
    }

    @ParameterizedTest
    @CsvSource({"CDB$ROOT, false", "APP_PDB, true", "DB19, true"})
    void distinguishesRootFromPluggableAndNonContainerDatabase(String container, boolean supported) throws Exception {
        Database database = new Database("EXTENDED", "19.0.0");
        database.containerName = container;

        assertEquals(supported, OracleDatabaseCapabilities.read(database.connection).supportsDataBoundCollation());

        database.assertReleased();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsUnknownContainerInsteadOfAssumingCollationSupport(String container) {
        Database database = new Database("EXTENDED", "19.0.0");
        database.containerName = container;

        SQLException error = assertThrows(SQLException.class, () -> OracleDatabaseCapabilities.read(database.connection));

        assertTrue(error.getMessage().contains("CON_NAME"));
        database.assertReleased();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "UNKNOWN", "EXTENDED PLUS"})
    void rejectsMissingOrUnknownStringSize(String maxStringSize) {
        Database database = new Database(maxStringSize, "19.0.0");

        SQLException error = assertThrows(SQLException.class, () -> OracleDatabaseCapabilities.read(database.connection));

        assertTrue(error.getMessage().toUpperCase().contains("MAX_STRING_SIZE"));
        database.assertReleased();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "19", "19.0.0.0.0.0", "19.0-extra", "-1.0", "12.x", "NaN"})
    void rejectsMissingOrInvalidCompatibilityEvenIfStandard(String compatible) {
        Database database = new Database("STANDARD", compatible);

        SQLException error = assertThrows(SQLException.class, () -> OracleDatabaseCapabilities.read(database.connection));

        assertTrue(error.getMessage().toUpperCase().contains("COMPATIBLE"));
        database.assertReleased();
    }

    @Test
    void rejectsMissingParameterRow() {
        Database database = new Database("EXTENDED", "19.0.0");
        database.parameters.remove(1);

        SQLException error = assertThrows(SQLException.class, () -> OracleDatabaseCapabilities.read(database.connection));

        assertTrue(error.getMessage().contains("compatible"));
        database.assertReleased();
    }

    @Test
    void rejectsDuplicateAndUnknownParameterRows() {
        for (String name : List.of("max_string_size", "unrequested_parameter")) {
            Database database = new Database("EXTENDED", "19.0.0");
            database.parameters.add(new String[]{name, "EXTENDED"});

            assertThrows(SQLException.class, () -> OracleDatabaseCapabilities.read(database.connection));

            database.assertReleased();
        }
    }

    @Test
    void closesStatementWhenQueryFailsAndPreservesDatabaseError() {
        Database database = new Database("EXTENDED", "19.0.0");
        database.queryFailure = new SQLException("Missing privilege", "42000", 942);

        SQLException error = assertThrows(SQLException.class, () -> OracleDatabaseCapabilities.read(database.connection));

        assertSame(database.queryFailure, error);
        database.assertReleased();
    }

    @Test
    void closesAllResourcesWhenResultSetFails() {
        Database database = new Database("EXTENDED", "19.0.0");
        database.readFailure = new SQLException("Connection interrupted", "08006", 17002);

        SQLException error = assertThrows(SQLException.class, () -> OracleDatabaseCapabilities.read(database.connection));

        assertSame(database.readFailure, error);
        database.assertReleased();
    }

    /** Das Testdouble lehnt Close/Commit/Session-Operationen auf der Connection ausdrücklich ab. */
    private static final class Database {
        final List<String[]> parameters = new ArrayList<>();
        int statements;
        int resultSets;
        SQLException queryFailure;
        SQLException readFailure;
        String containerName = "APP_PDB";
        final Connection connection = proxy(Connection.class, (object, method, args) -> {
            assertEquals("prepareStatement", method.getName(), "Connection bleibt unter Kontrolle des Aufrufers");
            String sql = ((String) args[0]).replaceAll("\\s+", " ").strip();
            assertEquals("SELECT name, value, SYS_CONTEXT('USERENV', 'CON_NAME') AS container_name FROM v$parameter "
                    + "WHERE name IN ('max_string_size', 'compatible')", sql);
            statements++;
            return proxy(PreparedStatement.class, (statement, operation, arguments) -> switch (operation.getName()) {
                case "executeQuery" -> {
                    if (queryFailure != null) throw queryFailure;
                    yield rows();
                }
                case "close" -> { statements--; yield null; }
                default -> throw new AssertionError("Unerwartete Statement-Operation: " + operation.getName());
            });
        });

        Database(String maxStringSize, String compatible) {
            parameters.add(new String[]{"max_string_size", maxStringSize});
            parameters.add(new String[]{"compatible", compatible});
        }

        ResultSet rows() {
            resultSets++;
            int[] cursor = {-1};
            return proxy(ResultSet.class, (object, method, args) -> switch (method.getName()) {
                case "next" -> {
                    if (readFailure != null) throw readFailure;
                    yield ++cursor[0] < parameters.size();
                }
                case "getString" -> switch ((String) args[0]) {
                    case "NAME" -> parameters.get(cursor[0])[0];
                    case "VALUE" -> parameters.get(cursor[0])[1];
                    case "CONTAINER_NAME" -> containerName;
                    default -> throw new AssertionError("Unbekannte Parameterspalte: " + args[0]);
                };
                case "close" -> { resultSets--; yield null; }
                default -> throw new AssertionError("Unerwartete ResultSet-Operation: " + method.getName());
            });
        }

        void assertReleased() {
            assertEquals(0, statements);
            assertEquals(0, resultSets);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
