package de.axa.oraclecompare;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static de.axa.oraclecompare.SchemaDefinition.*;

/**
 * Liest Oracle-19c-Definitionen ausschließlich mit parametrisierten SELECT-Abfragen.
 * Die Connection bleibt Eigentum des Aufrufers; weder Transaktions- noch Sessionzustand
 * werden geändert. Nicht darstellbare Definitionen führen vor der SQL-Erzeugung zum Abbruch.
 */
final class OracleDictionaryReader {
    private static final List<String> PHYSICAL = List.of("TABLESPACE_NAME", "PCT_FREE", "PCT_USED",
            "INI_TRANS", "MAX_TRANS", "INITIAL_EXTENT", "NEXT_EXTENT", "MIN_EXTENTS", "MAX_EXTENTS",
            "PCT_INCREASE", "FREELISTS", "FREELIST_GROUPS", "BUFFER_POOL", "FLASH_CACHE", "CELL_FLASH_CACHE",
            "LOGGING", "COMPRESSION", "COMPRESS_FOR");
    private final Connection connection;

    /** Übernimmt ausschließlich eine Referenz auf die bereits geöffnete JDBC-Connection. */
    OracleDictionaryReader(Connection connection) { this.connection = Objects.requireNonNull(connection, "connection"); }

    /**
     * Liest ein Schema vollständig genug für Definitionen und Ausschlussschutz. Ausgeschlossene
     * Tabellen behalten ihre Constraints; interne Domain-Speichertabellen erben den Ausschluss.
     */
    SchemaDefinition read(String owner, ExclusionFilter exclusions) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(exclusions, "exclusions");
        if (query("SELECT username FROM dba_users WHERE username = ?", owner).isEmpty()) {
            throw new SQLException("Schema existiert nicht oder ist nicht sichtbar: " + owner);
        }
        List<Row> tableRows = query("""
                SELECT t.* FROM dba_tables t
                 WHERE t.owner = ? AND t.nested = 'NO' AND NVL(t.dropped, 'NO') = 'NO'
                   AND (t.iot_type IS NULL OR t.iot_type = 'IOT')
                   AND NOT EXISTS (SELECT 1 FROM dba_mviews m WHERE m.owner = t.owner AND m.mview_name = t.table_name)
                   AND NOT EXISTS (SELECT 1 FROM dba_mview_logs m WHERE m.log_owner = t.owner AND m.log_table = t.table_name)
                 ORDER BY t.table_name
                """, owner);
        List<Row> indexRows = query("""
                SELECT i.* FROM dba_indexes i WHERE i.owner = ?
                   AND i.index_type NOT IN ('LOB', 'IOT - TOP')
                   AND NVL(i.dropped, 'NO') = 'NO' ORDER BY i.index_name
                """, owner);
        Set<String> excludedTables = excludedTables(owner, exclusions, tableRows, indexRows);
        Map<String, List<Constraint>> constraints = readConstraints(owner);
        Map<String, List<Column>> columns = readColumns(owner, excludedTables);
        Map<String, List<Lob>> lobs = readLobs(owner, excludedTables, columns);
        Map<String, Row> partitionTables = byName(query("SELECT p.* FROM dba_part_tables p WHERE owner = ?", owner), "TABLE_NAME");
        Map<String, Row> partitionIndexes = byName(query("SELECT p.* FROM dba_part_indexes p WHERE owner = ?", owner), "INDEX_NAME");
        Map<String, Row> externalTables = byName(query("SELECT e.* FROM dba_external_tables e WHERE owner = ?", owner), "TABLE_NAME");
        Map<String, Integer> blockSizes = new LinkedHashMap<>();
        for (Row row : query("SELECT tablespace_name, block_size FROM dba_tablespaces")) {
            blockSizes.put(row.get("TABLESPACE_NAME"), row.integer("BLOCK_SIZE"));
        }
        Set<String> archivalTables = names(query("SELECT DISTINCT table_name FROM dba_tab_cols WHERE owner = ? AND column_name = 'ORA_ARCHIVE_STATE' AND user_generated = 'NO'", owner), "TABLE_NAME");
        Set<String> encryptedTables = names(query("SELECT DISTINCT table_name FROM dba_encrypted_columns WHERE owner = ?", owner), "TABLE_NAME");
        Set<String> objectTables = names(query("SELECT table_name FROM dba_object_tables WHERE owner = ?", owner), "TABLE_NAME");
        Set<String> nestedParents = names(query("SELECT DISTINCT parent_table_name FROM dba_nested_tables WHERE owner = ?", owner), "PARENT_TABLE_NAME");
        Set<String> templateTables = names(query("SELECT DISTINCT table_name FROM dba_subpartition_templates WHERE user_name = ?", owner), "TABLE_NAME");
        Map<String, Table> tables = new LinkedHashMap<>();
        for (Row row : tableRows) {
            String name = row.get("TABLE_NAME");
            boolean excluded = excludedTables.contains(name);
            if (!excluded) {
                validateTable(owner, row, archivalTables, encryptedTables, objectTables, nestedParents);
                if (templateTables.contains(name)) unsupported(owner, name, "Subpartition-Template");
            }
            Row partition = partitionTables.get(name);
            Map<String, String> attributes = attributes(row, PHYSICAL);
            addAttributes(attributes, row, "TEMPORARY", "DURATION", "ROW_MOVEMENT", "CACHE", "READ_ONLY", "DEGREE", "INSTANCES", "DEPENDENCIES", "DEFAULT_COLLATION");
            if (!excluded && partition != null) mergePartitionDefaults(attributes, partition, blockSizes, false);
            Partitioning partitioning = excluded || partition == null ? null
                    : readPartitioning(owner, name, partition, false, blockSizes);
            ExternalTable external = excluded || !externalTables.containsKey(name) ? null
                    : readExternal(owner, name, externalTables.get(name));
            tables.put(name, new Table(name, columns.getOrDefault(name, List.of()),
                    constraints.getOrDefault(name, List.of()), attributes, partitioning, external, lobs.getOrDefault(name, List.of())));
        }
        Map<String, Index> indexes = new LinkedHashMap<>();
        for (Row row : indexRows) {
            String name = row.get("INDEX_NAME"), tableName = row.get("TABLE_NAME");
            if (owner.equals(row.get("TABLE_OWNER")) && !tables.containsKey(tableName)) continue;
            boolean excluded = exclusions.excludes(name) || excludedTables.contains(tableName);
            if (!excluded) validateIndex(owner, row);
            Map<String, String> attributes = attributes(row, PHYSICAL);
            addAttributes(attributes, row, "INDEX_TYPE", "JOIN_INDEX", "VISIBILITY", "PREFIX_LENGTH", "DEGREE", "INSTANCES", "GENERATED");
            Row partition = partitionIndexes.get(name);
            if (!excluded && partition != null) mergePartitionDefaults(attributes, partition, blockSizes, true);
            indexes.put(name, new Index(name, row.get("TABLE_OWNER"), tableName,
                    "UNIQUE".equals(row.get("UNIQUENESS")), excluded ? List.of() : readIndexColumns(owner, name), attributes,
                    excluded || partition == null ? null : readPartitioning(owner, name, partition, true, blockSizes)));
        }
        return new SchemaDefinition(owner, tables, indexes, readViews(owner, exclusions, columns, constraints),
                readSequences(owner), readViewDependencies(owner), readIncomingForeignKeys(owner), excludedTables, readConstraintNames(owner));
    }

    /** Erweitert Tabellenausschlüsse rekursiv um interne Speicherobjekte ausgeschlossener Domain-Indizes. */
    private Set<String> excludedTables(String owner, ExclusionFilter filter, List<Row> tables, List<Row> indexes) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        for (Row table : tables) if (filter.excludes(table.get("TABLE_NAME"))) result.add(table.get("TABLE_NAME"));
        Map<String, Row> byIndex = byName(indexes, "INDEX_NAME");
        List<Row> secondary = query("""
                SELECT s.index_owner, s.index_name, s.secondary_object_owner, s.secondary_object_name,
                       i.table_owner AS base_table_owner, i.table_name AS base_table_name
                  FROM dba_secondary_objects s
                  JOIN dba_indexes i ON i.owner = s.index_owner AND i.index_name = s.index_name
                 WHERE s.secondary_object_owner = ?
                """, owner);
        boolean changed;
        do {
            changed = false;
            for (Row row : secondary) {
                Row index = owner.equals(row.get("INDEX_OWNER")) ? byIndex.get(row.get("INDEX_NAME")) : null;
                String baseName = row.get("BASE_TABLE_NAME") != null ? row.get("BASE_TABLE_NAME") : index == null ? null : index.get("TABLE_NAME");
                String baseOwner = row.get("BASE_TABLE_OWNER") != null ? row.get("BASE_TABLE_OWNER") : index == null ? null : index.get("TABLE_OWNER");
                if (filter.excludes(row.get("INDEX_NAME")) || (baseName != null && (filter.excludes(baseName)
                        || (owner.equals(baseOwner) && result.contains(baseName))))) {
                    changed |= result.add(row.get("SECONDARY_OBJECT_NAME"));
                }
            }
        } while (changed);
        return result;
    }

    /** Liest eigenständige BasicFile-/SecureFile-Speicherung einschließlich expliziter Segmentnamen. */
    private Map<String, List<Lob>> readLobs(String owner, Set<String> excludedTables, Map<String, List<Column>> columns) throws SQLException {
        Map<String, List<Lob>> result = new LinkedHashMap<>();
        for (Row row : query("""
                SELECT l.*, o.generated AS segment_generated FROM dba_lobs l
                  LEFT JOIN dba_objects o ON o.owner = l.owner AND o.object_name = l.segment_name AND o.object_type = 'LOB'
                 WHERE l.owner = ? ORDER BY l.table_name, l.column_name
                """, owner)) {
            String table = row.get("TABLE_NAME");
            if (excludedTables.contains(table)) continue;
            if (row.is("PARTITIONED", "YES") || row.is("ENCRYPT", "YES") || row.is("RETENTION_TYPE", "MAX")) {
                unsupported(owner, table, "partitionierte/verschlüsselte LOB-Speicherung oder RETENTION MAX ohne öffentliches MAXSIZE-Metadatum");
            }
            boolean directLobColumn = columns.getOrDefault(table, List.of()).stream().anyMatch(column ->
                    column.name().equals(row.get("COLUMN_NAME")) && Set.of("CLOB", "BLOB", "NCLOB").contains(column.dataType()));
            if (!directLobColumn) unsupported(owner, table, "interne LOB-Speicherung für XML-/Objektattribute oder erweiterte Datentypen");
            Map<String, String> attributes = new LinkedHashMap<>();
            addAttributes(attributes, row, "SECUREFILE", "TABLESPACE_NAME", "CHUNK", "PCTVERSION",
                    "RETENTION_TYPE", "RETENTION_VALUE", "CACHE", "LOGGING", "COMPRESSION", "DEDUPLICATION", "IN_ROW", "FREEPOOLS");
            if (row.is("SECUREFILE", "YES")) {
                // CHUNK/FREEPOOLS/PCTVERSION sind keine einstellbaren SecureFile-Definitionen.
                attributes.remove("CHUNK"); attributes.remove("FREEPOOLS"); attributes.remove("PCTVERSION");
            }
            for (Row segment : query("""
                    SELECT initial_extent, next_extent, min_extents, max_extents, pct_increase, freelists,
                           freelist_groups, buffer_pool, flash_cache, cell_flash_cache
                      FROM dba_segments WHERE owner = ? AND segment_name = ? AND partition_name IS NULL
                    """, owner, row.get("SEGMENT_NAME"))) addAttributes(attributes, segment,
                    "INITIAL_EXTENT", "NEXT_EXTENT", "MIN_EXTENTS", "MAX_EXTENTS", "PCT_INCREASE", "FREELISTS",
                    "FREELIST_GROUPS", "BUFFER_POOL", "FLASH_CACHE", "CELL_FLASH_CACHE");
            String segmentName = row.is("SEGMENT_GENERATED", "Y") ? null : row.get("SEGMENT_NAME");
            result.computeIfAbsent(table, ignored -> new ArrayList<>()).add(new Lob(row.get("COLUMN_NAME"), segmentName, attributes));
        }
        return result;
    }

    /** Liest LONG-Defaults vollständig; unsichtbare Benutzerspalten bleiben im Modell enthalten. */
    private Map<String, List<Column>> readColumns(String owner, Set<String> excludedTables) throws SQLException {
        Map<String, Identity> identities = new LinkedHashMap<>();
        for (Row row : query("SELECT table_name, column_name, generation_type, identity_options FROM dba_tab_identity_cols WHERE owner = ?", owner)) {
            identities.put(key(row.get("TABLE_NAME"), row.get("COLUMN_NAME")),
                    new Identity(row.get("GENERATION_TYPE"), row.get("IDENTITY_OPTIONS")));
        }
        Map<String, List<Column>> result = new LinkedHashMap<>();
        for (Row row : query("""
                SELECT table_name, column_name, data_type, data_type_owner, data_length, data_precision,
                       data_scale, char_used, char_length, nullable, virtual_column, hidden_column,
                       user_generated, default_on_null, collation, identity_column, column_id, internal_column_id, data_default
                  FROM dba_tab_cols WHERE owner = ? AND user_generated = 'YES'
                 ORDER BY table_name, column_id NULLS LAST, internal_column_id
                """, owner)) {
            String table = row.get("TABLE_NAME");
            if (excludedTables.contains(table)) continue;
            Identity identity = identities.get(key(table, row.get("COLUMN_NAME")));
            if (identity != null && "BY DEFAULT".equals(identity.generationType()) && row.is("DEFAULT_ON_NULL", "YES")) {
                identity = new Identity("BY DEFAULT ON NULL", identity.options());
            }
            if (row.is("IDENTITY_COLUMN", "YES") && identity == null) {
                throw new SQLException("Identity-Definition fehlt für " + owner + "." + table + "." + row.get("COLUMN_NAME"));
            }
            result.computeIfAbsent(table, ignored -> new ArrayList<>()).add(new Column(row.get("COLUMN_NAME"),
                    row.get("DATA_TYPE"), row.get("DATA_TYPE_OWNER"), row.integer("DATA_LENGTH"),
                    row.integer("DATA_PRECISION"), row.integer("DATA_SCALE"), row.get("CHAR_USED"),
                    row.integer("CHAR_LENGTH"), identity == null ? normalizeDefault(row.get("DATA_DEFAULT"), row.is("VIRTUAL_COLUMN", "YES")) : null,
                    row.is("NULLABLE", "Y"), row.is("VIRTUAL_COLUMN", "YES"), row.is("HIDDEN_COLUMN", "YES"),
                    row.is("DEFAULT_ON_NULL", "YES"), row.get("COLLATION"), identity));
        }
        return result;
    }

    /** Löst Fremdschlüssel samt referenzierter Spalten auf, ohne gleichnamige Schemanamen vorauszusetzen. */
    private Map<String, List<Constraint>> readConstraints(String owner) throws SQLException {
        Map<String, List<Constraint>> result = new LinkedHashMap<>();
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (Row row : query("SELECT constraint_name, column_name FROM dba_cons_columns WHERE owner = ? ORDER BY constraint_name, position", owner)) {
            columns.computeIfAbsent(row.get("CONSTRAINT_NAME"), ignored -> new ArrayList<>()).add(row.get("COLUMN_NAME"));
        }
        for (Row row : query("""
                SELECT c.table_name, c.constraint_name, c.constraint_type, c.r_owner, c.r_constraint_name,
                       c.delete_rule, c.deferrable, c.deferred, c.status, c.validated, c.rely, c.generated,
                       c.index_owner, c.index_name, c.search_condition
                  FROM dba_constraints c WHERE c.owner = ? AND c.constraint_type IN ('P', 'U', 'C', 'R')
                 ORDER BY c.table_name, c.constraint_name
                """, owner)) {
            String referencedTable = null;
            List<String> referencedColumns = new ArrayList<>();
            if (row.is("CONSTRAINT_TYPE", "R")) {
                List<Row> parent = query("SELECT table_name FROM dba_constraints WHERE owner = ? AND constraint_name = ?",
                        row.get("R_OWNER"), row.get("R_CONSTRAINT_NAME"));
                if (parent.size() != 1) throw new SQLException("Referenziertes Constraint ist nicht sichtbar: " + row.get("R_OWNER") + "." + row.get("R_CONSTRAINT_NAME"));
                referencedTable = parent.get(0).get("TABLE_NAME");
                for (Row column : query("SELECT column_name FROM dba_cons_columns WHERE owner = ? AND constraint_name = ? ORDER BY position",
                        row.get("R_OWNER"), row.get("R_CONSTRAINT_NAME"))) referencedColumns.add(column.get("COLUMN_NAME"));
            }
            result.computeIfAbsent(row.get("TABLE_NAME"), ignored -> new ArrayList<>()).add(new Constraint(
                    row.get("CONSTRAINT_NAME"), row.get("CONSTRAINT_TYPE"), columns.getOrDefault(row.get("CONSTRAINT_NAME"), List.of()),
                    trim(row.get("SEARCH_CONDITION")), row.get("R_OWNER"), referencedTable, referencedColumns,
                    row.get("DELETE_RULE"), row.is("DEFERRABLE", "DEFERRABLE"), row.is("DEFERRED", "DEFERRED"),
                    row.is("STATUS", "ENABLED"), row.is("VALIDATED", "VALIDATED"), row.is("RELY", "RELY"),
                    row.is("GENERATED", "GENERATED NAME"), row.get("INDEX_OWNER"), row.get("INDEX_NAME")));
        }
        return result;
    }

    /** Belegte Constraint-Namen bleiben auch für ausgeschlossene Views und interne Speicherobjekte sichtbar. */
    private Map<String, String> readConstraintNames(String owner) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Row row : query("SELECT constraint_name, table_name FROM dba_constraints WHERE owner = ? AND generated = 'USER NAME'", owner)) {
            result.put(row.get("CONSTRAINT_NAME"), row.get("TABLE_NAME"));
        }
        return result;
    }

    /** Verknüpft normale Indexspalten mit LONG-Funktionsausdrücken anhand ihrer Position. */
    private List<IndexColumn> readIndexColumns(String owner, String name) throws SQLException {
        Map<String, String> expressions = new LinkedHashMap<>();
        for (Row row : query("SELECT column_position, column_expression FROM dba_ind_expressions WHERE index_owner = ? AND index_name = ? ORDER BY column_position", owner, name)) {
            expressions.put(row.get("COLUMN_POSITION"), trim(row.get("COLUMN_EXPRESSION")));
        }
        List<IndexColumn> result = new ArrayList<>();
        for (Row row : query("SELECT column_name, column_position, descend FROM dba_ind_columns WHERE index_owner = ? AND index_name = ? ORDER BY column_position", owner, name)) {
            result.add(new IndexColumn(expressions.getOrDefault(row.get("COLUMN_POSITION"), quote(row.get("COLUMN_NAME"))), row.is("DESCEND", "DESC")));
        }
        if (result.isEmpty()) throw new SQLException("Index ohne sichtbare Spalten: " + owner + "." + name);
        return result;
    }

    /** Liest komplette View-Texte; semantisch zusätzliche View-Optionen werden nicht still verworfen. */
    private Map<String, View> readViews(String owner, ExclusionFilter exclusions, Map<String, List<Column>> columns, Map<String, List<Constraint>> constraints) throws SQLException {
        Map<String, View> result = new LinkedHashMap<>();
        Set<String> alteredVisibility = names(query("""
                SELECT DISTINCT c.table_name FROM dba_tab_cols c
                  JOIN dba_views v ON v.owner = c.owner AND v.view_name = c.table_name
                 WHERE c.owner = ? AND c.user_generated = 'YES'
                   AND (c.hidden_column = 'YES' OR c.column_id <> c.internal_column_id)
                """, owner), "TABLE_NAME");
        for (Row row : query("SELECT view_name, read_only, editioning_view, bequeath, view_type, default_collation, text FROM dba_views WHERE owner = ? ORDER BY view_name", owner)) {
            String name = row.get("VIEW_NAME");
            if (!exclusions.excludes(name) && row.get("DEFAULT_COLLATION") != null
                    && !row.is("DEFAULT_COLLATION", "USING_NLS_COMP")) {
                unsupported(owner, name, "View-DEFAULT COLLATION " + row.get("DEFAULT_COLLATION"));
            }
            if (!exclusions.excludes(name) && (row.is("READ_ONLY", "Y") || row.is("EDITIONING_VIEW", "Y")
                    || row.is("BEQUEATH", "CURRENT_USER") || row.get("VIEW_TYPE") != null || alteredVisibility.contains(name)
                    || !constraints.getOrDefault(name, List.of()).isEmpty())) unsupported(owner, name, "Read-only-/Editioning-/Object-/BEQUEATH-CURRENT_USER-View oder geänderte Spaltensichtbarkeit/View-Constraints");
            result.put(name, new View(name, columns.getOrDefault(name, List.of()).stream().map(Column::name).toList(), trim(row.get("TEXT"))));
        }
        return result;
    }

    /** Liest nur eigenständige Sequenzen; Identity-Sequenzen gehören zur Spaltendefinition. */
    private Map<String, Sequence> readSequences(String owner) throws SQLException {
        Map<String, Sequence> result = new LinkedHashMap<>();
        for (Row row : query("""
                SELECT s.* FROM dba_sequences s WHERE s.sequence_owner = ?
                   AND NOT EXISTS (SELECT 1 FROM dba_tab_identity_cols c WHERE c.owner = s.sequence_owner AND c.sequence_name = s.sequence_name)
                 ORDER BY s.sequence_name
                """, owner)) {
            String name = row.get("SEQUENCE_NAME");
            Map<String, String> attributes = new LinkedHashMap<>();
            addAttributes(attributes, row, "SCALE_FLAG", "EXTEND_FLAG", "SHARDED_FLAG", "SESSION_FLAG", "KEEP_VALUE");
            result.put(name, new Sequence(name, row.get("MIN_VALUE"), row.get("MAX_VALUE"), row.get("INCREMENT_BY"),
                    row.get("CACHE_SIZE"), row.is("CYCLE_FLAG", "Y"), row.is("ORDER_FLAG", "Y"), row.get("LAST_NUMBER"), attributes));
        }
        return result;
    }

    /** Liest direkte lokale View-Abhängigkeiten; der Plan bildet den transitiven Schutz selbst. */
    private Map<String, Set<String>> readViewDependencies(String owner) throws SQLException {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Row row : query("""
                SELECT name, referenced_name FROM dba_dependencies
                 WHERE owner = ? AND type = 'VIEW' AND referenced_owner = ?
                   AND referenced_type IN ('TABLE', 'VIEW', 'SEQUENCE') AND referenced_link_name IS NULL
                 ORDER BY name, referenced_name
                """, owner, owner)) result.computeIfAbsent(row.get("NAME"), ignored -> new LinkedHashSet<>()).add(row.get("REFERENCED_NAME"));
        return result;
    }

    /** Behält auch eingehende FKs aus fremden oder ausgeschlossenen Tabellen für den Schutz des Ziels. */
    private List<ForeignKeyReference> readIncomingForeignKeys(String owner) throws SQLException {
        List<ForeignKeyReference> result = new ArrayList<>();
        for (Row row : query("""
                SELECT c.owner, c.table_name, c.constraint_name, p.owner AS referenced_owner, p.table_name AS referenced_table
                  FROM dba_constraints c JOIN dba_constraints p ON p.owner = c.r_owner AND p.constraint_name = c.r_constraint_name
                 WHERE c.constraint_type = 'R' AND p.owner = ? ORDER BY c.owner, c.table_name, c.constraint_name
                """, owner)) result.add(new ForeignKeyReference(row.get("OWNER"), row.get("TABLE_NAME"), row.get("CONSTRAINT_NAME"), row.get("REFERENCED_OWNER"), row.get("REFERENCED_TABLE")));
        return result;
    }

    /** Liest Treiberoptionen als CLOB und erhält Directory-Zuordnungen für jede einzelne Location. */
    private ExternalTable readExternal(String owner, String name, Row row) throws SQLException {
        if (row.is("ACCESS_TYPE", "BLOB") || (!row.is("TYPE_NAME", "ORACLE_LOADER") && !row.is("TYPE_NAME", "ORACLE_DATAPUMP"))) {
            unsupported(owner, name, "externer Zugriffstreiber/Parameterformat");
        }
        if (row.is("PROPERTY", "REFERENCED")) unsupported(owner, name, "PROJECT COLUMN REFERENCED");
        List<Location> locations = new ArrayList<>();
        for (Row location : query("SELECT directory_name, location FROM dba_external_locations WHERE owner = ? AND table_name = ? ORDER BY directory_name, location", owner, name)) {
            locations.add(new Location(location.get("DIRECTORY_NAME"), location.get("LOCATION")));
        }
        return new ExternalTable(row.get("TYPE_NAME"), row.get("DEFAULT_DIRECTORY_NAME"), trim(row.get("ACCESS_PARAMETERS")), row.get("REJECT_LIMIT"), locations);
    }

    /** Liest RANGE/LIST/HASH-Partitionen und deren explizite Subpartitionen in Dictionary-Reihenfolge. */
    private Partitioning readPartitioning(String owner, String name, Row definition, boolean index, Map<String, Integer> blockSizes) throws SQLException {
        String method = definition.get("PARTITIONING_TYPE"), subMethod = definition.get("SUBPARTITIONING_TYPE");
        if (method == null || !Set.of("RANGE", "LIST", "HASH").contains(method)
                || (subMethod != null && !Set.of("NONE", "RANGE", "LIST", "HASH").contains(subMethod))
                || definition.is("AUTOLIST", "YES") || definition.is("AUTOLIST_SUBPARTITION", "YES")
                || definition.get("INTERVAL_SUBPARTITION") != null) unsupported(owner, name, "Partitionierungsstrategie " + method + "/" + subMethod);
        List<String> keys = partitionKeys(owner, name, index, false);
        List<String> subKeys = "NONE".equals(subMethod) || subMethod == null ? List.of() : partitionKeys(owner, name, index, true);
        String view = index ? "dba_ind_partitions" : "dba_tab_partitions";
        String ownerColumn = index ? "index_owner" : "table_owner";
        String nameColumn = index ? "index_name" : "table_name";
        List<Partition> partitions = new ArrayList<>();
        for (Row partition : query("SELECT p.* FROM " + view + " p WHERE " + ownerColumn + " = ? AND " + nameColumn + " = ? ORDER BY partition_position", owner, name)) {
            List<Partition> children = new ArrayList<>();
            if (!subKeys.isEmpty()) {
                String childView = index ? "dba_ind_subpartitions" : "dba_tab_subpartitions";
                for (Row child : query("SELECT p.* FROM " + childView + " p WHERE " + ownerColumn + " = ? AND " + nameColumn + " = ? AND partition_name = ? ORDER BY subpartition_position", owner, name, partition.get("PARTITION_NAME"))) {
                    validatePartition(owner, name, child);
                    children.add(new Partition(child.get("SUBPARTITION_NAME"), trim(child.get("HIGH_VALUE")), partitionAttributes(child, false, blockSizes), List.of()));
                }
            }
            validatePartition(owner, name, partition);
            partitions.add(new Partition(partition.get("PARTITION_NAME"), trim(partition.get("HIGH_VALUE")),
                    partitionAttributes(partition, !index && partition.is("COMPOSITE", "YES"), blockSizes), children));
        }
        if (partitions.isEmpty()) throw new SQLException("Partitionierte Definition ohne sichtbare Partitionen: " + owner + "." + name);
        return new Partitioning(method, keys, subMethod, subKeys, trim(definition.get("INTERVAL")), definition.is("LOCALITY", "LOCAL"), partitions);
    }

    /** Liest Partitionierungsschlüssel, ohne Spaltennamen anhand generierter Namen zu erraten. */
    private List<String> partitionKeys(String owner, String name, boolean index, boolean sub) throws SQLException {
        List<String> result = new ArrayList<>();
        for (Row row : query("SELECT column_name FROM " + (sub ? "dba_subpart_key_columns" : "dba_part_key_columns")
                + " WHERE owner = ? AND name = ? AND object_type = ? ORDER BY column_position", owner, name, index ? "INDEX" : "TABLE")) result.add(row.get("COLUMN_NAME"));
        return result;
    }

    /** Vereinheitlicht singular benannte Partition-Storage-Werte und Oracle-Blockeinheiten. */
    private Map<String, String> partitionAttributes(Row row, boolean composite, Map<String, Integer> blockSizes) throws SQLException {
        Map<String, String> result = attributes(row, PHYSICAL);
        if (row.get("MIN_EXTENT") != null) result.put("MIN_EXTENTS", row.get("MIN_EXTENT"));
        if (row.get("MAX_EXTENT") != null) result.put("MAX_EXTENTS", row.get("MAX_EXTENT"));
        addAttributes(result, row, "PREFIX_LENGTH");
        if (composite) convertBlockExtents(result, blockSizes);
        return result;
    }

    /** Vererbt explizite Storage-Defaults und rechnet deren Oracle-Blockzahlen in Bytes um. */
    private void mergePartitionDefaults(Map<String, String> attributes, Row row, Map<String, Integer> blockSizes, boolean index) throws SQLException {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (String key : PHYSICAL) {
            String value = row.get("DEF_" + key);
            if (value != null && !Set.of("DEFAULT", "NONE").contains(value)) defaults.put(key, value);
        }
        convertBlockExtents(defaults, blockSizes);
        attributes.putAll(defaults);
        if (!index && (row.is("DEF_INMEMORY", "ENABLED") || row.is("DEF_READ_ONLY", "YES") || row.is("DEF_INDEXING", "OFF"))) {
            throw new SQLException("Nicht unterstützte Partitionsdefaults bei " + row.get("TABLE_NAME"));
        }
    }

    /** Multipliziert ausschließlich in Blöcken gespeicherte Extentgrößen mit der Tablespace-Blockgröße. */
    private void convertBlockExtents(Map<String, String> attributes, Map<String, Integer> blockSizes) throws SQLException {
        for (String key : List.of("INITIAL_EXTENT", "NEXT_EXTENT")) {
            String value = attributes.get(key);
            if (value == null || "DEFAULT".equals(value)) continue;
            Integer blockSize = blockSizes.get(attributes.get("TABLESPACE_NAME"));
            if (blockSize == null) throw new SQLException("Blockgröße des Tablespace nicht sichtbar: " + attributes.get("TABLESPACE_NAME"));
            try { attributes.put(key, new BigInteger(value).multiply(BigInteger.valueOf(blockSize)).toString()); }
            catch (NumberFormatException exception) { throw new SQLException("Ungültige Extentgröße: " + value, exception); }
        }
    }

    /** Verhindert unvollständige Definitionen für Strukturen außerhalb des unterstützten SQL-Modells. */
    private void validateTable(String owner, Row row, Set<String> archivalTables, Set<String> encrypted, Set<String> objects, Set<String> nestedParents) throws SQLException {
        String name = row.get("TABLE_NAME");
        if (row.get("CLUSTER_NAME") != null || row.get("IOT_TYPE") != null || row.is("SECONDARY", "Y")
                || row.is("INMEMORY", "ENABLED") || row.is("CLUSTERING", "YES") || archivalTables.contains(name)
                || encrypted.contains(name) || objects.contains(name) || nestedParents.contains(name)) unsupported(owner, name, "Cluster/IOT/Domain-Speicher/ROW ARCHIVAL/Encryption/Object/Nested/In-Memory/Clustering");
    }

    /** Domain-, Join- und spezielle Indexvarianten benötigen eine eigene Migration. */
    private void validateIndex(String owner, Row row) throws SQLException {
        if (row.get("INDEX_TYPE") == null || !Set.of("NORMAL", "NORMAL/REV", "BITMAP", "FUNCTION-BASED NORMAL", "FUNCTION-BASED NORMAL/REV", "FUNCTION-BASED BITMAP").contains(row.get("INDEX_TYPE"))
                || row.is("JOIN_INDEX", "YES") || !owner.equals(row.get("TABLE_OWNER"))
                || row.is("INDEXING", "PARTIAL")) unsupported(owner, row.get("INDEX_NAME"), "Domain-/Join-/schemafremder oder partieller Index");
    }

    /** Verhindert den Verlust besonderer Partitionsmerkmale, die das Modell nicht ausdrücken kann. */
    private void validatePartition(String owner, String name, Row row) throws SQLException {
        if (row.is("INMEMORY", "ENABLED") || row.is("INDEXING", "OFF") || row.is("READ_ONLY", "YES")) unsupported(owner, name, "In-Memory/INDEXING OFF/READ ONLY auf Partition");
    }

    /** Führt einen SELECT aus und liest alle Werte einschließlich LONG/CLOB in Spaltenreihenfolge vollständig. */
    private List<Row> query(String sql, String... values) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                while (result.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        row.put(metadata.getColumnLabel(column).toUpperCase(Locale.ROOT), result.getString(column));
                    }
                    rows.add(new Row(row));
                }
            }
        }
        return rows;
    }

    /** Indexiert bereits deterministisch sortierte Ergebniszeilen nach einem eindeutigen Namen. */
    private static Map<String, Row> byName(List<Row> rows, String column) {
        Map<String, Row> result = new LinkedHashMap<>();
        for (Row row : rows) result.put(row.get(column), row);
        return result;
    }

    /** Extrahiert eine Menge von Dictionary-Namen. */
    private static Set<String> names(List<Row> rows, String column) {
        Set<String> result = new LinkedHashSet<>();
        for (Row row : rows) result.add(row.get(column));
        return result;
    }

    /** Kopiert ausschließlich deklarative Attribute und keine Nutzungs-/Statistikwerte. */
    private static Map<String, String> attributes(Row row, List<String> keys) {
        Map<String, String> result = new LinkedHashMap<>();
        addAttributes(result, row, keys.toArray(String[]::new));
        return result;
    }

    /** Nullwerte werden ausgelassen; gepolsterte CHAR-Werte werden normalisiert. */
    private static void addAttributes(Map<String, String> result, Row row, String... keys) {
        for (String key : keys) if (row.get(key) != null) result.put(key, row.get(key).trim());
    }

    /** Meldet nicht unterstützte Metadaten mit eindeutiger Objektzuordnung. */
    private static void unsupported(String owner, String name, String detail) throws SQLException {
        throw new SQLException("Nicht unterstützte Definition bei " + owner + "." + name + ": " + detail + ". Individuelle Migration erforderlich.");
    }

    /** DEFAULT NULL und kein Default sind gleichwertig; Literale und virtuelle Ausdrücke bleiben erhalten. */
    private static String normalizeDefault(String expression, boolean virtual) {
        String result = trim(expression);
        return !virtual && "NULL".equalsIgnoreCase(result) ? null : result;
    }

    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    private static String key(String table, String column) { return table.length() + ":" + table + column; }

    /** Typsichere Konvertierung der aus JDBC bereits vollständig gelesenen Werte. */
    private record Row(Map<String, String> values) {
        String get(String name) { return values.get(name); }
        boolean is(String name, String value) { return value.equals(trim(get(name))); }
        Integer integer(String name) throws SQLException {
            String value = get(name);
            if (value == null) return null;
            try { return Integer.valueOf(value.trim()); }
            catch (NumberFormatException exception) { throw new SQLException("Ungültige Ganzzahl für " + name + ": " + value, exception); }
        }
    }
}
