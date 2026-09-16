package de.axa.oraclecompare;

import static de.axa.oraclecompare.SchemaDefinition.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Erzeugt Oracle-19c-DDL ausschließlich aus dem eigenen Dictionary-Modell. Jede Methode
 * liefert vollständige Statements; Fremdschlüssel und andere Constraints bleiben bewusst
 * von CREATE TABLE getrennt. Unbekannte Ausprägungen werden mit Objektkontext abgelehnt.
 */
final class OracleSqlRenderer {
    private final String source;
    private final String target;
    private final boolean targetSupportsCollation;

    OracleSqlRenderer(String source, String target) { this(source, target, true); }

    /** Die Zielplattform bestimmt, ob explizite Collation-Klauseln zulässig sind. */
    OracleSqlRenderer(String source, String target, boolean targetSupportsCollation) {
        this.source = source;
        this.target = target;
        this.targetSupportsCollation = targetSupportsCollation;
    }

    String qualified(String name) { return SqlText.qualified(target, name); }
    String expression(String value) {
        if (value == null) return null;
        String sql = SqlText.remap(value, source, target).strip();
        // Ein letzter Zeilenkommentar darf nachfolgende Klammern oder Terminatoren nicht verschlucken.
        return sql.lastIndexOf("--") > sql.lastIndexOf('\n') ? sql + "\n" : sql;
    }

    /** Erstellt Tabellen ohne PK/UK/FK; nur das für Identity/DEFAULT ON NULL erforderliche NOT NULL steht inline. */
    String createTable(Table table) throws SQLException {
        validateNotNullDefinitions(table);
        boolean temporary = "Y".equals(table.attributes().get("TEMPORARY"));
        StringBuilder sql = new StringBuilder("CREATE ");
        if (temporary) sql.append("GLOBAL TEMPORARY ");
        sql.append("TABLE ").append(qualified(table.name())).append(" (\n  ");
        List<String> columns = new ArrayList<>();
        for (Column column : table.columns()) columns.add(column(table, column));
        if (columns.isEmpty()) throw unsupported(table.name(), "Tabelle ohne auswertbare Spalten");
        sql.append(String.join(",\n  ", columns)).append("\n)");
        sql.append(collation(table.attributes().get("DEFAULT_COLLATION"), " DEFAULT COLLATION "));
        if (table.external() != null) {
            if (temporary) throw unsupported(table.name(), "temporäre externe Tabelle");
            sql.append("\n").append(external(table.external()));
        } else if (temporary) {
            sql.append(" ON COMMIT ").append("SYS$SESSION".equals(table.attributes().get("DURATION"))
                    ? "PRESERVE ROWS" : "DELETE ROWS");
            append(sql, tablespace(table.attributes()));
        } else {
            Map<String, String> segmentAttributes = new TreeMap<>(table.attributes());
            segmentAttributes.remove("DEGREE");
            append(sql, physical(segmentAttributes, false));
        }
        for (Lob lob : table.lobs()) append(sql, lob(lob));
        if (!temporary && table.external() == null && table.attributes().containsKey("READ_ONLY")) {
            sql.append("YES".equals(table.attributes().get("READ_ONLY")) ? " READ ONLY" : " READ WRITE");
        }
        if (table.partitioning() != null) append(sql, partitioning(table.partitioning(), false));
        if (!temporary && table.external() == null) {
            Map<String, String> behavior = new TreeMap<>(table.attributes()); behavior.remove("READ_ONLY");
            append(sql, tableBehavior(behavior));
        }
        return sql.append(';').toString();
    }

    /** Spaltendefinition ohne Constraints, die als eigene Phase geplant werden. */
    String column(Column column) throws SQLException {
        StringBuilder sql = new StringBuilder(SqlText.identifier(column.name()));
        if (column.virtual()) {
            if (column.defaultExpression() == null) throw unsupported(column.name(), "virtuelle Spalte ohne Ausdruck");
            sql.append(' ').append(dataType(column));
            sql.append(collation(column.collation(), " COLLATE "));
            if (column.invisible()) sql.append(" INVISIBLE");
            sql.append(" GENERATED ALWAYS AS (").append(expression(column.defaultExpression())).append(") VIRTUAL");
        } else {
            sql.append(' ').append(dataType(column));
            sql.append(collation(column.collation(), " COLLATE "));
            if (column.invisible()) sql.append(" INVISIBLE");
            if (column.identity() != null) sql.append(' ').append(identity(column.identity()));
            else if (defaultExpression(column) != null) {
                sql.append(" DEFAULT ");
                if (column.defaultOnNull()) sql.append("ON NULL ");
                sql.append(expression(defaultExpression(column)));
            }
        }
        return sql.toString();
    }

    /**
     * Bei STANDARD ist USING_NLS_COMP implizit und darf nicht explizit deklariert werden.
     * Andere Collations lassen sich dort nicht erhalten und führen vor der Ausgabe zum Fehler.
     */
    private String collation(String value, String clause) throws SQLException {
        if (value == null) return "";
        if (!targetSupportsCollation) {
            if ("USING_NLS_COMP".equals(value)) return "";
            throw unsupported(target, "Collation " + value + " benötigt MAX_STRING_SIZE=EXTENDED und COMPATIBLE>=12.2 im Ziel");
        }
        return clause + SqlText.identifier(value);
    }

    /** DEFAULT NULL ist nach Oracle-DDL derselbe Zustand wie ein fehlender Default. */
    private static String defaultExpression(Column column) {
        if (column.defaultExpression() == null || "NULL".equalsIgnoreCase(column.defaultExpression().strip())) return column.defaultOnNull() ? "NULL" : null;
        return column.defaultExpression();
    }

    /** Implizites NOT NULL wird schon bei IDENTITY/DEFAULT ON NULL mit dem gewünschten Namen angelegt. */
    private String column(Table table, Column column) throws SQLException {
        String result = column(column);
        if (column.identity() != null || column.defaultOnNull()) {
            for (Constraint constraint : table.constraints()) if (column.name().equals(notNullColumn(constraint))) {
                result += " CONSTRAINT " + SqlText.identifier(constraint.name()) + " NOT NULL" + constraintState(constraint);
            }
        }
        return result;
    }

    /** Oracle-Datentyp aus den einzelnen Dictionary-Spalten, einschließlich CHAR-Semantik. */
    String dataType(Column column) throws SQLException {
        if (column.dataTypeOwner() != null) {
            return SqlText.qualified(remapOwner(column.dataTypeOwner()), column.dataType());
        }
        String type = column.dataType();
        if (type == null) throw unsupported(column.name(), "fehlender Datentyp");
        return switch (type) {
            case "CHAR", "VARCHAR2" -> type + "(" + ("C".equals(column.charUsed()) ? required(column.charLength(), column.name())
                    + " CHAR" : required(column.length(), column.name()) + " BYTE") + ")";
            case "NCHAR", "NVARCHAR2" -> type + "(" + required(column.charLength(), column.name()) + ")";
            case "RAW" -> "RAW(" + required(column.length(), column.name()) + ")";
            case "NUMBER" -> column.precision() == null ? (column.scale() == null ? "NUMBER" : "NUMBER(*," + column.scale() + ")")
                    : "NUMBER(" + column.precision() + (column.scale() == null ? "" : "," + column.scale()) + ")";
            case "FLOAT" -> column.precision() == null ? "FLOAT" : "FLOAT(" + column.precision() + ")";
            case "UROWID" -> column.length() == null ? "UROWID" : "UROWID(" + column.length() + ")";
            default -> {
                if (!Set.of("DATE", "BINARY_FLOAT", "BINARY_DOUBLE", "ROWID", "LONG", "LONG RAW", "BLOB", "CLOB", "NCLOB", "BFILE").contains(type)
                        && !type.matches("TIMESTAMP(?:\\([0-9]+\\))?(?: WITH (?:LOCAL )?TIME ZONE)?")
                        && !type.matches("INTERVAL YEAR(?:\\([0-9]+\\))? TO MONTH")
                        && !type.matches("INTERVAL DAY(?:\\([0-9]+\\))? TO SECOND(?:\\([0-9]+\\))?")) {
                    throw unsupported(column.name(), "Datentyp " + type);
                }
                yield type;
            }
        };
    }

    /** Identity-Optionen werden als Werte gelesen und anschließend mit eigener Grammatik gerendert. */
    private String identity(Identity identity) throws SQLException {
        if (!Set.of("ALWAYS", "BY DEFAULT", "BY DEFAULT ON NULL").contains(identity.generationType())) {
            throw unsupported("Identity", identity.generationType());
        }
        List<String> options = new ArrayList<>();
        if (identity.options() != null) for (String entry : identity.options().split(",")) {
            String[] pair = entry.strip().split(":", 2);
            if (pair.length != 2) throw unsupported("Identity", "Option " + entry);
            String key = pair[0].strip(); String value = pair[1].strip();
            switch (key) {
                case "START WITH", "INCREMENT BY", "MIN_VALUE", "MAX_VALUE", "CACHE_SIZE" -> {
                    String token = switch (key) { case "MIN_VALUE" -> "MINVALUE"; case "MAX_VALUE" -> "MAXVALUE";
                        case "CACHE_SIZE" -> "CACHE"; default -> key; };
                    options.add("CACHE".equals(token) && "0".equals(value) ? "NOCACHE" : token + " " + number(value));
                }
                case "CYCLE_FLAG" -> options.add(flag(value, "CYCLE", "NOCYCLE"));
                case "ORDER_FLAG" -> options.add(flag(value, "ORDER", "NOORDER"));
                default -> throw unsupported("Identity", "Option " + key);
            }
        }
        return "GENERATED " + identity.generationType() + " AS IDENTITY" + (options.isEmpty() ? "" : " (" + String.join(" ", options) + ")");
    }

    /** Erstellt bzw. ersetzt eine relationale View mit expliziter Spaltenliste. */
    String view(View view) throws SQLException {
        if (view.text() == null || view.text().isBlank()) throw unsupported(view.name(), "fehlender View-Text");
        return "CREATE OR REPLACE VIEW " + qualified(view.name())
                + (view.columns().isEmpty() ? "" : " (" + identifiers(view.columns()) + ")")
                + " AS\n" + expression(view.text()) + ";";
    }

    String sequence(Sequence sequence, boolean create) throws SQLException {
        StringBuilder sql = new StringBuilder(create ? "CREATE SEQUENCE " : "ALTER SEQUENCE ");
        sql.append(qualified(sequence.name()));
        if (create) sql.append(" START WITH ").append(number(sequence.lastNumber()));
        sql.append(" INCREMENT BY ").append(number(sequence.incrementBy()))
                .append(" MINVALUE ").append(number(sequence.minValue())).append(" MAXVALUE ").append(number(sequence.maxValue()))
                .append(" ").append(sequence.cycle() ? "CYCLE" : "NOCYCLE")
                .append(" ").append(sequence.ordered() ? "ORDER" : "NOORDER")
                .append(" ").append("0".equals(sequence.cacheSize()) ? "NOCACHE" : "CACHE " + number(sequence.cacheSize()));
        // Spezialsequenzen dürfen nicht stillschweigend in gewöhnliche Sequenzen überführt werden.
        for (var entry : sequence.attributes().entrySet()) {
            if (entry.getValue() != null && !Set.of("N", "NO", "GLOBAL", "NONE").contains(entry.getValue())) {
                throw unsupported(sequence.name(), "Sequenzattribut " + entry.getKey() + "=" + entry.getValue());
            }
        }
        return sql.append(';').toString();
    }

    /** Indexe werden einschließlich funktionaler Ausdrücke und Partitionierung eigenständig angelegt. */
    String index(Index index) throws SQLException {
        validateHashIndexCompression(index);
        String type = index.attributes().getOrDefault("INDEX_TYPE", "NORMAL");
        boolean bitmap = type.contains("BITMAP");
        if (!Set.of("NORMAL", "NORMAL/REV", "BITMAP", "FUNCTION-BASED NORMAL", "FUNCTION-BASED NORMAL/REV", "FUNCTION-BASED BITMAP").contains(type)) {
            throw unsupported(index.name(), "Indextyp " + type);
        }
        List<String> columns = index.columns().stream().map(column -> expression(column.expression())
                + (column.descending() ? " DESC" : " ASC")).toList();
        if (columns.isEmpty()) throw unsupported(index.name(), "Index ohne Spalten");
        StringBuilder sql = new StringBuilder("CREATE ");
        if (bitmap) sql.append("BITMAP "); else if (index.unique()) sql.append("UNIQUE ");
        sql.append("INDEX ").append(qualified(index.name())).append(" ON ")
                .append(SqlText.qualified(remapOwner(index.tableOwner()), index.tableName()))
                .append(" (").append(String.join(", ", columns)).append(')');
        append(sql, physical(index.attributes(), true));
        if (index.partitioning() != null) append(sql, partitioning(index.partitioning(), true));
        if (type.endsWith("/REV")) sql.append(" REVERSE");
        if ("INVISIBLE".equals(index.attributes().get("VISIBILITY"))) sql.append(" INVISIBLE");
        return sql.append(';').toString();
    }

    /** CREATE erlaubt bei globalen HASH-Partitionen keine vom Index abweichende Kompression. */
    private static void validateHashIndexCompression(Index index) throws SQLException {
        Partitioning partitioning = index.partitioning();
        if (partitioning == null || partitioning.local() || !"HASH".equals(partitioning.method())) return;
        String compression = index.attributes().getOrDefault("COMPRESSION", "DISABLED");
        for (Partition partition : partitioning.partitions()) {
            String individual = partition.attributes().get("COMPRESSION");
            String prefix = partition.attributes().get("PREFIX_LENGTH");
            if (individual != null && !individual.equals(compression)
                    || "ENABLED".equals(individual) && prefix != null && !prefix.equals(index.attributes().get("PREFIX_LENGTH"))) {
                throw unsupported(index.name() + "." + partition.name(),
                        "abweichende Kompression einer globalen HASH-Indexpartition benötigt einen eigenen Migrationsplan");
            }
        }
    }

    /** Constraint-Zustände und referenzierte Spalten werden vollständig übernommen. */
    String constraint(String table, Constraint constraint) throws SQLException {
        String prefix = "ALTER TABLE " + qualified(table);
        String name = "CONSTRAINT " + SqlText.identifier(constraint.name()) + " ";
        String definition;
        String notNullColumn = notNullColumn(constraint);
        if (notNullColumn != null) {
            return prefix + " MODIFY (" + SqlText.identifier(notNullColumn) + " " + name + "NOT NULL"
                    + constraintState(constraint) + ");";
        }
        definition = switch (constraint.type()) {
            case "P" -> "PRIMARY KEY (" + identifiers(constraint.columns()) + ")";
            case "U" -> "UNIQUE (" + identifiers(constraint.columns()) + ")";
            case "C" -> "CHECK (" + expression(constraint.expression()) + ")";
            case "R" -> "FOREIGN KEY (" + identifiers(constraint.columns()) + ") REFERENCES "
                    + SqlText.qualified(remapOwner(constraint.referencedOwner()), constraint.referencedTable())
                    + " (" + identifiers(constraint.referencedColumns()) + ")" + deleteRule(constraint.deleteRule());
            default -> throw unsupported(constraint.name(), "Constrainttyp " + constraint.type());
        };
        String using = "";
        if (Set.of("P", "U").contains(constraint.type()) && constraint.indexName() != null && constraint.enabled()) {
            using = " USING INDEX " + SqlText.qualified(remapOwner(constraint.indexOwner()), constraint.indexName());
        }
        // USING INDEX gehört innerhalb des Constraint-Zustands vor ENABLE/DISABLE.
        String deferrable = constraint.deferrable() ? " DEFERRABLE INITIALLY " + (constraint.initiallyDeferred() ? "DEFERRED" : "IMMEDIATE") : " NOT DEFERRABLE";
        return prefix + " ADD " + name + definition + deferrable + (constraint.rely() ? " RELY" : " NORELY")
                + using + enabledState(constraint) + ";";
    }

    static String notNullColumn(Constraint constraint) {
        if (!"C".equals(constraint.type()) || constraint.columns().size() != 1 || constraint.expression() == null
                || constraint.deferrable() || constraint.rely()) return null;
        String column = constraint.columns().get(0);
        return constraint.expression().strip().equals(SqlText.identifier(column) + " IS NOT NULL") ? column : null;
    }

    /**
     * Das Dictionary unterscheidet ein natives NOT NULL nicht eindeutig von einem gleichlautenden
     * CHECK. Bei mehreren solchen Constraints darf der Generator nicht mehrfach NOT NULL setzen.
     */
    private static void validateNotNullDefinitions(Table table) throws SQLException {
        Set<String> seen = new java.util.HashSet<>();
        for (Constraint constraint : table.constraints()) {
            String column = notNullColumn(constraint);
            if (column != null && !seen.add(column)) {
                throw unsupported(table.name() + "." + column,
                        "mehrdeutige NOT-NULL-/CHECK-Constraints; das Dictionary unterscheidet ihre ursprüngliche Syntax nicht eindeutig");
            }
        }
    }

    private static String constraintState(Constraint constraint) {
        return (constraint.deferrable() ? " DEFERRABLE INITIALLY " + (constraint.initiallyDeferred() ? "DEFERRED" : "IMMEDIATE") : " NOT DEFERRABLE")
                + (constraint.rely() ? " RELY" : " NORELY") + enabledState(constraint);
    }
    private static String enabledState(Constraint constraint) {
        return (constraint.enabled() ? " ENABLE" : " DISABLE") + (constraint.validated() ? " VALIDATE" : " NOVALIDATE");
    }
    private static String deleteRule(String rule) throws SQLException {
        if (rule == null || "NO ACTION".equals(rule)) return "";
        if (Set.of("CASCADE", "SET NULL").contains(rule)) return " ON DELETE " + rule;
        throw unsupported("Fremdschlüssel", "Löschregel " + rule);
    }

    /** Physische Attribute werden zentral für Tabellen, Indizes und Partitionen formatiert. */
    String physical(Map<String, String> attributes, boolean index) throws SQLException {
        List<String> clauses = new ArrayList<>();
        add(clauses, tablespace(attributes));
        addNumber(clauses, attributes, "PCT_FREE", "PCTFREE");
        if (!index) addNumber(clauses, attributes, "PCT_USED", "PCTUSED");
        addNumber(clauses, attributes, "INI_TRANS", "INITRANS");
        addNumber(clauses, attributes, "MAX_TRANS", "MAXTRANS");
        List<String> storage = new ArrayList<>();
        for (String[] attribute : new String[][] {{"INITIAL_EXTENT", "INITIAL"}, {"NEXT_EXTENT", "NEXT"}, {"MIN_EXTENTS", "MINEXTENTS"},
                {"MAX_EXTENTS", "MAXEXTENTS"}, {"PCT_INCREASE", "PCTINCREASE"}, {"FREELISTS", "FREELISTS"}, {"FREELIST_GROUPS", "FREELIST GROUPS"}}) {
            addNumber(storage, attributes, attribute[0], attribute[1]);
        }
        for (String key : List.of("BUFFER_POOL", "FLASH_CACHE", "CELL_FLASH_CACHE")) {
            String value = attributes.get(key);
            if (value != null) {
                if (!Set.of("DEFAULT", "KEEP", "RECYCLE", "NONE").contains(value)) throw unsupported(key, value);
                storage.add(key + " " + value);
            }
        }
        if (!storage.isEmpty()) clauses.add("STORAGE (" + String.join(" ", storage) + ")");
        String logging = attributes.get("LOGGING");
        if (logging != null && !"NONE".equals(logging)) clauses.add(flag(logging, "LOGGING", "NOLOGGING"));
        String compression = attributes.get("COMPRESSION");
        if (index) {
            if (compression != null) switch (compression) {
                case "DISABLED" -> clauses.add("NOCOMPRESS");
                case "ENABLED" -> clauses.add("COMPRESS" + (attributes.get("PREFIX_LENGTH") == null ? "" : " " + number(attributes.get("PREFIX_LENGTH"))));
                case "ADVANCED LOW", "ADVANCED HIGH" -> clauses.add("COMPRESS " + compression);
                default -> throw unsupported("Indexkompression", compression);
            }
        } else if ("ENABLED".equals(compression)) {
            String mode = attributes.get("COMPRESS_FOR");
            if (mode == null || "BASIC".equals(mode)) clauses.add("ROW STORE COMPRESS BASIC");
            else if ("ADVANCED".equals(mode) || "OLTP".equals(mode)) clauses.add("ROW STORE COMPRESS ADVANCED");
            else if (Set.of("QUERY LOW", "QUERY HIGH", "ARCHIVE LOW", "ARCHIVE HIGH").contains(mode)) clauses.add("COLUMN STORE COMPRESS FOR " + mode);
            else throw unsupported("Tabellenkompression", mode);
        } else if ("DISABLED".equals(compression)) clauses.add("NOCOMPRESS");
        String degree = attributes.get("DEGREE");
        if (degree != null) clauses.add("1".equals(degree) ? "NOPARALLEL" : "DEFAULT".equals(degree) ? "PARALLEL" : "PARALLEL " + number(degree));
        return String.join(" ", clauses);
    }

    private static String tableBehavior(Map<String, String> attributes) {
        List<String> clauses = new ArrayList<>();
        if (attributes.containsKey("CACHE")) clauses.add("Y".equals(attributes.get("CACHE")) ? "CACHE" : "NOCACHE");
        if (attributes.containsKey("DEGREE")) clauses.add("1".equals(attributes.get("DEGREE")) ? "NOPARALLEL" : "DEFAULT".equals(attributes.get("DEGREE")) ? "PARALLEL" : "PARALLEL " + attributes.get("DEGREE"));
        if (attributes.containsKey("DEPENDENCIES")) clauses.add("ENABLED".equals(attributes.get("DEPENDENCIES")) ? "ROWDEPENDENCIES" : "NOROWDEPENDENCIES");
        if (attributes.containsKey("ROW_MOVEMENT")) clauses.add("ENABLED".equals(attributes.get("ROW_MOVEMENT")) ? "ENABLE ROW MOVEMENT" : "DISABLE ROW MOVEMENT");
        if (attributes.containsKey("READ_ONLY")) clauses.add("YES".equals(attributes.get("READ_ONLY")) ? "READ ONLY" : "READ WRITE");
        return String.join(" ", clauses);
    }

    /** Partitionen behalten ihre Dictionary-Reihenfolge, insbesondere bei RANGE-Grenzen. */
    String partitioning(Partitioning partitioning, boolean index) throws SQLException {
        String method = partitioning.method();
        if (!Set.of("RANGE", "LIST", "HASH").contains(method)) throw unsupported("Partitionierung", method);
        StringBuilder sql = new StringBuilder();
        if (index && partitioning.local()) sql.append("LOCAL");
        else {
            if (index) sql.append("GLOBAL ");
            if (index && "LIST".equals(method)) throw unsupported("Index", "globale LIST-Partitionierung");
            sql.append("PARTITION BY ").append(method).append(" (").append(identifiers(partitioning.keys())).append(')');
            if (partitioning.interval() != null) {
                if (index) throw unsupported("Index", "Intervallpartitionierung");
                sql.append(" INTERVAL (").append(expression(partitioning.interval())).append(')');
            }
            if (partitioning.subMethod() != null && !"NONE".equals(partitioning.subMethod())) {
                if (index) throw unsupported("Index", "globale zusammengesetzte Partitionierung");
                if (!Set.of("RANGE", "LIST", "HASH").contains(partitioning.subMethod())) throw unsupported("Subpartition", partitioning.subMethod());
                sql.append(" SUBPARTITION BY ").append(partitioning.subMethod()).append(" (").append(identifiers(partitioning.subKeys())).append(')');
            }
        }
        List<String> parts = new ArrayList<>();
        for (Partition partition : partitioning.partitions()) {
            parts.add(partition(partition, method, partitioning.subMethod(), index, partitioning.local(), false));
        }
        if (parts.isEmpty()) throw unsupported("Partitionierung", "keine Partitionen");
        sql.append(" (\n  ").append(String.join(",\n  ", parts)).append("\n)");
        return sql.toString();
    }

    private String partition(Partition partition, String method, String subMethod, boolean index, boolean local, boolean sub) throws SQLException {
        StringBuilder sql = new StringBuilder(sub ? "SUBPARTITION " : "PARTITION ");
        sql.append(SqlText.identifier(partition.name()));
        if (!index || !local) {
            if ("RANGE".equals(method)) sql.append(" VALUES LESS THAN (").append(expression(partition.highValue())).append(')');
            else if ("LIST".equals(method)) sql.append(" VALUES (").append(expression(partition.highValue())).append(')');
        }
        // Oracle erlaubt bei HASH-Partitionen und LIST/HASH-Subpartitionen nur Tablespace/Kompression.
        if ("HASH".equals(method) || sub) {
            Map<String, String> limited = new TreeMap<>();
            // Globale HASH-Indexpartitionen erlauben ausschließlich TABLESPACE; Kompression
            // wird am Index selbst deklariert. Tabellenpartitionen haben eine andere Grammatik.
            List<String> allowed = index && !local && "HASH".equals(method)
                    ? List.of("TABLESPACE_NAME") : List.of("TABLESPACE_NAME", "COMPRESSION", "COMPRESS_FOR");
            for (String key : allowed) {
                if (partition.attributes().get(key) != null) limited.put(key, partition.attributes().get(key));
            }
            append(sql, physical(limited, index));
        } else append(sql, physical(partition.attributes(), index));
        if (!partition.children().isEmpty()) {
            List<String> children = new ArrayList<>();
            for (Partition child : partition.children()) children.add(partition(child, subMethod, null, index, local, true));
            sql.append(" (\n    ").append(String.join(",\n    ", children)).append("\n  )");
        }
        return sql.toString();
    }

    /** Rendert BasicFile-/SecureFile-LOBs ohne deren automatisch erzeugte internen Indexnamen. */
    String lob(Lob lob) throws SQLException {
        Map<String, String> attributes = lob.attributes();
        boolean secure = "YES".equals(attributes.get("SECUREFILE"));
        StringBuilder sql = new StringBuilder("LOB (").append(SqlText.identifier(lob.column())).append(") STORE AS ")
                .append(secure ? "SECUREFILE" : "BASICFILE");
        if (lob.segmentName() != null) sql.append(' ').append(SqlText.identifier(lob.segmentName()));
        List<String> clauses = new ArrayList<>();
        Map<String, String> physical = new TreeMap<>();
        for (String key : List.of("TABLESPACE_NAME", "INITIAL_EXTENT", "NEXT_EXTENT", "MIN_EXTENTS", "MAX_EXTENTS",
                "PCT_INCREASE", "FREELISTS", "FREELIST_GROUPS", "BUFFER_POOL", "FLASH_CACHE", "CELL_FLASH_CACHE", "LOGGING")) {
            if (attributes.get(key) != null) physical.put(key, attributes.get(key));
        }
        add(clauses, physical(physical, false));
        if (attributes.get("IN_ROW") != null) clauses.add(flag(attributes.get("IN_ROW"), "ENABLE STORAGE IN ROW", "DISABLE STORAGE IN ROW"));
        String cache = attributes.get("CACHE");
        if (cache != null) {
            if ("CACHEREADS".equals(cache)) clauses.add("CACHE READS");
            else clauses.add(flag(cache, "CACHE", "NOCACHE"));
        }
        if (!secure) {
            addNumber(clauses, attributes, "CHUNK", "CHUNK");
            addNumber(clauses, attributes, "FREEPOOLS", "FREEPOOLS");
            if ("YES".equals(attributes.get("RETENTION_TYPE"))) clauses.add("RETENTION");
            else addNumber(clauses, attributes, "PCTVERSION", "PCTVERSION");
        } else {
            String compression = attributes.get("COMPRESSION");
            if (compression != null) {
                if ("NO".equals(compression)) clauses.add("NOCOMPRESS");
                else if (Set.of("LOW", "MEDIUM", "HIGH").contains(compression)) clauses.add("COMPRESS " + compression);
                else throw unsupported(lob.column(), "LOB-Kompression " + compression);
            }
            String deduplication = attributes.get("DEDUPLICATION");
            if (deduplication != null) {
                if ("LOB".equals(deduplication)) clauses.add("DEDUPLICATE");
                else if ("NO".equals(deduplication)) clauses.add("KEEP_DUPLICATES");
                else throw unsupported(lob.column(), "LOB-Deduplikation " + deduplication);
            }
            String retention = attributes.get("RETENTION_TYPE");
            if (retention != null && !"DEFAULT".equals(retention)) {
                if (Set.of("AUTO", "NONE").contains(retention)) clauses.add("RETENTION " + retention);
                else if ("MIN".equals(retention)) clauses.add("RETENTION MIN " + number(attributes.get("RETENTION_VALUE")));
                else throw unsupported(lob.column(), "LOB-Retention " + retention);
            }
        }
        return sql.append(" (").append(String.join(" ", clauses)).append(')').toString();
    }

    private String external(ExternalTable external) throws SQLException {
        if (!Set.of("ORACLE_LOADER", "ORACLE_DATAPUMP").contains(external.type())) throw unsupported("externe Tabelle", external.type());
        StringBuilder sql = new StringBuilder("ORGANIZATION EXTERNAL (TYPE ").append(external.type())
                .append(" DEFAULT DIRECTORY ").append(SqlText.identifier(external.defaultDirectory()));
        if (external.accessParameters() != null && !external.accessParameters().isBlank()) {
            sql.append(" ACCESS PARAMETERS (\n").append(external.accessParameters().strip()).append("\n)");
        }
        List<String> locations = external.locations().stream().map(location ->
                (location.directory() == null || location.directory().equals(external.defaultDirectory()) ? "" : SqlText.identifier(location.directory()) + ":")
                        + literal(location.name())).toList();
        if (!locations.isEmpty()) sql.append(" LOCATION (").append(String.join(", ", locations)).append(')');
        sql.append(')');
        if (external.rejectLimit() != null) sql.append(" REJECT LIMIT ").append("UNLIMITED".equals(external.rejectLimit()) ? "UNLIMITED" : number(external.rejectLimit()));
        return sql.toString();
    }

    /** Bestehende Tabellen werden ohne impliziten Neuaufbau per ADD/MODIFY/DROP geändert. */
    List<String> alterTable(Table actual, Table desired, OracleSqlRenderer actualRenderer) throws SQLException {
        validateNotNullDefinitions(actual);
        validateNotNullDefinitions(desired);
        List<String> sql = new ArrayList<>();
        if (!Objects.equals(partitionSignature(actual.partitioning(), actualRenderer), partitionSignature(desired.partitioning(), this))) {
            throw unsupported(desired.name(), "Änderung bestehender Partitionierungsdefinitionen benötigt einen eigenen Migrationsplan");
        }
        if (!Objects.equals(actual.lobs(), desired.lobs())) {
            throw unsupported(desired.name(), "Änderung bestehender LOB-Speicherung benötigt einen eigenen Migrationsplan");
        }
        sql.addAll(alterExternal(actual, desired));
        sql.addAll(alterAttributes(actual, desired));
        Map<String, Column> oldColumns = actual.columns().stream().collect(Collectors.toMap(Column::name, column -> column));
        Map<String, Column> newColumns = desired.columns().stream().collect(Collectors.toMap(Column::name, column -> column));
        List<String> retained = actual.columns().stream().map(Column::name).filter(newColumns::containsKey).toList();
        List<String> order = desired.columns().stream().map(Column::name).filter(oldColumns::containsKey).toList();
        if (!retained.equals(order)) throw unsupported(desired.name(), "Änderung der Spaltenreihenfolge");
        boolean seenNew = false;
        for (Column column : desired.columns()) {
            if (!oldColumns.containsKey(column.name())) seenNew = true;
            else if (seenNew) throw unsupported(desired.name(), "neue Spalte innerhalb der bestehenden Spaltenreihenfolge");
        }
        List<Column> remaining = new ArrayList<>(actual.columns());
        // Virtuelle Spalten zuerst lösen, bevor ihre physischen Grundlagen verändert werden.
        for (Column column : actual.columns()) if (column.virtual() && !newColumns.containsKey(column.name())) {
            dropColumn(desired.name(), column, remaining, sql);
        }
        // Bei einem vollständigen Wechsel bleibt genau eine physische Spalte bis nach ADD
        // bestehen. Sonst früh löschen, damit LONG- und Spaltenanzahlgrenzen gewahrt bleiben.
        List<Column> removedPhysical = actual.columns().stream()
                .filter(column -> !column.virtual() && !newColumns.containsKey(column.name())).toList();
        boolean retainsPhysicalColumn = actual.columns().stream()
                .anyMatch(column -> !column.virtual() && newColumns.containsKey(column.name()));
        boolean retainsVisibleColumn = actual.columns().stream()
                .anyMatch(column -> !column.invisible() && newColumns.containsKey(column.name()));
        Column heldColumn = (!retainsPhysicalColumn || !retainsVisibleColumn) && !removedPhysical.isEmpty()
                ? removedPhysical.stream().min(Comparator.comparing((Column column) -> !retainsVisibleColumn && column.invisible())
                    .thenComparing(OracleSqlRenderer::isLong)).orElseThrow() : null;
        for (Column column : removedPhysical) if (column != heldColumn) {
            dropColumn(desired.name(), column, remaining, sql);
        }
        List<String> added = new ArrayList<>();
        for (Column column : desired.columns()) {
            Column old = oldColumns.get(column.name());
            if (old == null) {
                added.add(column(desired, column));
                continue;
            }
            if (column(column).equals(actualRenderer.column(old))) continue;
            if (column.virtual() || old.virtual() || !Objects.equals(column.identity(), old.identity())
                    || column.invisible() != old.invisible() || !Objects.equals(column.collation(), old.collation())
                    || old.defaultOnNull() && !column.defaultOnNull()) {
                throw unsupported(desired.name() + "." + column.name(), "Änderung virtueller/Identity-/unsichtbarer/ON-NULL-/Collation-Eigenschaften");
            }
            String definition = column(column);
            if (!old.defaultOnNull() && column.defaultOnNull()) definition = column(desired, column);
            if (column.defaultExpression() == null && old.defaultExpression() != null) definition += " DEFAULT NULL";
            sql.add("ALTER TABLE " + qualified(desired.name()) + " MODIFY (" + definition + ");");
        }
        // Zusammen hinzufügen, damit neue virtuelle Spalten dieselben neuen Basisspalten sehen.
        // Auch beim kompletten Spaltenwechsel darf die Tabelle nie ohne Spalten sein.
        if (heldColumn != null && (desired.columns().size() >= 1000
                || isLong(heldColumn) && desired.columns().stream().anyMatch(OracleSqlRenderer::isLong))) {
            throw unsupported(desired.name(), "vollständiger Spaltenwechsel mit LONG- oder 1000-Spalten-Grenze benötigt einen eigenen Migrationsplan");
        }
        if (!added.isEmpty()) {
            sql.add("ALTER TABLE " + qualified(desired.name()) + " ADD (" + String.join(", ", added) + ");");
            desired.columns().stream().filter(column -> !oldColumns.containsKey(column.name())).forEach(remaining::add);
        }
        if (heldColumn != null) dropColumn(desired.name(), heldColumn, remaining, sql);
        boolean wasReadOnly = "YES".equals(actual.attributes().get("READ_ONLY"));
        boolean wantsReadOnly = "YES".equals(desired.attributes().get("READ_ONLY"));
        boolean dropsColumns = actual.columns().stream().anyMatch(column -> !newColumns.containsKey(column.name()));
        boolean unlock = wasReadOnly && (!wantsReadOnly || dropsColumns);
        if (unlock) sql.add(0, "ALTER TABLE " + qualified(desired.name()) + " READ WRITE;");
        if (wantsReadOnly && (!wasReadOnly || unlock)) sql.add("ALTER TABLE " + qualified(desired.name()) + " READ ONLY;");
        return sql;
    }

    private static boolean isLong(Column column) {
        return "LONG".equals(column.dataType()) || "LONG RAW".equals(column.dataType());
    }

    /** Jede Zwischenstruktur muss mindestens eine physische und eine sichtbare Spalte besitzen. */
    private void dropColumn(String table, Column column, List<Column> remaining, List<String> sql) throws SQLException {
        remaining.remove(column);
        if (remaining.stream().noneMatch(candidate -> !candidate.virtual())
                || remaining.stream().noneMatch(candidate -> !candidate.invisible())) {
            throw unsupported(table, "Spaltenwechsel würde vorübergehend alle physischen oder sichtbaren Spalten entfernen; eigener Migrationsplan erforderlich");
        }
        sql.add("ALTER TABLE " + qualified(table) + " DROP COLUMN " + SqlText.identifier(column.name()) + ";");
    }

    private static String partitionSignature(Partitioning partition, OracleSqlRenderer renderer) throws SQLException {
        return partition == null ? null : renderer.partitioning(partition, false);
    }

    /** Unterstützte physische Änderungen behalten Tabelleninhalte; komplexe Segmentumbauten werden abgelehnt. */
    private List<String> alterAttributes(Table actual, Table desired) throws SQLException {
        if (actual.attributes().equals(desired.attributes())) return List.of();
        Set<String> supported = Set.of("TABLESPACE_NAME", "PCT_FREE", "PCT_USED", "INI_TRANS", "MAX_TRANS", "NEXT_EXTENT", "MAX_EXTENTS", "PCT_INCREASE",
                "BUFFER_POOL", "FLASH_CACHE", "CELL_FLASH_CACHE", "LOGGING", "COMPRESSION", "COMPRESS_FOR", "DEGREE", "CACHE", "ROW_MOVEMENT", "READ_ONLY");
        Map<String, String> changed = new TreeMap<>();
        Set<String> keys = new java.util.HashSet<>(actual.attributes().keySet()); keys.addAll(desired.attributes().keySet());
        for (String key : keys) if (!Objects.equals(actual.attributes().get(key), desired.attributes().get(key))) {
            if ("COMPRESS_FOR".equals(key) && desired.attributes().get(key) == null && "DISABLED".equals(desired.attributes().get("COMPRESSION"))) continue;
            if (!supported.contains(key) || desired.attributes().get(key) == null || desired.external() != null || "Y".equals(desired.attributes().get("TEMPORARY"))) {
                throw unsupported(desired.name(), "Änderung des Tabellenattributs " + key + " benötigt einen eigenen Migrationsplan");
            }
            changed.put(key, desired.attributes().get(key));
        }
        List<String> sql = new ArrayList<>(); String prefix = "ALTER TABLE " + qualified(desired.name()) + " ";
        if (changed.containsKey("TABLESPACE_NAME")) {
            if (desired.partitioning() != null) throw unsupported(desired.name(), "Tablespace-Wechsel einer partitionierten Tabelle");
            sql.add(prefix + "MOVE TABLESPACE " + SqlText.identifier(changed.remove("TABLESPACE_NAME")) + ";");
        }
        if (changed.containsKey("COMPRESS_FOR")) changed.put("COMPRESSION", desired.attributes().get("COMPRESSION"));
        // Schreibschutz wird von alterTable vor bzw. nach den Strukturänderungen gesetzt.
        changed.remove("READ_ONLY");
        Map<String, String> behavior = new TreeMap<>();
        for (String key : List.of("DEGREE", "CACHE", "ROW_MOVEMENT")) if (changed.containsKey(key)) behavior.put(key, changed.remove(key));
        String physical = physical(changed, false);
        if (!physical.isBlank()) sql.add(prefix + physical + ";");
        for (var entry : behavior.entrySet()) sql.add(prefix + tableBehavior(Map.of(entry.getKey(), entry.getValue())) + ";");
        return sql;
    }

    /** Externe Dateiquellen lassen sich ohne Tabellenersatz über die Oracle-ALTER-Klauseln ändern. */
    private List<String> alterExternal(Table actual, Table desired) throws SQLException {
        ExternalTable old = actual.external(), wanted = desired.external();
        if (Objects.equals(old, wanted)) return List.of();
        if (old == null || wanted == null || !old.type().equals(wanted.type())) throw unsupported(desired.name(), "Wechsel des externen Zugriffstreibers/der Tabellenorganisation");
        List<String> sql = new ArrayList<>(); String prefix = "ALTER TABLE " + qualified(desired.name()) + " ";
        if (!Objects.equals(old.defaultDirectory(), wanted.defaultDirectory())) sql.add(prefix + "DEFAULT DIRECTORY " + SqlText.identifier(wanted.defaultDirectory()) + ";");
        if (!Objects.equals(old.accessParameters(), wanted.accessParameters())) {
            sql.add(prefix + "ACCESS PARAMETERS (\n" + (wanted.accessParameters() == null ? "" : wanted.accessParameters().strip()) + "\n);");
        }
        if (!old.locations().equals(wanted.locations())) {
            String locations = wanted.locations().stream().map(location -> (location.directory() == null ? "" : SqlText.identifier(location.directory()) + ":")
                    + literal(location.name())).collect(Collectors.joining(", "));
            sql.add(prefix + "LOCATION (" + locations + ");");
        }
        if (!Objects.equals(old.rejectLimit(), wanted.rejectLimit())) sql.add(prefix + "REJECT LIMIT "
                + ("UNLIMITED".equals(wanted.rejectLimit()) ? "UNLIMITED" : number(wanted.rejectLimit())) + ";");
        return sql;
    }

    String remapOwner(String owner) { return owner == null || source.equals(owner) ? target : owner; }
    static String identifiers(List<String> values) { return values.stream().map(SqlText::identifier).collect(Collectors.joining(", ")); }
    static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    static SQLException unsupported(String object, String reason) { return new SQLException("Nicht unterstützter Abgleich für " + object + ": " + reason); }
    private static Integer required(Integer value, String object) throws SQLException {
        if (value == null) throw unsupported(object, "fehlende Datentyplänge");
        return value;
    }
    private static String number(String value) throws SQLException {
        if (value == null || !value.matches("-?[0-9]+")) throw unsupported("numerischer Dictionary-Wert", String.valueOf(value));
        return value;
    }
    private static String flag(String value, String yes, String no) throws SQLException {
        if (Set.of("Y", "YES", "ENABLED").contains(value)) return yes;
        if (Set.of("N", "NO", "DISABLED").contains(value)) return no;
        throw unsupported("Dictionary-Schalter", value);
    }
    private static String tablespace(Map<String, String> attributes) {
        return attributes.get("TABLESPACE_NAME") == null ? "" : "TABLESPACE " + SqlText.identifier(attributes.get("TABLESPACE_NAME"));
    }
    private static void addNumber(List<String> clauses, Map<String, String> attributes, String key, String token) throws SQLException {
        if (attributes.get(key) != null) clauses.add(token + " " + number(attributes.get(key)));
    }
    private static void add(List<String> clauses, String clause) { if (!clause.isBlank()) clauses.add(clause); }
    private static void append(StringBuilder sql, String clause) { if (!clause.isBlank()) sql.append(' ').append(clause); }
}
