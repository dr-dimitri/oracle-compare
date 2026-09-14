package de.axa.oraclecompare;

import static de.axa.oraclecompare.SchemaModel.*;

import java.io.IOException;
import java.io.Reader;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Kapselt Dictionary-Abfragen und die Oracle-Pakete. Alle Transform-Einstellungen sind
 * handle-lokal; die Connection, ihre Transaktion und SESSION_TRANSFORM bleiben beim Aufrufer.
 */
final class OracleMetadata implements Metadata {
    private final Connection connection;
    private final Map<String, Set<String>> managedTables = new HashMap<>();

    OracleMetadata(Connection connection) { this.connection = connection; }

    /** DBA-Sichten vermeiden einen scheinbar vollständigen Abgleich mit eingeschränkten ALL-Sichten. */
    @Override
    public Snapshot snapshot(String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT username FROM dba_users WHERE username = ?")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Schema existiert nicht: " + schema);
            }
        }
        Map<Type, Map<String, DbObject>> objects = new EnumMap<>(Type.class);
        for (Type type : Type.values()) objects.put(type, new TreeMap<>());
        Set<String> materializedViewLogs = materializedViewLogTables(schema);
        // Storage-Tabellen (Nested Tables, IOT-Overflow, MViews samt Logs) gehören zu ihren Basisobjekten.
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_name, cluster_name, secondary,
                  (SELECT p.partitioning_type FROM dba_part_tables p WHERE p.owner=t.owner AND p.table_name=t.table_name)
                FROM dba_tables t WHERE owner = ? AND nested = 'NO'
                  AND (iot_type IS NULL OR iot_type = 'IOT')
                  AND NOT EXISTS (SELECT 1 FROM dba_mviews m WHERE m.owner=t.owner AND m.mview_name=t.table_name)
                  AND NOT EXISTS (SELECT 1 FROM dba_recyclebin r WHERE r.owner=t.owner AND r.object_name=t.table_name)
                ORDER BY table_name
                """)) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    if (materializedViewLogs.contains(name)) continue;
                    if (rows.getString(2) != null || "Y".equals(rows.getString(3)) || "REFERENCE".equals(rows.getString(4))) {
                        throw new SQLException("Cluster-/Domain-/Reference-Partition-Tabelle benötigt einen eigenen Migrationsplan: " + schema + "." + name);
                    }
                    objects.get(Type.TABLE).put(name, new DbObject(Type.TABLE, name, null));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT index_name, table_name, index_type, table_owner, join_index FROM dba_indexes i
                WHERE owner = ? AND index_type NOT IN ('LOB', 'IOT - TOP')
                  AND NOT EXISTS (SELECT 1 FROM dba_constraints c WHERE c.index_owner=i.owner
                                  AND c.index_name=i.index_name AND c.constraint_type IN ('P','U'))
                ORDER BY index_name
                """)) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    String table = rows.getString(2);
                    if (!schema.equals(rows.getString(4))) {
                        throw new SQLException("Schemaübergreifender Index wird nicht unterstützt: " + schema + "." + name);
                    }
                    if ("YES".equals(rows.getString(5))) {
                        throw new SQLException("Bitmap-Join-Index benötigt einen eigenen Migrationsplan: " + schema + "." + name);
                    }
                    if (!objects.get(Type.TABLE).containsKey(table)) continue;
                    if (rows.getString(3).startsWith("DOMAIN")) {
                        throw new SQLException("Domain-Index benötigt einen eigenen Migrationsplan: " + schema + "." + name);
                    }
                    objects.get(Type.INDEX).put(name, new DbObject(Type.INDEX, name, table));
                }
            }
        }
        readNames(schema, "SELECT view_name FROM dba_views WHERE owner = ? ORDER BY view_name", Type.VIEW, objects);
        readNames(schema, """
                SELECT sequence_name FROM dba_sequences s WHERE sequence_owner = ?
                AND NOT EXISTS (SELECT 1 FROM dba_tab_identity_cols c
                                WHERE c.owner=s.sequence_owner AND c.sequence_name=s.sequence_name)
                ORDER BY sequence_name
                """, Type.SEQUENCE, objects);
        List<ForeignKey> keys = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT constraint_name, table_name FROM dba_constraints
                WHERE owner = ? AND constraint_type = 'R' ORDER BY constraint_name
                """)) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (objects.get(Type.TABLE).containsKey(rows.getString(2))) keys.add(new ForeignKey(rows.getString(1), rows.getString(2)));
                }
            }
        }
        Map<String, Set<String>> dependencies = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name, referenced_name FROM dba_dependencies
                WHERE owner = ? AND type = 'VIEW' AND referenced_owner = ? AND referenced_type = 'VIEW'
                """)) {
            statement.setString(1, schema);
            statement.setString(2, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) dependencies.computeIfAbsent(rows.getString(1), k -> new HashSet<>()).add(rows.getString(2));
            }
        }
        Map<String, String> constraintIndexes = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT index_name, table_name FROM dba_constraints
                WHERE owner=? AND index_owner=? AND constraint_type IN ('P','U') AND index_name IS NOT NULL
                """)) {
            statement.setString(1, schema);
            statement.setString(2, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (objects.get(Type.TABLE).containsKey(rows.getString(2))) constraintIndexes.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        managedTables.put(schema, Set.copyOf(objects.get(Type.TABLE).keySet()));
        return new Snapshot(objects, List.copyOf(keys), dependencies, constraintIndexes);
    }

    /**
     * Ermittelt Logtabellen anhand des Dictionarys, nicht anhand ihres meist mit MLOG$_
     * beginnenden Namens. Sie dürfen weder als Tabellen noch über ihre Indizes und
     * Constraints in den Abgleich gelangen; Oracle verwaltet sie mit dem Masterobjekt.
     */
    private Set<String> materializedViewLogTables(String schema) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT log_table FROM dba_mview_logs WHERE log_owner = ?")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    private void readNames(String schema, String query, Type type, Map<Type, Map<String, DbObject>> objects) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    objects.get(type).put(name, new DbObject(type, name, null));
                }
            }
        }
    }

    @Override
    public String sxml(String schema, String target, DbObject object) throws SQLException {
        String xml = MetadataXml.remapEmbeddedSql(fetch(schema, target, object.type().name(), object.name(), true), schema, target);
        return object.type() == Type.SEQUENCE ? MetadataXml.withoutSequencePosition(xml) : xml;
    }

    @Override
    public String ddl(String schema, String target, DbObject object) throws SQLException {
        return SqlText.remap(fetch(schema, target, object.type().name(), object.name(), false), schema, target);
    }

    @Override
    public String foreignKeyDdl(String schema, String target, ForeignKey key) throws SQLException {
        return SqlText.remap(fetch(schema, target, "REF_CONSTRAINT", key.name(), false), schema, target);
    }

    /**
     * XML -> MODIFY(REMAP_SCHEMA) -> SXML bzw. DDL; keine globalen Session-Einstellungen.
     * Tabellen enthalten ihre PK-/UK-/Check-Constraints, aber keine Fremdschlüssel.
     * Diese Trennung gilt auch für den SXML-Vergleich, damit weder CREATE noch ALTER
     * einen Fremdschlüssel vorzeitig anlegen. REF_CONSTRAINT wird separat exportiert.
     */
    private String fetch(String schema, String target, String type, String name, boolean sxml) throws SQLException {
        String sql = """
                DECLARE
                  h NUMBER; t NUMBER; result CLOB;
                  object_type VARCHAR2(30) := ?;
                  owner_name VARCHAR2(128) := ?;
                  object_name VARCHAR2(128) := ?;
                  target_name VARCHAR2(128) := ?;
                BEGIN
                  h := DBMS_METADATA.OPEN(object_type);
                  DBMS_METADATA.SET_FILTER(h, 'SCHEMA', owner_name);
                  DBMS_METADATA.SET_FILTER(h, 'NAME', object_name);
                  t := DBMS_METADATA.ADD_TRANSFORM(h, 'MODIFY');
                  DBMS_METADATA.SET_REMAP_PARAM(t, 'REMAP_SCHEMA', owner_name, target_name);
                  t := DBMS_METADATA.ADD_TRANSFORM(h, '%s');
                  %s
                  IF object_type = 'TABLE' THEN
                    DBMS_METADATA.SET_TRANSFORM_PARAM(t, 'CONSTRAINTS', TRUE);
                    DBMS_METADATA.SET_TRANSFORM_PARAM(t, 'REF_CONSTRAINTS', FALSE);
                    %s
                  END IF;
                  result := DBMS_METADATA.FETCH_CLOB(h);
                  DBMS_METADATA.CLOSE(h); h := NULL;
                  IF result IS NULL THEN
                    RAISE_APPLICATION_ERROR(-20001, 'Metadaten fehlen: ' || owner_name || '.' || object_name);
                  END IF;
                  ? := result;
                EXCEPTION WHEN OTHERS THEN
                  IF h IS NOT NULL THEN
                    BEGIN DBMS_METADATA.CLOSE(h); EXCEPTION WHEN OTHERS THEN NULL; END;
                  END IF;
                  IF DBMS_LOB.ISTEMPORARY(result) = 1 THEN DBMS_LOB.FREETEMPORARY(result); END IF;
                  RAISE;
                END;
                """.formatted(sxml ? "SXML" : "DDL", sxml ? "" : "DBMS_METADATA.SET_TRANSFORM_PARAM(t, 'SQLTERMINATOR', TRUE);",
                        sxml ? "" : "DBMS_METADATA.SET_TRANSFORM_PARAM(t, 'CONSTRAINTS_AS_ALTER', TRUE);");
        try (CallableStatement statement = connection.prepareCall(sql)) {
            statement.setString(1, type);
            statement.setString(2, schema);
            statement.setString(3, name);
            statement.setString(4, target);
            statement.registerOutParameter(5, Types.CLOB);
            statement.execute();
            return readAndFree(statement.getClob(5));
        } catch (SQLException e) {
            throw new SQLException("Metadaten für " + type + " " + schema + "." + name + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
        }
    }

    /** Ziel als Dokument 1, Referenz als Dokument 2: Das erzeugte ALTER verändert ausschließlich das Ziel. */
    @Override
    public String alterXml(Type type, String actual, String desired) throws SQLException {
        String sql = """
                DECLARE
                  d NUMBER; h NUMBER; t NUMBER; diff CLOB; result CLOB; has_diff BOOLEAN;
                  object_type VARCHAR2(30) := ?;
                BEGIN
                  d := DBMS_METADATA_DIFF.OPENC(object_type);
                  DBMS_METADATA_DIFF.ADD_DOCUMENT(d, ?);
                  DBMS_METADATA_DIFF.ADD_DOCUMENT(d, ?);
                  DBMS_LOB.CREATETEMPORARY(diff, TRUE);
                  DBMS_METADATA_DIFF.FETCH_CLOB(d, diff, has_diff);
                  DBMS_METADATA_DIFF.CLOSE(d); d := NULL;
                  DBMS_LOB.CREATETEMPORARY(result, TRUE);
                  IF has_diff THEN
                    h := DBMS_METADATA.OPENW(object_type);
                    t := DBMS_METADATA.ADD_TRANSFORM(h, 'ALTERXML');
                    DBMS_METADATA.SET_PARSE_ITEM(h, 'ALTERABLE');
                    DBMS_METADATA.CONVERT(h, diff, result);
                    DBMS_METADATA.CLOSE(h); h := NULL;
                  ELSE
                    DBMS_LOB.WRITEAPPEND(result, 12, '<ALTER_XML/>');
                  END IF;
                  DBMS_LOB.FREETEMPORARY(diff);
                  ? := result;
                EXCEPTION WHEN OTHERS THEN
                  IF d IS NOT NULL THEN
                    BEGIN DBMS_METADATA_DIFF.CLOSE(d); EXCEPTION WHEN OTHERS THEN NULL; END;
                  END IF;
                  IF h IS NOT NULL THEN
                    BEGIN DBMS_METADATA.CLOSE(h); EXCEPTION WHEN OTHERS THEN NULL; END;
                  END IF;
                  IF DBMS_LOB.ISTEMPORARY(diff) = 1 THEN DBMS_LOB.FREETEMPORARY(diff); END IF;
                  IF DBMS_LOB.ISTEMPORARY(result) = 1 THEN DBMS_LOB.FREETEMPORARY(result); END IF;
                  RAISE;
                END;
                """;
        String xml = transform(sql, type, List.of(actual, desired));
        if (!"<ALTER_XML/>".equals(xml)) {
            var difference = MetadataXml.inspect(xml);
            if (!difference.hasStatements() && difference.unsupportedReason() == null) {
                throw new SQLException("Oracle erkennt Unterschiede, liefert dafür aber kein ALTER-SQL: " + type);
            }
        }
        return xml;
    }

    /** ALTER_XML -> ALTERDDL; SQLTERMINATOR terminiert jedes Statement, auch bei mehrteiligen Änderungen. */
    @Override
    public String alterDdl(Type type, String alterXml) throws SQLException {
        String sql = """
                DECLARE
                  h NUMBER; t NUMBER; result CLOB;
                  object_type VARCHAR2(30) := ?;
                BEGIN
                  h := DBMS_METADATA.OPENW(object_type);
                  t := DBMS_METADATA.ADD_TRANSFORM(h, 'ALTERDDL');
                  DBMS_METADATA.SET_TRANSFORM_PARAM(t, 'SQLTERMINATOR', TRUE);
                  DBMS_LOB.CREATETEMPORARY(result, TRUE);
                  DBMS_METADATA.CONVERT(h, ?, result);
                  DBMS_METADATA.CLOSE(h); h := NULL;
                  ? := result;
                EXCEPTION WHEN OTHERS THEN
                  IF h IS NOT NULL THEN
                    BEGIN DBMS_METADATA.CLOSE(h); EXCEPTION WHEN OTHERS THEN NULL; END;
                  END IF;
                  IF DBMS_LOB.ISTEMPORARY(result) = 1 THEN DBMS_LOB.FREETEMPORARY(result); END IF;
                  RAISE;
                END;
                """;
        return transform(sql, type, List.of(alterXml));
    }

    /** Bindet XML als CLOB: Auch Metadaten oberhalb der VARCHAR2-Grenze bleiben vollständig. */
    private String transform(String sql, Type type, List<String> documents) throws SQLException {
        try (TemporaryClobs inputs = new TemporaryClobs(); CallableStatement statement = connection.prepareCall(sql)) {
            statement.setString(1, type.name());
            for (int i = 0; i < documents.size(); i++) {
                Clob input = inputs.add(connection.createClob());
                input.setString(1, documents.get(i));
                statement.setClob(i + 2, input);
            }
            int output = documents.size() + 2;
            statement.registerOutParameter(output, Types.CLOB);
            statement.execute();
            return readAndFree(statement.getClob(output));
        }
    }

    private static String readAndFree(Clob clob) throws SQLException {
        if (clob == null) throw new SQLException("Oracle lieferte keinen Metadaten-CLOB (Berechtigungen prüfen).");
        try (TemporaryClobs output = new TemporaryClobs()) {
            output.add(clob);
            try (Reader reader = clob.getCharacterStream()) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[8192];
                int length;
                while ((length = reader.read(buffer)) != -1) text.append(buffer, 0, length);
                return text.toString();
            } catch (IOException e) {
                throw new SQLException("Metadaten-CLOB konnte nicht gelesen werden.", e);
            }
        }
    }

    /** try-with-resources hängt Freigabefehler an den ursprünglichen Fehler an. */
    private static final class TemporaryClobs implements AutoCloseable {
        private final List<Clob> values = new ArrayList<>();

        Clob add(Clob clob) { values.add(clob); return clob; }

        @Override public void close() throws SQLException {
            SQLException failure = null;
            for (Clob value : values) {
                try { value.free(); } catch (SQLException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw failure;
        }
    }

    /** Fremde Schemata werden nicht verändert; deren eingehende FKs würden Tabellen-DDL blockieren. */
    @Override
    public void checkExternalForeignKeys(String target, Set<String> changedTables) throws SQLException {
        if (changedTables.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT fk.owner, fk.constraint_name, pk.table_name, fk.table_name
                FROM dba_constraints fk JOIN dba_constraints pk
                  ON pk.owner=fk.r_owner AND pk.constraint_name=fk.r_constraint_name
                WHERE fk.constraint_type='R' AND pk.owner=?
                """)) {
            statement.setString(1, target);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    boolean managed = target.equals(rows.getString(1))
                            && managedTables.getOrDefault(target, Set.of()).contains(rows.getString(4));
                    if (!managed && changedTables.contains(rows.getString(3))) {
                        throw new SQLException("Externer Fremdschlüssel " + rows.getString(1) + "." + rows.getString(2)
                                + " referenziert die zu ändernde Tabelle " + rows.getString(3) + ".");
                    }
                }
            }
        }
    }
}
