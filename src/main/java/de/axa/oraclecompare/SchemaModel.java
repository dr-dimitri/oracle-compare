package de.axa.oraclecompare;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Interne, unveränderliche Beschreibungen der vom Abgleich verwalteten Objekte. */
final class SchemaModel {
    private SchemaModel() { }

    enum Type { SEQUENCE, TABLE, INDEX, VIEW }

    /** Bei Indizes bezeichnet tableName die zugehörige Tabelle. */
    record DbObject(Type type, String name, String tableName) { }

    /** Fremdschlüssel werden getrennt von Tabellen angelegt, um Zyklen aufzulösen. */
    record ForeignKey(String name, String tableName) { }

    record Snapshot(Map<Type, Map<String, DbObject>> objects, List<ForeignKey> foreignKeys,
                    Map<String, Set<String>> viewDependencies, Map<String, String> constraintIndexes) {
        Snapshot(Map<Type, Map<String, DbObject>> objects, List<ForeignKey> foreignKeys,
                 Map<String, Set<String>> viewDependencies) {
            this(objects, foreignKeys, viewDependencies, Map.of());
        }
        Map<String, DbObject> objects(Type type) { return objects.getOrDefault(type, Map.of()); }
    }

    /** Trennt JDBC/Oracle von der deterministischen Planung und ermöglicht isolierte Tests. */
    interface Metadata {
        Snapshot snapshot(String schema) throws SQLException;
        /** TABLE-SXML enthält PK/UK/Checks, aber keine Foreign Keys; diese werden separat verglichen. */
        String sxml(String schema, String target, DbObject object) throws SQLException;
        /** TABLE-DDL enthält die vollständige Tabelle samt PK/UK/Checks, aber keine Foreign Keys. */
        String ddl(String schema, String target, DbObject object) throws SQLException;
        /** Vollständiges, terminiertes ALTER TABLE zum separaten Anlegen genau dieses Fremdschlüssels. */
        String foreignKeyDdl(String schema, String target, ForeignKey key) throws SQLException;
        String alterXml(Type type, String actual, String desired) throws SQLException;
        String alterDdl(Type type, String alterXml) throws SQLException;
        void checkExternalForeignKeys(String target, Set<String> changedTables) throws SQLException;
    }
}
