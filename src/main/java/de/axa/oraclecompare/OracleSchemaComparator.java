package de.axa.oraclecompare;

import static de.axa.oraclecompare.SchemaModel.*;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;

/**
 * Erstellt für Oracle 19c ein SQL*Plus-/SQLcl-Skript, das ein Zielschema an ein
 * Referenzschema angleicht: Tabellen samt Constraints, Indizes, Views und Sequenzen.
 * Fehlende Objekte werden angelegt, Unterschiede geändert und überzählige Objekte gelöscht.
 * Die Klasse führt das Skript nicht aus und überträgt keine Tabelleninhalte.
 * Tabellen werden einschließlich ihrer Primär-/Unique-Schlüssel vor den Fremdschlüsseln
 * angelegt. Die separate Fremdschlüsselphase unterstützt auch zyklische und Selbstreferenzen.
 *
 * <p>Beide Schemata müssen in derselben Datenbank/PDB liegen. Die übergebene Verbindung
 * benötigt vollständigen Dictionary-/Metadatenzugriff (üblicherweise SELECT_CATALOG_ROLE).
 * Ihre Verwaltung und die Transaktionskontrolle bleiben vollständig beim Aufrufer.
 * Während der Analyse dürfen die Schemata nicht parallel per DDL verändert werden.</p>
 *
 * <p>Die Instanz ist zustandslos. Eine Connection darf während eines Aufrufs nicht
 * gleichzeitig anderweitig verwendet werden. Nicht durch Oracle abbildbare Tabellen-
 * oder Sequenzänderungen führen zu einer SQLException, nicht zu einem Teilergebnis.</p>
 */
public final class OracleSchemaComparator {
    private static final Charset OUTPUT_CHARSET = Charset.forName("windows-1252");

    /**
     * Schreibt den vollständigen Abgleich als Windows-1252-Datei mit CRLF-Zeilenumbrüchen.
     * Nicht darstellbare Zeichen führen zu einer IOException. Eine vorhandene Datei wird
     * erst nach erfolgreicher Planung und vollständigem Schreiben ersetzt.
     *
     * @param connection offene Oracle-JDBC-Verbindung; wird weder geschlossen noch committed
     * @param referenceSchema gewünschter Stand, als exakter Dictionary-Name ohne äußere Quotes
     * @param targetSchema zu änderndes Schema, als exakter Dictionary-Name ohne äußere Quotes
     * @param outputFile lokale SQL-Datei; fehlende Elternverzeichnisse werden angelegt
     * @throws SQLException bei fehlenden Rechten, Metadaten oder nicht abbildbaren Änderungen
     * @throws IOException bei Schreibfehlern oder in Windows-1252 nicht darstellbaren Zeichen
     * @throws IllegalArgumentException bei ungültigen oder identischen Schemanamen
     */
    public void writeSynchronizationScript(Connection connection, String referenceSchema,
                                           String targetSchema, Path outputFile) throws SQLException, IOException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(outputFile, "outputFile");
        validateSchemas(referenceSchema, targetSchema);
        if (connection.isClosed()) throw new SQLException("Die JDBC-Connection ist geschlossen.");
        writeSynchronizationScript(new OracleMetadata(connection), referenceSchema, targetSchema, outputFile);
    }

    /** Interner Einstieg für Tests ohne laufende Oracle-Instanz. */
    void writeSynchronizationScript(Metadata metadata, String referenceSchema,
                                    String targetSchema, Path outputFile) throws SQLException, IOException {
        validateSchemas(referenceSchema, targetSchema);
        String script = plan(metadata, referenceSchema, targetSchema);
        Path output = outputFile.toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".oracle-compare-", ".sql.tmp");
        try {
            Files.writeString(temporary, windowsLineEndings(script), OUTPUT_CHARSET);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Vereinheitlicht auch gemischte Oracle-Zeilenumbrüche, ohne vorhandene CRLF zu verdoppeln. */
    private static String windowsLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
    }

    /** Plant zuerst alle Operationen; ein Fehler kann so keine halbe Abgleichsdatei hinterlassen. */
    private String plan(Metadata metadata, String reference, String target) throws SQLException {
        Snapshot desired = metadata.snapshot(reference);
        Snapshot actual = metadata.snapshot(target);
        List<String> dropIndexes = new ArrayList<>();
        List<String> sequences = new ArrayList<>();
        List<String> constraintDrops = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        List<String> indexes = new ArrayList<>();
        List<String> releaseConstraintIndexes = new ArrayList<>();
        Map<String, String> views = new LinkedHashMap<>();
        Set<String> changedTables = new TreeSet<>();
        Set<String> removedTables = difference(actual.objects(Type.TABLE).keySet(), desired.objects(Type.TABLE).keySet());
        changedTables.addAll(removedTables);

        for (Type type : Type.values()) {
            for (DbObject object : new TreeMap<>(desired.objects(type)).values()) {
                DbObject old = actual.objects(type).get(object.name());
                String ddl = null;
                if (old != null && type == Type.INDEX && changedTables.contains(old.tableName())) {
                    // DROP COLUMN kann auch unveränderte Indexdefinitionen physisch entfernen.
                    dropIndexes.add(drop(target, old));
                    ddl = metadata.ddl(reference, target, object);
                } else if (old == null) {
                    ddl = metadata.ddl(reference, target, object);
                    if (type == Type.INDEX && actual.constraintIndexes().containsKey(object.name())) {
                        releaseConstraintIndexes.add(dropIndexIfPresent(target, object.name()));
                    }
                } else {
                    String alterXml;
                    try {
                        alterXml = comparisonXml(metadata, type, metadata.sxml(target, target, old), metadata.sxml(reference, target, object));
                        MetadataXml.Difference diff = MetadataXml.inspect(alterXml);
                        if (!diff.hasStatements() && diff.unsupportedReason() == null) continue;
                        if (type == Type.INDEX) {
                            // Eine geänderte Spaltenliste ist nicht per ALTER INDEX ausdrückbar.
                            dropIndexes.add(drop(target, old));
                            ddl = metadata.ddl(reference, target, object);
                        } else if (type == Type.VIEW) {
                            // CREATE OR REPLACE erhält die Grants einer bestehenden View.
                            ddl = metadata.ddl(reference, target, object);
                        } else {
                            if (diff.unsupportedReason() != null) {
                                throw new SQLException("Oracle kann diese Änderung nicht per ALTER ausführen: " + diff.unsupportedReason());
                            }
                            if (type == Type.TABLE) {
                                var phases = MetadataXml.constraintPhases(alterXml);
                                if (phases.constraintDropsXml() != null) {
                                    constraintDrops.add(requireDdl(metadata.alterDdl(type, phases.constraintDropsXml()), object));
                                }
                                changedTables.add(object.name());
                                if (phases.remainingXml() == null) continue;
                                ddl = metadata.alterDdl(type, phases.remainingXml());
                            } else {
                                ddl = metadata.alterDdl(type, alterXml);
                            }
                        }
                    } catch (SQLException e) {
                        throw new SQLException("Abgleich von " + type + " " + target + "." + object.name() + ": " + e.getMessage(),
                                e.getSQLState(), e.getErrorCode(), e);
                    }
                }
                requireDdl(ddl, object);
                switch (type) {
                    case SEQUENCE -> sequences.add(ddl);
                    case TABLE -> { tables.add(ddl); changedTables.add(object.name()); }
                    case INDEX -> indexes.add(ddl);
                    case VIEW -> views.put(object.name(), ddl);
                }
            }
        }
        Map<String, String> actualKeys = foreignKeys(metadata, actual, target, target);
        Map<String, String> desiredKeys = foreignKeys(metadata, desired, reference, target);
        boolean resetKeys = !changedTables.isEmpty() || !actualKeys.equals(desiredKeys);
        metadata.checkExternalForeignKeys(target, changedTables);

        List<String> commands = new ArrayList<>();
        if (resetKeys) {
            for (ForeignKey key : sortedKeys(actual)) {
                commands.add("ALTER TABLE " + SqlText.qualified(target, key.tableName())
                        + " DROP CONSTRAINT " + SqlText.identifier(key.name()) + ";");
            }
        }
        List<String> oldViewOrder = viewOrder(actual);
        Collections.reverse(oldViewOrder);
        for (String name : oldViewOrder) {
            if (!desired.objects(Type.VIEW).containsKey(name)) commands.add(drop(target, actual.objects(Type.VIEW).get(name)));
        }
        commands.addAll(dropIndexes);
        for (DbObject index : new TreeMap<>(actual.objects(Type.INDEX)).values()) {
            if (!desired.objects(Type.INDEX).containsKey(index.name()) && !removedTables.contains(index.tableName())) commands.add(drop(target, index));
        }
        // Constraintnamen gelten schemaweit. Zuerst Namen auf allen bestehenden Tabellen freigeben,
        // erst danach neue Tabellen/Constraints anlegen oder verbleibende Tabellen ändern.
        commands.addAll(constraintDrops);
        for (String name : removedTables) commands.add(drop(target, actual.objects(Type.TABLE).get(name)));
        for (DbObject sequence : new TreeMap<>(actual.objects(Type.SEQUENCE)).values()) {
            if (!desired.objects(Type.SEQUENCE).containsKey(sequence.name())) commands.add(drop(target, sequence));
        }
        commands.addAll(sequences);
        // Jede Tabellen-DDL enthält auch ihre PK/UK-Definitionen, aber keine Foreign Keys.
        // Erst ALLE Tabellen samt Schlüsseln fertigstellen: Das unterstützt selbst FK-Zyklen,
        // für die es keine topologische Reihenfolge der CREATE-TABLE-Anweisungen gibt.
        commands.addAll(tables);
        commands.addAll(releaseConstraintIndexes);
        commands.addAll(indexes);
        // Jetzt existieren alle verwalteten Referenzziele und ihre referenzierbaren Schlüssel.
        if (resetKeys) commands.addAll(desiredKeys.values());
        for (String name : viewOrder(desired)) {
            if (views.containsKey(name)) commands.add(views.get(name));
        }
        boolean changed = !commands.isEmpty();
        if (changed) {
            // Auch zuvor unveränderte Views können durch Tabellen-DDL invalidiert werden.
            for (String name : viewOrder(desired)) commands.add("ALTER VIEW " + SqlText.qualified(target, name) + " COMPILE;");
            if (!desired.objects(Type.VIEW).isEmpty()) commands.add(validateViews(target));
        }
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
        script.append(changed ? "PROMPT Schemaabgleich abgeschlossen.\n" : "PROMPT Keine Unterschiede gefunden.\n");
        return script.toString();
    }

    private static String requireDdl(String ddl, DbObject object) throws SQLException {
        if (ddl == null || ddl.isBlank()) throw new SQLException("Oracle lieferte leeres DDL für " + object.name());
        return ddl;
    }

    private static Map<String, String> foreignKeys(Metadata metadata, Snapshot snapshot, String source, String target) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (ForeignKey key : sortedKeys(snapshot)) {
            String ddl = metadata.foreignKeyDdl(source, target, key);
            if (ddl == null || ddl.isBlank()) throw new SQLException("Fremdschlüssel-DDL fehlt: " + source + "." + key.name());
            result.put(key.name(), ddl.strip());
        }
        return result;
    }

    private static List<ForeignKey> sortedKeys(Snapshot snapshot) {
        return snapshot.foreignKeys().stream().sorted(java.util.Comparator.comparing(ForeignKey::name)).toList();
    }

    /** Nur dokumentierte Nicht-ALTER-Fälle erlauben den Ersatz eines Index/einer View. */
    private static String comparisonXml(Metadata metadata, Type type, String actual, String desired) throws SQLException {
        try {
            return metadata.alterXml(type, actual, desired);
        } catch (SQLException e) {
            int code = e.getErrorCode();
            if ((type == Type.INDEX && code >= 39285 && code <= 39296) || (type == Type.VIEW && code == 39308)) {
                return "<ALTER_XML><NOT_ALTERABLE>ORA-" + code + "</NOT_ALTERABLE></ALTER_XML>";
            }
            throw e;
        }
    }

    /** Sortiert Views topologisch: referenzierte Views werden zuerst erstellt/kompiliert. */
    private static List<String> viewOrder(Snapshot snapshot) throws SQLException {
        List<String> result = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String name : new TreeSet<>(snapshot.objects(Type.VIEW).keySet())) visitView(name, snapshot, visiting, visited, result);
        return result;
    }

    private static void visitView(String name, Snapshot snapshot, Set<String> visiting, Set<String> visited, List<String> result) throws SQLException {
        if (visited.contains(name)) return;
        if (!visiting.add(name)) throw new SQLException("Zyklische View-Abhängigkeit bei " + name);
        for (String dependency : new TreeSet<>(snapshot.viewDependencies().getOrDefault(name, Set.of()))) {
            if (snapshot.objects(Type.VIEW).containsKey(dependency)) visitView(dependency, snapshot, visiting, visited, result);
        }
        visiting.remove(name);
        visited.add(name);
        result.add(name);
    }

    private static Set<String> difference(Set<String> actual, Set<String> desired) {
        Set<String> result = new TreeSet<>(actual);
        result.removeAll(desired);
        return result;
    }

    private static String drop(String schema, DbObject object) {
        return "DROP " + object.type() + " " + SqlText.qualified(schema, object.name()) + ";";
    }

    /** Ein früherer PK/UK-Index kann nach DROP CONSTRAINT weiterexistieren oder bereits fehlen. */
    private static String dropIndexIfPresent(String schema, String name) {
        String sql = "DROP INDEX " + SqlText.qualified(schema, name);
        return "BEGIN\n  EXECUTE IMMEDIATE '" + sql.replace("'", "''") + "';\n"
                + "EXCEPTION WHEN OTHERS THEN\n  IF SQLCODE <> -1418 THEN RAISE; END IF;\nEND;\n/";
    }

    /** FORCE-Views können trotz SQL-Erfolg invalid sein; das Skript meldet dies explizit als Fehler. */
    private static String validateViews(String target) {
        return """
                DECLARE
                  invalid_count PLS_INTEGER;
                BEGIN
                  SELECT COUNT(*) INTO invalid_count FROM all_objects
                    WHERE owner = '%s' AND object_type = 'VIEW' AND status <> 'VALID';
                  IF invalid_count > 0 THEN
                    RAISE_APPLICATION_ERROR(-20002, 'Schemaabgleich: Views sind ungueltig; ALL_ERRORS pruefen.');
                  END IF;
                END;
                /
                """.formatted(target.replace("'", "''"));
    }

    private static void validateSchemas(String reference, String target) {
        SqlText.identifier(reference);
        SqlText.identifier(target);
        if (reference.equals(target)) throw new IllegalArgumentException("Referenz- und Zielschema müssen verschieden sein.");
    }
}
