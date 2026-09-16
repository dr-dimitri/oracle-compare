package de.axa.oraclecompare;

import java.util.List;
import java.util.Objects;

/**
 * Unveränderliches Ergebnis eines Abgleichs: ausführbares SQL und die zugehörigen Schritte
 * in Ausführungsreihenfolge. Damit beschreibt der Bericht denselben Plan wie die SQL-Datei,
 * ohne fertiges SQL erneut zerlegen oder den Abgleich ein zweites Mal ausführen zu müssen.
 */
record ComparisonPlan(String referenceSchema, String targetSchema, String script, List<Operation> operations) {
    ComparisonPlan {
        Objects.requireNonNull(referenceSchema, "referenceSchema");
        Objects.requireNonNull(targetSchema, "targetSchema");
        Objects.requireNonNull(script, "script");
        operations = List.copyOf(operations);
    }

    /** Fachliche Aktion; COMPILE und VALIDATE kennzeichnen zusätzliche Prüfschritte. */
    enum Action { CREATE, ALTER, DROP, COMPILE, VALIDATE }

    /** Betroffenes Objekt; SCHEMA wird für die abschließende View-Prüfung verwendet. */
    enum ObjectType { TABLE, INDEX, VIEW, SEQUENCE, CONSTRAINT, SCHEMA }

    /**
     * Ein geplanter SQL-Schritt mit fachlicher Einordnung. Bei Indizes und Constraints
     * bezeichnet tableName die zugehörige Tabelle; ansonsten ist tableName null.
     * Auch vorübergehendes Entfernen und Wiederherstellen abhängiger Objekte wird erfasst.
     */
    record Operation(Action action, ObjectType objectType, String objectName, String tableName, String sql) {
        Operation {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(objectType, "objectType");
            Objects.requireNonNull(objectName, "objectName");
            Objects.requireNonNull(sql, "sql");
        }
    }
}
