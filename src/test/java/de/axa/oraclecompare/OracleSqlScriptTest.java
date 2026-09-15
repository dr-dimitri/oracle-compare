package de.axa.oraclecompare;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Prüft die Statementgrenzen, bevor Integrations-DDL über echte JDBC-Verbindungen ausgeführt wird. */
class OracleSqlScriptTest {
    @Test
    void preservesQuotedSemicolonsAndComments() throws Exception {
        List<String> sql = OracleSqlScript.statements("""
                CREATE TABLE "A;B" (LABEL VARCHAR2(30) DEFAULT 'one;two''three');
                -- Ein Kommentar mit ; und /
                CREATE VIEW V AS SELECT q'[a;it's /* text */]' AS X, nq'!z;z!' AS Y FROM dual;
                /* weiterer ; Kommentar */
                ALTER TABLE "A;B" ADD N NUMBER;
                """);
        assertEquals(3, sql.size());
        assertEquals("CREATE TABLE \"A;B\" (LABEL VARCHAR2(30) DEFAULT 'one;two''three')", sql.get(0));
        assertTrue(sql.get(1).contains("q'[a;it's /* text */]'"));
        assertTrue(sql.get(1).endsWith("FROM dual"));
        assertTrue(sql.get(2).endsWith("ADD N NUMBER"));
    }

    @Test
    void treatsWholePlSqlBlockAsOneJdbcStatement() throws Exception {
        String block = """
                DECLARE
                  text_value VARCHAR2(100) := q'[first
                /
                last;]';
                BEGIN
                  EXECUTE IMMEDIATE 'DROP INDEX "X"';
                EXCEPTION WHEN OTHERS THEN
                  IF SQLCODE <> -1418 THEN RAISE; END IF;
                END;
                """.strip();
        List<String> sql = OracleSqlScript.statements("""
                -- Generator-Kopf
                SET DEFINE OFF
                SET SQLBLANKLINES ON
                SET SQLTERMINATOR ON
                WHENEVER OSERROR EXIT FAILURE ROLLBACK
                WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
                ALTER SESSION SET CURRENT_SCHEMA = "Mixed";

                """ + block + "\n/\nPROMPT Fertig.\n");
        assertEquals(List.of("ALTER SESSION SET CURRENT_SCHEMA = \"Mixed\"", block), sql);
        assertEquals(sql, OracleSqlScript.statements(("ALTER SESSION SET CURRENT_SCHEMA = \"Mixed\";\n"
                + block + "\n/\n").replace("\n", "\r\n")));
    }

    @Test
    void commentsBeforeBeginDoNotBreakBlockRecognition() throws Exception {
        List<String> sql = OracleSqlScript.statements("-- Comment\n/* More */\nBEGIN\n NULL;\nEND;\n / \n");
        assertEquals(1, sql.size());
        assertTrue(sql.get(0).endsWith("END;"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE T (ID NUMBER)", "BEGIN NULL; END;", "SELECT 'unfinished FROM dual;",
            "SELECT q'[unfinished' FROM dual;", "SELECT \"unfinished FROM dual;", "/* unfinished",
            "/\n", "SET DEFINE ON\n", "@another.sql\n", "SPOOL other.sql\n"
    })
    void rejectsIncompleteOrUnsupportedScriptsBeforeExecution(String sql) {
        assertThrows(IOException.class, () -> OracleSqlScript.statements(sql));
    }

    @Test
    void closesStatementsAndStopsOnFirstJdbcFailureWithoutOwningConnection() throws Exception {
        List<String> executed = new ArrayList<>();
        int[] closed = {0};
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("createStatement")) fail("Connectionverwaltung darf nicht erfolgen: " + method.getName());
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                            (statement, operation, values) -> {
                                if (operation.getName().equals("close")) { closed[0]++; return null; }
                                if (operation.getName().equals("execute")) {
                                    executed.add((String) values[0]);
                                    if (values[0].equals("FAIL")) throw new SQLException("Oracle failure", "42000", 1234);
                                    return false;
                                }
                                throw new AssertionError(operation.getName());
                            });
                });
        SQLException error = assertThrows(SQLException.class,
                () -> OracleSqlScript.execute(connection, List.of("FIRST", "FAIL", "LAST")));
        assertEquals(List.of("FIRST", "FAIL"), executed);
        assertEquals(2, closed[0]);
        assertEquals(1234, error.getErrorCode());
        assertTrue(error.getMessage().contains("Anweisung 2"));
    }
}
