package de.axa.oraclecompare;

import static de.axa.oraclecompare.SchemaDefinition.*;
import static de.axa.oraclecompare.ComparisonPlan.Action.*;
import static de.axa.oraclecompare.ComparisonPlan.ObjectType.*;

import de.axa.oraclecompare.ComparisonPlan.Operation;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Vergleicht zwei unveränderliche Fachmodelle und plant alle DDL-Phasen im Java-Prozess.
 * Erst ein vollständig geprüfter Plan wird an die Dateiausgabe übergeben. Insbesondere
 * werden keine Tabellen als Ersatz für eine nicht unterstützte ALTER-Operation neu angelegt.
 */
final class SchemaComparisonPlanner {
    /** Liefert ein vollständiges SQL*Plus-/SQLcl-Skript; die Modelle werden nicht verändert. */
    String plan(SchemaDefinition reference, SchemaDefinition target, ExclusionFilter exclusions) throws SQLException {
        return comparisonPlan(reference, target, exclusions).script();
    }

    /** Erstellt SQL und Berichtsdaten gemeinsam, einschließlich aller nötigen Hilfsoperationen. */
    ComparisonPlan comparisonPlan(SchemaDefinition reference, SchemaDefinition target, ExclusionFilter exclusions) throws SQLException {
        Scope scope = new Scope(normalizeGeneratedNames(reference, target, exclusions), target, exclusions);
        SchemaDefinition desired = scope.desired;
        SchemaDefinition actual = scope.actual;
        OracleSqlRenderer render = new OracleSqlRenderer(desired.owner(), actual.owner());
        OracleSqlRenderer oldRender = new OracleSqlRenderer(actual.owner(), actual.owner());
        Set<String> changedTables = difference(actual.tables().keySet(), desired.tables().keySet());
        List<Operation> tableStatements = new ArrayList<>();
        for (Table table : new TreeMap<>(desired.tables()).values()) {
            Table old = actual.tables().get(table.name());
            if (old == null) {
                changedTables.add(table.name());
                tableStatements.add(new Operation(CREATE, TABLE, table.name(), null, render.createTable(table)));
            } else if (!render.createTable(table).equals(oldRender.createTable(old))
                    || !constraintSignatures(table, false, render).equals(constraintSignatures(old, false, oldRender))) {
                for (Constraint constraint : table.constraints()) if (implicitNotNull(table, constraint)) {
                    Constraint existing = old.constraints().stream().filter(candidate -> Objects.equals(OracleSqlRenderer.notNullColumn(candidate), OracleSqlRenderer.notNullColumn(constraint))).findFirst().orElse(null);
                    if (existing != null && implicitNotNull(old, existing)
                            && !constraintSignature(constraint, render, false).equals(constraintSignature(existing, oldRender, false))) {
                        throw OracleSqlRenderer.unsupported(table.name(), "Änderung eines impliziten Identity-/DEFAULT-ON-NULL-Constraints");
                    }
                }
                changedTables.add(table.name());
                for (String sql : render.alterTable(old, table, oldRender)) {
                    tableStatements.add(new Operation(ALTER, TABLE, table.name(), null, sql));
                }
            }
        }
        // Eine Änderung am PK-/UK-Index benötigt zuerst das Lösen seines Constraints.
        Map<String, String> desiredBacking = backingIndexes(desired);
        Map<String, String> actualBacking = backingIndexes(actual);
        Set<String> changedIndexes = new TreeSet<>();
        Set<String> indexNames = new TreeSet<>(desired.indexes().keySet());
        indexNames.addAll(actual.indexes().keySet());
        for (String name : indexNames) {
            Index wanted = desired.indexes().get(name); Index old = actual.indexes().get(name);
            if (wanted == null || old == null || !render.index(wanted).equals(oldRender.index(old))) {
                changedIndexes.add(name);
                if (desiredBacking.containsKey(name)) changedTables.add(desiredBacking.get(name));
                if (actualBacking.containsKey(name)) changedTables.add(actualBacking.get(name));
            }
        }
        Map<String, Operation> viewStatements = new TreeMap<>();
        Set<String> changedViews = difference(actual.views().keySet(), desired.views().keySet());
        for (View view : new TreeMap<>(desired.views()).values()) {
            View old = actual.views().get(view.name());
            if (old == null || !render.view(view).equals(oldRender.view(old))) {
                viewStatements.put(view.name(), new Operation(old == null ? CREATE : ALTER, VIEW, view.name(), null, render.view(view)));
                changedViews.add(view.name());
            }
        }
        scope.validate(changedTables, changedViews, desiredBacking, actualBacking);
        validateRequiredDependencies(scope, render);
        boolean resetForeignKeys = !changedTables.isEmpty()
                || !foreignKeySignatures(desired, render).equals(foreignKeySignatures(actual, oldRender));
        List<Operation> commands = new ArrayList<>();
        if (resetForeignKeys) for (Table table : new TreeMap<>(actual.tables()).values()) {
            for (Constraint constraint : sortedConstraints(table)) if ("R".equals(constraint.type())) {
                commands.add(new Operation(DROP, CONSTRAINT, constraint.name(), table.name(), dropConstraint(actual.owner(), table.name(), constraint, false)));
            }
        }
        List<String> oldViews = viewOrder(actual);
        Collections.reverse(oldViews);
        for (String name : oldViews) if (!desired.views().containsKey(name)) commands.add(new Operation(DROP, VIEW, name, null, drop("VIEW", actual.owner(), name)));

        // Freie Indizes vor DROP COLUMN entfernen, damit ein späteres DROP nicht auf fehlende Objekte trifft.
        for (Index index : new TreeMap<>(actual.indexes()).values()) {
            if (!actualBacking.containsKey(index.name()) && (changedIndexes.contains(index.name()) || changedTables.contains(index.tableName()))) {
                commands.add(new Operation(DROP, INDEX, index.name(), index.tableName(), drop("INDEX", actual.owner(), index.name())));
            }
        }
        // Constraintnamen sind schemaweit eindeutig: Alle Namen freigeben, bevor neue Tabellen sie verwenden.
        for (Table table : new TreeMap<>(actual.tables()).values()) if (changedTables.contains(table.name()) && desired.tables().containsKey(table.name())) {
            for (Constraint constraint : sortedConstraints(table)) if (!"R".equals(constraint.type()) && !implicitNotNull(table, constraint)) {
                commands.add(new Operation(DROP, CONSTRAINT, constraint.name(), table.name(), dropConstraint(actual.owner(), table.name(), constraint, true)));
            }
        }
        for (Index index : new TreeMap<>(actual.indexes()).values()) {
            if (actualBacking.containsKey(index.name()) && changedTables.contains(index.tableName()) && desired.tables().containsKey(index.tableName())) commands.add(new Operation(DROP, INDEX, index.name(), index.tableName(), drop("INDEX", actual.owner(), index.name())));
        }
        for (String name : difference(actual.tables().keySet(), desired.tables().keySet())) commands.add(new Operation(DROP, TABLE, name, null, drop("TABLE", actual.owner(), name)));
        for (String name : difference(actual.sequences().keySet(), desired.sequences().keySet())) commands.add(new Operation(DROP, SEQUENCE, name, null, drop("SEQUENCE", actual.owner(), name)));
        for (Sequence sequence : new TreeMap<>(desired.sequences()).values()) {
            Sequence old = actual.sequences().get(sequence.name());
            if (old == null) commands.add(new Operation(CREATE, SEQUENCE, sequence.name(), null, render.sequence(sequence, true)));
            else if (!render.sequence(sequence, false).equals(oldRender.sequence(old, false))) commands.add(new Operation(ALTER, SEQUENCE, sequence.name(), null, render.sequence(sequence, false)));
        }
        commands.addAll(tableStatements);
        for (Index index : new TreeMap<>(desired.indexes()).values()) {
            if (changedIndexes.contains(index.name()) || changedTables.contains(index.tableName())) commands.add(new Operation(CREATE, INDEX, index.name(), index.tableName(), render.index(index)));
        }
        for (Table table : new TreeMap<>(desired.tables()).values()) if (changedTables.contains(table.name())) {
            for (Constraint constraint : sortedConstraints(table)) if (!"R".equals(constraint.type()) && !implicitNotNull(table, constraint)) commands.add(new Operation(CREATE, CONSTRAINT, constraint.name(), table.name(), render.constraint(table.name(), constraint)));
        }
        // Alle Tabellen und deren referenzierbare Schlüssel existieren jetzt, auch bei FK-Zyklen.
        if (resetForeignKeys) for (Table table : new TreeMap<>(desired.tables()).values()) {
            for (Constraint constraint : sortedConstraints(table)) if ("R".equals(constraint.type())) commands.add(new Operation(CREATE, CONSTRAINT, constraint.name(), table.name(), render.constraint(table.name(), constraint)));
        }
        for (String name : viewOrder(desired)) if (viewStatements.containsKey(name)) commands.add(viewStatements.get(name));
        if (!commands.isEmpty()) {
            for (String name : viewOrder(desired)) commands.add(new Operation(COMPILE, VIEW, name, null, "ALTER VIEW " + SqlText.qualified(actual.owner(), name) + " COMPILE;"));
            if (!desired.views().isEmpty()) commands.add(new Operation(VALIDATE, SCHEMA, actual.owner(), null, validateViews(actual.owner(), desired.views().keySet())));
        }
        return new ComparisonPlan(desired.owner(), actual.owner(),
                script(desired.owner(), actual.owner(), commands.stream().map(Operation::sql).toList()), commands);
    }

    /** Generierte Constraint-/Indexnamen sind Datenbankdetails, keine fachlichen Unterschiede. */
    private SchemaDefinition normalizeGeneratedNames(SchemaDefinition reference, SchemaDefinition actual, ExclusionFilter exclusions) throws SQLException {
        OracleSqlRenderer desiredRenderer = new OracleSqlRenderer(reference.owner(), actual.owner());
        OracleSqlRenderer actualRenderer = new OracleSqlRenderer(actual.owner(), actual.owner());
        Map<String, String> renamedIndexes = new HashMap<>();
        Set<String> reservedConstraints = new HashSet<>(actual.constraintTables().keySet());
        Set<String> reservedIndexes = new HashSet<>(actual.indexes().keySet());
        Set<String> explicitConstraints = new HashSet<>();
        for (Table table : reference.tables().values()) for (Constraint constraint : table.constraints()) {
            if (!constraint.generated()) { reservedConstraints.add(constraint.name()); explicitConstraints.add(constraint.name()); }
        }
        for (Index index : reference.indexes().values()) if (!"Y".equals(index.attributes().get("GENERATED"))) reservedIndexes.add(index.name());
        Map<String, Table> tables = new LinkedHashMap<>();
        for (Table table : reference.tables().values()) {
            Table old = actual.tables().get(table.name());
            if (exclusions.excludes(table.name()) || reference.excludedTables().contains(table.name()) || actual.excludedTables().contains(table.name())) {
                tables.put(table.name(), table); continue;
            }
            Set<String> matched = new HashSet<>();
            List<Constraint> constraints = new ArrayList<>();
            for (Constraint constraint : table.constraints()) {
                Constraint match = !constraint.generated() && old != null ? old.constraints().stream()
                        .filter(candidate -> candidate.name().equals(constraint.name())).findFirst().orElse(null) : null;
                if (constraint.generated() && old != null) {
                    for (Constraint candidate : old.constraints()) {
                        if (matched.contains(candidate.name()) || explicitConstraints.contains(candidate.name())) continue;
                        if (constraintSignature(constraint, desiredRenderer, true).equals(constraintSignature(candidate, actualRenderer, true))) {
                            match = candidate; matched.add(candidate.name()); break;
                        }
                    }
                }
                String name = constraint.name();
                if (constraint.generated()) name = match == null
                        ? uniqueName("ORACMP_C", table.name() + ":" + constraintSignature(constraint, desiredRenderer, true), reservedConstraints)
                        : match.name();
                String indexName = constraint.indexName();
                Index index = indexName == null ? null : reference.indexes().get(indexName);
                if (index != null && "Y".equals(index.attributes().get("GENERATED")) && !exclusions.excludes(indexName)
                        && (match == null || match.indexName() == null || !exclusions.excludes(match.indexName()))) {
                    String selected = match != null && match.indexName() != null ? match.indexName()
                            : renamedIndexes.computeIfAbsent(indexName, ignored -> uniqueName("ORACMP_I", table.name() + ":" + constraint.name(), reservedIndexes));
                    renamedIndexes.put(indexName, selected); indexName = selected;
                }
                constraints.add(copyConstraint(constraint, name, indexName));
            }
            tables.put(table.name(), new Table(table.name(), table.columns(), constraints, table.attributes(), table.partitioning(), table.external(), table.lobs()));
        }
        Map<String, Index> indexes = new LinkedHashMap<>();
        for (Index index : reference.indexes().values()) {
            String name = renamedIndexes.getOrDefault(index.name(), index.name());
            if (indexes.containsKey(name)) throw OracleSqlRenderer.unsupported(name, "Kollision generierter Indexnamen");
            indexes.put(name, new Index(name, index.tableOwner(), index.tableName(), index.unique(), index.columns(), index.attributes(), index.partitioning()));
        }
        Map<String, String> constraintTables = new HashMap<>(reference.constraintTables());
        for (Table table : reference.tables().values()) table.constraints().forEach(constraint -> constraintTables.remove(constraint.name()));
        for (Table table : tables.values()) table.constraints().forEach(constraint -> constraintTables.put(constraint.name(), table.name()));
        return new SchemaDefinition(reference.owner(), tables, indexes, reference.views(), reference.sequences(), reference.viewDependencies(),
                reference.incomingForeignKeys(), reference.excludedTables(), constraintTables);
    }

    /** Stabile kurze Namen kollidieren weder mit bestehenden noch mit expliziten gewünschten Namen. */
    private static String uniqueName(String prefix, String signature, Set<String> reserved) {
        String hash = java.util.UUID.nameUUIDFromBytes(signature.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 20).toUpperCase(java.util.Locale.ROOT);
        String base = prefix + hash; String name = base; int suffix = 1;
        while (!reserved.add(name)) name = base + "_" + suffix++;
        return name;
    }

    private static Constraint copyConstraint(Constraint original, String name, String indexName) {
        return new Constraint(name, original.type(), original.columns(), original.expression(), original.referencedOwner(), original.referencedTable(),
                original.referencedColumns(), original.deleteRule(), original.deferrable(), original.initiallyDeferred(), original.enabled(),
                original.validated(), original.rely(), original.generated(), original.indexOwner(), indexName);
    }

    private static List<String> constraintSignatures(Table table, boolean foreignKeys, OracleSqlRenderer renderer) throws SQLException {
        List<String> signatures = new ArrayList<>();
        for (Constraint constraint : table.constraints()) if ("R".equals(constraint.type()) == foreignKeys) {
            signatures.add(constraintSignature(constraint, renderer, false));
        }
        Collections.sort(signatures);
        return signatures;
    }

    private static String constraintSignature(Constraint constraint, OracleSqlRenderer renderer, boolean omitIndex) throws SQLException {
        Constraint normalized = copyConstraint(constraint, omitIndex ? "__MATCH_CONSTRAINT__" : constraint.name(), omitIndex ? null : constraint.indexName());
        return renderer.constraint("__TABLE__", normalized);
    }

    /** Identity und DEFAULT ON NULL erzeugen ihr NOT NULL selbst; ein zweites MODIFY wäre ungültig. */
    private static boolean implicitNotNull(Table table, Constraint constraint) {
        String name = OracleSqlRenderer.notNullColumn(constraint);
        if (name == null) return false;
        return table.columns().stream().anyMatch(column -> column.name().equals(name) && (column.identity() != null || column.defaultOnNull()));
    }

    private static Map<String, List<String>> foreignKeySignatures(SchemaDefinition schema, OracleSqlRenderer renderer) throws SQLException {
        Map<String, List<String>> result = new TreeMap<>();
        for (Table table : schema.tables().values()) {
            List<String> keys = constraintSignatures(table, true, renderer);
            if (!keys.isEmpty()) result.put(table.name(), keys);
        }
        return result;
    }

    private static Map<String, String> backingIndexes(SchemaDefinition schema) {
        Map<String, String> result = new HashMap<>();
        for (Table table : schema.tables().values()) for (Constraint constraint : table.constraints()) {
            if (Set.of("P", "U").contains(constraint.type()) && constraint.indexName() != null) result.put(constraint.indexName(), table.name());
        }
        return result;
    }

    private static String dropConstraint(String schema, String table, Constraint constraint, boolean keepIndex) {
        return "ALTER TABLE " + SqlText.qualified(schema, table) + " DROP CONSTRAINT " + SqlText.identifier(constraint.name())
                + (keepIndex && Set.of("P", "U").contains(constraint.type()) ? " KEEP INDEX" : "") + ";";
    }

    private static List<Constraint> sortedConstraints(Table table) {
        // Explizite NOT-NULL-Constraints zuerst, damit PK-Erzeugung keine zusätzliche Namenswahl erzwingt.
        return table.constraints().stream().sorted(Comparator.comparing((Constraint constraint) -> OracleSqlRenderer.notNullColumn(constraint) == null)
                .thenComparing(Constraint::name)).toList();
    }

    /** Prüft ausgeschlossene Referenzziele und verhindert Skripte, deren Grundlagen fehlen. */
    private static void validateRequiredDependencies(Scope scope, OracleSqlRenderer render) throws SQLException {
        for (Table table : scope.desired.tables().values()) for (Constraint constraint : table.constraints()) {
            if ("R".equals(constraint.type()) && scope.reference.owner().equals(constraint.referencedOwner())
                    && scope.excludedTables.contains(constraint.referencedTable()) && !scope.target.tables().containsKey(constraint.referencedTable())) {
                throw new SQLException("Fremdschlüssel " + constraint.name() + " benötigt ausgeschlossene Tabelle " + constraint.referencedTable() + ", die im Ziel fehlt.");
            }
        }
        for (String view : scope.desired.views().keySet()) for (String dependency : scope.reference.viewDependencies().getOrDefault(view, Set.of())) {
            if ((scope.exclusions.excludes(dependency) || scope.excludedTables.contains(dependency))
                    && !scope.target.tables().containsKey(dependency) && !scope.target.views().containsKey(dependency)
                    && !scope.target.sequences().containsKey(dependency)) {
                throw new SQLException("View " + view + " benötigt ausgeschlossenes Objekt " + dependency + ", das im Ziel fehlt.");
            }
        }
    }

    private static List<String> viewOrder(SchemaDefinition schema) throws SQLException {
        List<String> order = new ArrayList<>(); Set<String> visited = new HashSet<>(); Set<String> visiting = new HashSet<>();
        for (String name : new TreeSet<>(schema.views().keySet())) visitView(name, schema, visited, visiting, order);
        return order;
    }
    private static void visitView(String name, SchemaDefinition schema, Set<String> visited, Set<String> visiting, List<String> order) throws SQLException {
        if (visited.contains(name)) return;
        if (!visiting.add(name)) throw new SQLException("Zyklische View-Abhängigkeit bei " + name);
        for (String dependency : new TreeSet<>(schema.viewDependencies().getOrDefault(name, Set.of()))) {
            if (schema.views().containsKey(dependency)) visitView(dependency, schema, visited, visiting, order);
        }
        visiting.remove(name); visited.add(name); order.add(name);
    }

    private static String drop(String type, String schema, String name) { return "DROP " + type + " " + SqlText.qualified(schema, name) + ";"; }
    private static Set<String> difference(Set<String> actual, Set<String> desired) {
        Set<String> result = new TreeSet<>(actual); result.removeAll(desired); return result;
    }
    private static String script(String reference, String target, List<String> commands) {
        StringBuilder script = new StringBuilder("""
                -- Oracle-Schemaabgleich (Oracle 19c; SQL*Plus / SQLcl)
                -- Referenz: %s | Ziel: %s
                -- Enthält DROP/ALTER; Oracle-DDL führt implizite Commits aus.
                -- Tabelleninhalte werden nicht kopiert. Vor Ausführung fachlich prüfen.
                SET DEFINE OFF
                SET SQLBLANKLINES ON
                SET SQLTERMINATOR ON
                WHENEVER OSERROR EXIT FAILURE ROLLBACK
                WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
                ALTER SESSION SET CURRENT_SCHEMA = %s;

                """.formatted(SqlText.identifier(reference), SqlText.identifier(target), SqlText.identifier(target)));
        for (String command : commands) script.append(command.strip()).append("\n\n");
        return script.append(commands.isEmpty() ? "PROMPT Keine Unterschiede gefunden.\n" : "PROMPT Schemaabgleich abgeschlossen.\n").toString();
    }
    private static String validateViews(String target, Set<String> selected) {
        List<String> names = new TreeSet<>(selected).stream().map(OracleSqlRenderer::literal).toList();
        List<String> groups = new ArrayList<>();
        for (int start = 0; start < names.size(); start += 1000) groups.add("object_name IN (" + String.join(", ", names.subList(start, Math.min(start + 1000, names.size()))) + ")");
        return "DECLARE\n  invalid_count PLS_INTEGER;\nBEGIN\n  SELECT COUNT(*) INTO invalid_count FROM all_objects\n"
                + "    WHERE owner = " + OracleSqlRenderer.literal(target) + " AND object_type = 'VIEW' AND status <> 'VALID'\n"
                + "      AND (" + String.join(" OR ", groups) + ");\n  IF invalid_count > 0 THEN\n"
                + "    RAISE_APPLICATION_ERROR(-20002, 'Schemaabgleich: Views sind ungueltig; ALL_ERRORS pruefen.');\n  END IF;\nEND;\n/";
    }

    /** Auswahl und Schutzinformationen werden getrennt gehalten; ausgeschlossene Objekte bleiben lesbar. */
    private static final class Scope {
        final SchemaDefinition reference, target, desired, actual;
        final ExclusionFilter exclusions;
        final Set<String> excludedTables = new HashSet<>();
        final Set<String> excludedIndexes = new HashSet<>();
        final Set<String> excludedKeys = new HashSet<>();

        Scope(SchemaDefinition reference, SchemaDefinition target, ExclusionFilter exclusions) {
            this.reference = reference; this.target = target; this.exclusions = exclusions;
            for (SchemaDefinition schema : List.of(reference, target)) {
                excludedTables.addAll(schema.excludedTables());
                for (String table : schema.tables().keySet()) if (exclusions.excludes(table)) excludedTables.add(table);
            }
            for (SchemaDefinition schema : List.of(reference, target)) {
                for (Index index : schema.indexes().values()) {
                    if (exclusions.excludes(index.name()) || excludedTables.contains(index.tableName())) excludedIndexes.add(index.name());
                }
                for (Table table : schema.tables().values()) if (excludedTables.contains(table.name())) {
                    for (Constraint constraint : table.constraints()) if ("R".equals(constraint.type())) excludedKeys.add(constraint.name());
                }
            }
            desired = select(reference); actual = select(target);
        }

        private SchemaDefinition select(SchemaDefinition schema) {
            Map<String, Table> tables = new TreeMap<>(); Map<String, Index> indexes = new TreeMap<>();
            Map<String, View> views = new TreeMap<>(); Map<String, Sequence> sequences = new TreeMap<>();
            schema.tables().forEach((name, table) -> {
                if (!excludedTables.contains(name)) tables.put(name, new Table(name, table.columns(),
                        table.constraints().stream().filter(constraint -> !"R".equals(constraint.type()) || !excludedKeys.contains(constraint.name())).toList(),
                        table.attributes(), table.partitioning(), table.external(), table.lobs()));
            });
            schema.indexes().forEach((name, index) -> { if (!excludedIndexes.contains(name)) indexes.put(name, index); });
            schema.views().forEach((name, view) -> { if (!exclusions.excludes(name)) views.put(name, view); });
            schema.sequences().forEach((name, sequence) -> { if (!exclusions.excludes(name)) sequences.put(name, sequence); });
            return new SchemaDefinition(schema.owner(), tables, indexes, views, sequences, schema.viewDependencies(), schema.incomingForeignKeys(), schema.excludedTables(), schema.constraintTables());
        }

        void validate(Set<String> changedTables, Set<String> changedViews, Map<String, String> desiredBacking, Map<String, String> actualBacking) throws SQLException {
            for (SchemaDefinition schema : List.of(reference, target)) for (Index index : schema.indexes().values()) {
                if (!excludedIndexes.contains(index.name())) continue;
                if (changedTables.contains(index.tableName()) || schema == target && !changedTables.isEmpty() && "YES".equals(index.attributes().get("JOIN_INDEX"))) {
                    throw new SQLException("Ausgeschlossener Index " + index.name() + " schützt Tabelle " + index.tableName() + " vor der geplanten Änderung.");
                }
            }
            for (ForeignKeyReference key : target.incomingForeignKeys()) {
                if (changedTables.contains(key.referencedTable()) && target.owner().equals(key.referencedOwner())
                        && (!target.owner().equals(key.owner()) || !actual.tables().containsKey(key.table()))) {
                    throw new SQLException("Nicht verwalteter Fremdschlüssel " + key.owner() + "." + key.name() + " schützt Tabelle " + key.referencedTable() + ".");
                }
            }
            for (Table table : target.tables().values()) for (Constraint constraint : table.constraints()) {
                if ("R".equals(constraint.type()) && (excludedTables.contains(table.name()) || excludedKeys.contains(constraint.name()))
                        && (changedTables.contains(table.name()) || target.owner().equals(constraint.referencedOwner()) && changedTables.contains(constraint.referencedTable()))) {
                    throw new SQLException("Ausgeschlossener Fremdschlüssel " + constraint.name() + " schützt Tabelle " + constraint.referencedTable() + ".");
                }
            }
            Set<String> changedObjects = new HashSet<>(changedTables); changedObjects.addAll(changedViews);
            for (String view : target.views().keySet()) if (exclusions.excludes(view)) {
                Set<String> visited = new HashSet<>(); ArrayDeque<String> pending = new ArrayDeque<>(); pending.add(view);
                while (!pending.isEmpty()) {
                    String object = pending.removeFirst();
                    if (!visited.add(object)) continue;
                    if (changedObjects.contains(object)) throw new SQLException("Ausgeschlossene View " + view + " hängt von geändertem Objekt " + object + " ab.");
                    pending.addAll(target.viewDependencies().getOrDefault(object, Set.of()));
                }
            }
            for (Table table : desired.tables().values()) for (Constraint constraint : table.constraints()) {
                String occupied = target.constraintTables().get(constraint.name());
                if (occupied != null && (!actual.tables().containsKey(occupied) || excludedKeys.contains(constraint.name()))) {
                    throw new SQLException("Constraintname " + constraint.name() + " ist durch ausgeschlossenes Objekt " + occupied + " belegt.");
                }
            }
        }
    }
}
