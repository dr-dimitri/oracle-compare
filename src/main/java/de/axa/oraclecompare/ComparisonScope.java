package de.axa.oraclecompare;

import static de.axa.oraclecompare.SchemaModel.*;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Trennt ausgewählte Objekte von geschützten Abhängigkeiten. Beide vollständigen Inventare
 * bleiben für Schutzprüfungen erhalten; nur die Auswahl gelangt in den DDL-Vergleich.
 */
final class ComparisonScope {
    private final Snapshot originalDesired;
    private final Snapshot originalActual;
    private final ExclusionFilter exclusions;
    private final Set<String> excludedIndexes = new HashSet<>();
    private final Set<String> excludedKeys = new HashSet<>();
    private final Snapshot desired;
    private final Snapshot actual;

    ComparisonScope(Snapshot desired, Snapshot actual, ExclusionFilter exclusions) {
        this.originalDesired = desired;
        this.originalActual = actual;
        this.exclusions = exclusions;
        collectDependencies(desired);
        collectDependencies(actual);
        this.desired = select(desired);
        this.actual = select(actual);
    }

    Snapshot desired() { return desired; }
    Snapshot actual() { return actual; }

    /** Ein geschützter Name bleibt auch beim Wechsel seiner Besitzertabelle ausgeschlossen. */
    private void collectDependencies(Snapshot snapshot) {
        for (DbObject index : snapshot.objects(Type.INDEX).values()) {
            if (exclusions.excludes(index.name()) || exclusions.excludes(index.tableName())) excludedIndexes.add(index.name());
        }
        snapshot.constraintIndexes().forEach((index, table) -> {
            if (exclusions.excludes(index) || exclusions.excludes(table)) excludedIndexes.add(index);
        });
        for (ForeignKey key : snapshot.foreignKeys()) {
            if (exclusions.excludes(key.tableName())) excludedKeys.add(key.name());
        }
    }

    /** Tabelleneigene Indizes und Fremdschlüssel folgen dem Tabellenausschluss. */
    private Snapshot select(Snapshot snapshot) {
        Map<Type, Map<String, DbObject>> objects = new EnumMap<>(Type.class);
        for (Type type : Type.values()) {
            Map<String, DbObject> selected = new TreeMap<>();
            for (DbObject object : snapshot.objects(type).values()) {
                if (exclusions.excludes(object.name())) continue;
                if (type == Type.INDEX && excludedIndexes.contains(object.name())) continue;
                selected.put(object.name(), object);
            }
            objects.put(type, selected);
        }
        Map<String, String> constraintIndexes = new TreeMap<>();
        snapshot.constraintIndexes().forEach((index, table) -> {
            if (!exclusions.excludes(table) && !excludedIndexes.contains(index)) constraintIndexes.put(index, table);
        });
        Map<String, String> constraintTables = new TreeMap<>();
        snapshot.constraintTables().forEach((name, table) -> {
            if (objects.get(Type.TABLE).containsKey(table) && !excludedKeys.contains(name)) constraintTables.put(name, table);
        });
        return new Snapshot(objects, snapshot.foreignKeys().stream()
                .filter(key -> !excludedKeys.contains(key.name())).toList(), snapshot.viewDependencies(),
                constraintIndexes, constraintTables);
    }

    /**
     * Tabellen- und View-DDL kann geschützte Abhängigkeiten verändern oder ungültig machen.
     * Solche Konflikte werden vor dem Schreiben abgelehnt, statt einen Ausschluss zu übergehen.
     */
    void checkDependencies(Set<String> changedTables, Set<String> changedViews,
                           String reference, String target) throws SQLException {
        for (DbObject index : originalActual.objects(Type.INDEX).values()) {
            if (excludedIndexes.contains(index.name()) && changedTables.contains(index.tableName())) {
                throw conflict("Index", index.name(), index.tableName());
            }
        }
        checkConstraintIndexes(originalActual, changedTables);
        checkConstraintIndexes(originalDesired, changedTables);
        for (ForeignKey key : originalActual.foreignKeys()) {
            if (!excludedKeys.contains(key.name())) continue;
            if (changedTables.contains(key.tableName())) throw conflict("Fremdschlüssel", key.name(), key.tableName());
            if (target.equals(key.referencedOwner()) && changedTables.contains(key.referencedTableName())) {
                throw conflict("Fremdschlüssel", key.name(), key.referencedTableName());
            }
        }
        for (ForeignKey key : desired.foreignKeys()) {
            if (reference.equals(key.referencedOwner()) && exclusions.excludes(key.referencedTableName())
                    && !originalActual.objects(Type.TABLE).containsKey(key.referencedTableName())) {
                throw new SQLException("Fremdschlüssel " + key.name() + " benötigt die ausgeschlossene Tabelle "
                        + target + "." + key.referencedTableName() + ", die im Ziel fehlt.");
            }
        }
        checkConstraintNames(changedTables);
        checkProtectedViews(changedTables, changedViews);
        for (String view : desired.objects(Type.VIEW).keySet()) {
            for (String dependency : desired.viewDependencies().getOrDefault(view, Set.of())) {
                if (!exclusions.excludes(dependency)) continue;
                // Eine ausgeschlossene SELECT-Grundlage darf im Ziel auch eine Tabelle
                // statt einer View sein oder umgekehrt; ihre Definition wird nicht abgeglichen.
                if (!originalActual.objects(Type.VIEW).containsKey(dependency)
                        && !originalActual.objects(Type.TABLE).containsKey(dependency)) {
                    throw new SQLException("View " + view + " benötigt das ausgeschlossene Objekt "
                            + target + "." + dependency + ", das im Ziel fehlt.");
                }
            }
        }
    }

    /**
     * Prüft den vollständigen Zielgraphen, einschließlich nicht ausgewählter und indirekter
     * Abhängigkeiten. Ohne Spaltenanalyse kann auch ein ALTER/REPLACE die Projektion einer
     * ausgeschlossenen View brechen; deshalb werden Änderungen ihrer Grundlagen abgelehnt.
     * Bereits besuchte Namen begrenzen den Durchlauf auch bei bestehenden View-Zyklen.
     */
    private void checkProtectedViews(Set<String> changedTables, Set<String> changedViews) throws SQLException {
        Set<String> changedObjects = new HashSet<>(changedTables);
        changedObjects.addAll(changedViews);
        if (changedObjects.isEmpty()) return;
        for (String view : new TreeSet<>(originalActual.objects(Type.VIEW).keySet())) {
            if (!exclusions.excludes(view)) continue;
            Set<String> visited = new HashSet<>();
            var pending = new ArrayDeque<String>();
            pending.add(view);
            while (!pending.isEmpty()) {
                String name = pending.removeFirst();
                if (!visited.add(name)) continue;
                if (changedObjects.contains(name)) {
                    throw new SQLException("Ausgeschlossene View " + view + " hängt von " + name
                            + " ab: Die geplante Tabellen-/View-Änderung könnte sie ungültig machen.");
                }
                pending.addAll(new TreeSet<>(originalActual.viewDependencies().getOrDefault(name, Set.of())));
            }
        }
    }

    /** Auch Constraints ausgeblendeter Speichertabellen belegen schemaweit eindeutige Namen. */
    private void checkConstraintNames(Set<String> changedTables) throws SQLException {
        Set<String> foreignKeys = new HashSet<>();
        desired.foreignKeys().forEach(key -> foreignKeys.add(key.name()));
        for (var constraint : originalDesired.constraintTables().entrySet()) {
            if (!changedTables.contains(constraint.getValue()) && !foreignKeys.contains(constraint.getKey())) continue;
            String targetTable = originalActual.constraintTables().get(constraint.getKey());
            if (targetTable != null && (!actual.objects(Type.TABLE).containsKey(targetTable)
                    || excludedKeys.contains(constraint.getKey()))) {
                throw new SQLException("Constraint " + constraint.getKey() + " wird für Tabelle "
                        + constraint.getValue() + " benötigt, sein Name ist jedoch durch einen ausgeschlossenen "
                        + "Constraint auf Tabelle " + targetTable + " belegt.");
            }
        }
    }

    /** PK-/UK-Indizes werden auch bei CREATE TABLE in dessen DDL mitgeliefert. */
    private void checkConstraintIndexes(Snapshot snapshot, Set<String> changedTables) throws SQLException {
        for (var index : snapshot.constraintIndexes().entrySet()) {
            if (excludedIndexes.contains(index.getKey()) && changedTables.contains(index.getValue())) {
                throw conflict("PK-/UK-Index", index.getKey(), index.getValue());
            }
        }
    }

    private static SQLException conflict(String type, String name, String table) {
        return new SQLException("Ausgeschlossener " + type + " " + name
                + " schützt Tabelle " + table + ": Die geplante Tabellenänderung könnte ihn verändern.");
    }
}
