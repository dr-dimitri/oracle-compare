package de.axa.oraclecompare;

import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Types;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static de.axa.oraclecompare.SchemaModel.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Prüft den an Oracle übergebenen Exportvertrag: Tabellen enthalten lokale Constraints,
 * Foreign Keys kommen aus einem eigenen Export. Das JDBC-Double zeichnet nur Aufrufe auf;
 * es führt weder PL/SQL aus noch simuliert es Oracles Metadatentransformationen.
 */
class OracleMetadataForeignKeyTest {
    private static final String SOURCE = "Reference";
    private static final String TARGET = "Target";
    private static final DbObject CHILD = new DbObject(Type.TABLE, "A_CHILD", null);

    @Test
    void tableDdlRequestsPrimaryAndUniqueConstraintsButNoForeignKeys() throws Exception {
        String ddl = """
                CREATE TABLE "Target"."A_CHILD" ("ID" NUMBER, "PARENT_ID" NUMBER);
                ALTER TABLE "Target"."A_CHILD" ADD CONSTRAINT "PK_CHILD" PRIMARY KEY ("ID");
                """;
        JdbcExport jdbc = new JdbcExport(ddl);

        assertEquals(ddl, new OracleMetadata(jdbc.connection).ddl(SOURCE, TARGET, CHILD));

        assertExport(jdbc, "TABLE", CHILD.name(), "DDL");
        assertTableConstraintOptions(jdbc.sql);
        assertTrue(tableOptions(jdbc.sql).contains(
                "DBMS_METADATA.SET_TRANSFORM_PARAM(T, 'CONSTRAINTS_AS_ALTER', TRUE);"));
        assertBefore(jdbc.sql, "DBMS_METADATA.SET_TRANSFORM_PARAM(T, 'SQLTERMINATOR', TRUE);",
                "RESULT := DBMS_METADATA.FETCH_CLOB(H);");
        jdbc.assertReleased();
    }

    @Test
    void tableSxmlComparisonAlsoExcludesForeignKeysAndKeepsLocalConstraints() throws Exception {
        JdbcExport jdbc = new JdbcExport("<TABLE><NAME>A_CHILD</NAME></TABLE>");

        String sxml = new OracleMetadata(jdbc.connection).sxml(SOURCE, TARGET, CHILD);

        assertTrue(sxml.contains("<TABLE><NAME>A_CHILD</NAME></TABLE>"));
        assertExport(jdbc, "TABLE", CHILD.name(), "SXML");
        assertTableConstraintOptions(jdbc.sql);
        assertFalse(jdbc.sql.contains("'SQLTERMINATOR'"), "DDL-Optionen gehören nicht an den SXML-Transform.");
        assertFalse(jdbc.sql.contains("'CONSTRAINTS_AS_ALTER'"));
        jdbc.assertReleased();
    }

    @Test
    void foreignKeyExportUsesItsOwnObjectTypeAndReturnsEveryTerminatedStatement() throws Exception {
        String ddl = """
                ALTER TABLE "Reference"."A_CHILD" ADD CONSTRAINT "FK_CHILD_PARENT"
                  FOREIGN KEY ("PARENT_ID") REFERENCES "Reference"."Z_PARENT" ("ID") DISABLE;
                ALTER TABLE "Reference"."A_CHILD" ENABLE VALIDATE CONSTRAINT "FK_CHILD_PARENT";
                """;
        JdbcExport jdbc = new JdbcExport(ddl);

        String actual = new OracleMetadata(jdbc.connection).foreignKeyDdl(
                SOURCE, TARGET, new ForeignKey("FK_CHILD_PARENT", "A_CHILD"));

        assertEquals(ddl.replace("\"Reference\".", "\"Target\"."), actual);
        assertExport(jdbc, "REF_CONSTRAINT", "FK_CHILD_PARENT", "DDL");
        // SQLTERMINATOR muss auch außerhalb des TABLE-Blocks gelten, also für REF_CONSTRAINT.
        assertBefore(jdbc.sql, "DBMS_METADATA.SET_TRANSFORM_PARAM(T, 'SQLTERMINATOR', TRUE);",
                "IF OBJECT_TYPE = 'TABLE' THEN");
        jdbc.assertReleased();
    }

    /** Prüft die handle-lokale Transformkette und die getrennt gebundenen Objektnamen. */
    private static void assertExport(JdbcExport jdbc, String type, String name, String transform) {
        assertEquals(Map.of(1, type, 2, SOURCE, 3, name, 4, TARGET), jdbc.parameters);
        assertEquals(Map.of(5, Types.CLOB), jdbc.outputParameters);
        assertEquals(1, jdbc.executions);
        assertBefore(jdbc.sql, "H := DBMS_METADATA.OPEN(OBJECT_TYPE);",
                "DBMS_METADATA.SET_FILTER(H, 'SCHEMA', OWNER_NAME);",
                "DBMS_METADATA.SET_FILTER(H, 'NAME', OBJECT_NAME);",
                "T := DBMS_METADATA.ADD_TRANSFORM(H, 'MODIFY');",
                "DBMS_METADATA.SET_REMAP_PARAM(T, 'REMAP_SCHEMA', OWNER_NAME, TARGET_NAME);",
                "T := DBMS_METADATA.ADD_TRANSFORM(H, '" + transform + "');",
                "RESULT := DBMS_METADATA.FETCH_CLOB(H);",
                "DBMS_METADATA.CLOSE(H); H := NULL;");
        assertFalse(jdbc.sql.contains("SESSION_TRANSFORM"));
    }

    /** Die Einstellungen müssen am TABLE-Transform vor dem Export gesetzt werden. */
    private static void assertTableConstraintOptions(String sql) {
        String options = tableOptions(sql);
        assertTrue(options.contains("DBMS_METADATA.SET_TRANSFORM_PARAM(T, 'REF_CONSTRAINTS', FALSE);"));
        assertTrue(options.contains("DBMS_METADATA.SET_TRANSFORM_PARAM(T, 'CONSTRAINTS', TRUE);"));
        assertBefore(sql, "IF OBJECT_TYPE = 'TABLE' THEN", "END IF;",
                "RESULT := DBMS_METADATA.FETCH_CLOB(H);");
    }

    private static String tableOptions(String sql) {
        int start = sql.indexOf("IF OBJECT_TYPE = 'TABLE' THEN");
        assertTrue(start >= 0, "Tabelleneinstellungen fehlen im PL/SQL-Block.");
        int end = sql.indexOf("END IF;", start);
        assertTrue(end > start);
        return sql.substring(start, end);
    }

    private static void assertBefore(String sql, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int next = sql.indexOf(fragment, previous + 1);
            assertTrue(next > previous, "Fehlender oder falsch positionierter Oracle-Aufruf: " + fragment);
            previous = next;
        }
    }

    /** Ein aufgezeichnetes CallableStatement und ein geliefertes CLOB; weitere JDBC-Aufrufe schlagen fehl. */
    private static final class JdbcExport {
        private final Map<Integer, String> parameters = new HashMap<>();
        private final Map<Integer, Integer> outputParameters = new HashMap<>();
        private String sql;
        private int executions;
        private int statementCloses;
        private int clobFrees;
        private boolean readerClosed;
        private final Connection connection;

        JdbcExport(String response) {
            StringReader reader = new StringReader(response) {
                @Override public void close() {
                    readerClosed = true;
                    super.close();
                }
            };
            Clob clob = proxy(Clob.class, (object, method, args) -> switch (method.getName()) {
                case "getCharacterStream" -> reader;
                case "free" -> { clobFrees++; yield null; }
                default -> throw new AssertionError("Unerwarteter CLOB-Aufruf: " + method.getName());
            });
            CallableStatement statement = proxy(CallableStatement.class, (object, method, args) -> switch (method.getName()) {
                case "setString" -> { parameters.put((Integer) args[0], (String) args[1]); yield null; }
                case "registerOutParameter" -> { outputParameters.put((Integer) args[0], (Integer) args[1]); yield null; }
                case "execute" -> { executions++; yield false; }
                case "getClob" -> {
                    assertEquals(1, executions);
                    assertEquals(5, args[0]);
                    yield clob;
                }
                case "close" -> { statementCloses++; yield null; }
                default -> throw new AssertionError("Unerwarteter Statement-Aufruf: " + method.getName());
            });
            connection = proxy(Connection.class, (object, method, args) -> {
                if (!"prepareCall".equals(method.getName())) {
                    throw new AssertionError("Die Connection darf nicht verändert werden: " + method.getName());
                }
                assertNull(sql, "Jeder Export benötigt genau einen Metadatenaufruf.");
                sql = ((String) args[0]).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
                return statement;
            });
        }

        void assertReleased() {
            assertEquals(1, statementCloses);
            assertEquals(1, clobFrees);
            assertTrue(readerClosed);
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }
    }
}
