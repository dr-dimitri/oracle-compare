package de.axa.oraclecompare;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unveränderliches Fachmodell eines Oracle-Schemas. Es enthält ausschließlich aus dem
 * Dictionary gelesene Werte; Vergleich und SQL-Erzeugung benötigen keine Datenbankpakete.
 * Namen behalten ihre exakte Dictionary-Schreibweise. SQL-Ausdrücke bleiben unverändert.
 */
record SchemaDefinition(String owner, Map<String, Table> tables, Map<String, Index> indexes,
                        Map<String, View> views, Map<String, Sequence> sequences,
                        Map<String, Set<String>> viewDependencies,
                        List<ForeignKeyReference> incomingForeignKeys, Set<String> excludedTables,
                        Map<String, String> constraintTables) {
    SchemaDefinition {
        tables = immutable(tables); indexes = immutable(indexes); views = immutable(views);
        sequences = immutable(sequences);
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        viewDependencies.forEach((name, values) -> dependencies.put(name, Set.copyOf(values)));
        viewDependencies = immutable(dependencies);
        incomingForeignKeys = List.copyOf(incomingForeignKeys);
        excludedTables = Set.copyOf(excludedTables);
        constraintTables = immutable(constraintTables);
    }

    /** Komfortkonstruktor für reine Fachmodell-Aufrufer ohne zusätzliche, ausgeblendete Constraint-Namen. */
    SchemaDefinition(String owner, Map<String, Table> tables, Map<String, Index> indexes, Map<String, View> views,
                     Map<String, Sequence> sequences, Map<String, Set<String>> viewDependencies,
                     List<ForeignKeyReference> incomingForeignKeys, Set<String> excludedTables) {
        this(owner, tables, indexes, views, sequences, viewDependencies, incomingForeignKeys, excludedTables, constraintNames(tables));
    }

    /** Ermittelt die Namen der direkt im Modell enthaltenen Tabellenconstraints. */
    private static Map<String, String> constraintNames(Map<String, Table> tables) {
        Map<String, String> result = new LinkedHashMap<>();
        tables.values().forEach(table -> table.constraints().forEach(constraint -> result.put(constraint.name(), table.name())));
        return result;
    }

    /** Tabelle einschließlich lokaler Constraints; Fremdschlüssel werden später separat angelegt. */
    record Table(String name, List<Column> columns, List<Constraint> constraints,
                 Map<String, String> attributes, Partitioning partitioning, ExternalTable external, List<Lob> lobs) {
        Table { columns = List.copyOf(columns); constraints = List.copyOf(constraints); attributes = immutable(attributes); lobs = List.copyOf(lobs); }
        Table(String name, List<Column> columns, List<Constraint> constraints, Map<String, String> attributes,
              Partitioning partitioning, ExternalTable external) {
            this(name, columns, constraints, attributes, partitioning, external, List.of());
        }
    }

    /** LOB-Speicherung; automatisch erzeugte Segmentnamen werden nicht als Definitionsmerkmal verwendet. */
    record Lob(String column, String segmentName, Map<String, String> attributes) {
        Lob { attributes = immutable(attributes); }
    }

    /**
     * Spalte mit Oracle-Datentypbestandteilen. Bei virtuellen Spalten enthält defaultExpression
     * den Berechnungsausdruck. Bei Identity-Spalten wird deren interner Default nicht verglichen.
     */
    record Column(String name, String dataType, String dataTypeOwner, Integer length,
                  Integer precision, Integer scale, String charUsed, Integer charLength,
                  String defaultExpression, boolean nullable, boolean virtual, boolean invisible,
                  boolean defaultOnNull, String collation, Identity identity) { }

    /** Identity-Optionen stammen aus DBA_TAB_IDENTITY_COLS; der Sequenzname ist kein Definitionsmerkmal. */
    record Identity(String generationType, String options) { }

    /** Constraint mit geordneten Schlüsselspalten und vollständig aufgelöster FK-Referenz. */
    record Constraint(String name, String type, List<String> columns, String expression,
                      String referencedOwner, String referencedTable, List<String> referencedColumns,
                      String deleteRule, boolean deferrable, boolean initiallyDeferred,
                      boolean enabled, boolean validated, boolean rely, boolean generated,
                      String indexOwner, String indexName) {
        Constraint { columns = List.copyOf(columns); referencedColumns = List.copyOf(referencedColumns); }
    }

    /** Indexdefinition; auch von PK-/UK-Constraints verwendete Indizes sind explizit enthalten. */
    record Index(String name, String tableOwner, String tableName, boolean unique,
                 List<IndexColumn> columns, Map<String, String> attributes, Partitioning partitioning) {
        Index { columns = List.copyOf(columns); attributes = immutable(attributes); }
    }

    /** Bereits gequoteter Spaltenname oder vollständiger Funktionsausdruck mit Sortierrichtung. */
    record IndexColumn(String expression, boolean descending) { }

    /** Normale relationale View einschließlich expliziter Spaltennamen und vollständigem LONG-Text. */
    record View(String name, List<String> columns, String text) {
        View { columns = List.copyOf(columns); }
    }

    /** Sequenzparameter; lastNumber dient nur der Neuanlage und gehört nicht zum Definitionsvergleich. */
    record Sequence(String name, String minValue, String maxValue, String incrementBy,
                    String cacheSize, boolean cycle, boolean ordered, String lastNumber,
                    Map<String, String> attributes) {
        Sequence { attributes = immutable(attributes); }
    }

    /** Partitionierungsstrategie und Partitionen in ihrer Dictionary-Position; local gilt für Indizes. */
    record Partitioning(String method, List<String> keys, String subMethod, List<String> subKeys,
                        String interval, boolean local, List<Partition> partitions) {
        Partitioning { keys = List.copyOf(keys); subKeys = List.copyOf(subKeys); partitions = List.copyOf(partitions); }
    }

    /** Partition oder Subpartition; highValue ist ein Oracle-SQL-Ausdruck einschließlich MAXVALUE/DEFAULT. */
    record Partition(String name, String highValue, Map<String, String> attributes, List<Partition> children) {
        Partition { attributes = immutable(attributes); children = List.copyOf(children); }
    }

    /** Externe Tabelle mit Zugriffstreiber, unverändertem Parametertext und allen Dateiquellen. */
    record ExternalTable(String type, String defaultDirectory, String accessParameters,
                         String rejectLimit, List<Location> locations) {
        ExternalTable { locations = List.copyOf(locations); }
    }

    /** Externe Datei; directory ist der Directory-Objektname, kein Betriebssystempfad. */
    record Location(String directory, String name) { }

    /** Eingehender FK aus beliebigem Schema als Schutzinformation vor Tabellenänderungen. */
    record ForeignKeyReference(String owner, String table, String name, String referencedOwner,
                               String referencedTable) { }

    /** Kopiert Maps deterministisch und lässt keine nachträgliche Änderung zu. */
    private static <K, V> Map<K, V> immutable(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
