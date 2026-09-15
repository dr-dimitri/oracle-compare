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
 * <p>Die Ein-Verbindungs-API vergleicht zwei Schemata derselben Datenbank/PDB. Die
 * Zwei-Verbindungs-API unterstützt getrennte Datenbanken, auch mit identischen Schemanamen.
 * Jede Verbindung benötigt vollständigen Dictionary-/Metadatenzugriff (üblicherweise
 * SELECT_CATALOG_ROLE). Verwaltung und Transaktionskontrolle bleiben beim Aufrufer.
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
        writeSynchronizationScript(connection, referenceSchema, targetSchema, outputFile, List.of());
    }

    /**
     * Schreibt einen Abgleich unter Ausschluss der angegebenen Objektnamen in beiden Schemata.
     * Muster gelten für Tabellen, Indizes, Views und Sequenzen ohne Beachtung der Groß-/
     * Kleinschreibung. {@code *} steht für beliebig viele Zeichen, {@code ?} für ein Zeichen;
     * ein Backslash maskiert das folgende Zeichen. Alle anderen Zeichen sind wörtlich.
     * Tabellen werden einschließlich ihrer Indizes und ausgehenden Fremdschlüssel ausgeschlossen.
     * Ein Abhängigkeitskonflikt mit einem geschützten Objekt führt vor dem Schreiben zum Abbruch.
     *
     * @param connection offene Oracle-JDBC-Verbindung, deren Verwaltung beim Aufrufer bleibt
     * @param referenceSchema gewünschter Stand, als exakter Dictionary-Name
     * @param targetSchema zu änderndes Schema, als exakter Dictionary-Name
     * @param outputFile SQL-Ausgabedatei in Windows-1252 mit CRLF-Zeilenumbrüchen
     * @param excludedObjects Ausschlussmuster ohne Schema-/Typpräfix; leere Liste schließt nichts aus
     * @throws SQLException bei Metadatenfehlern oder einem Konflikt mit ausgeschlossenen Objekten
     * @throws IOException bei Schreib- oder Zeichenkodierungsfehlern
     * @throws IllegalArgumentException bei ungültigen Schemanamen oder Ausschlussmustern
     */
    public void writeSynchronizationScript(Connection connection, String referenceSchema,
                                           String targetSchema, Path outputFile, List<String> excludedObjects)
            throws SQLException, IOException {
        ExclusionFilter exclusions = new ExclusionFilter(excludedObjects);
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(outputFile, "outputFile");
        validateSchemas(referenceSchema, targetSchema);
        if (connection.isClosed()) throw new SQLException("Die JDBC-Connection ist geschlossen.");
        Metadata metadata = new OracleMetadata(connection, exclusions);
        writeSynchronizationScript(metadata, referenceSchema, metadata, targetSchema, outputFile, exclusions);
    }

    /** Vergleicht Schemata über getrennte Verbindungen, auch auf unterschiedlichen Oracle-Datenbanken. */
    public void writeSynchronizationScript(Connection referenceConnection, String referenceSchema,
                                           Connection targetConnection, String targetSchema, Path outputFile)
            throws SQLException, IOException {
        writeSynchronizationScript(referenceConnection, referenceSchema, targetConnection, targetSchema, outputFile, List.of());
    }

    /**
     * Vergleicht zwei Datenbanken mit denselben Ausschlussregeln wie die Ein-Verbindungs-API.
     * Referenzmetadaten werden ausschließlich über referenceConnection gelesen; Zielmetadaten,
     * Differenzbildung und ALTERDDL-Konvertierung verwenden targetConnection. Beide Verbindungen
     * bleiben offen und ihre Transaktionseinstellungen unverändert. Bei getrennten Verbindungen
     * sind identische Schemanamen zulässig. Beide Datenbanken müssen Oracle 19c unterstützen.
     *
     * @param referenceConnection Verbindung zur Referenzdatenbank
     * @param referenceSchema gewünschtes Schema als exakter Dictionary-Name
     * @param targetConnection Verbindung zur Zieldatenbank
     * @param targetSchema anzupassendes Schema als exakter Dictionary-Name
     * @param outputFile SQL-Ausgabedatei in Windows-1252 mit CRLF
     * @param excludedObjects Ausschlussmuster für beide Schemata
     * @throws SQLException bei Verbindungs-, Metadaten- oder Abhängigkeitsfehlern
     * @throws IOException bei Schreib- oder Zeichenkodierungsfehlern
     * @throws IllegalArgumentException bei ungültigen Namen/Mustern oder identischem Schema derselben Verbindung
     */
    public void writeSynchronizationScript(Connection referenceConnection, String referenceSchema,
                                           Connection targetConnection, String targetSchema, Path outputFile,
                                           List<String> excludedObjects) throws SQLException, IOException {
        ExclusionFilter exclusions = new ExclusionFilter(excludedObjects);
        Objects.requireNonNull(referenceConnection, "referenceConnection");
        Objects.requireNonNull(targetConnection, "targetConnection");
        Objects.requireNonNull(outputFile, "outputFile");
        SqlText.identifier(referenceSchema);
        SqlText.identifier(targetSchema);
        if (referenceConnection == targetConnection) validateSchemas(referenceSchema, targetSchema);
        if (referenceConnection.isClosed() || targetConnection.isClosed()) throw new SQLException("Eine JDBC-Connection ist geschlossen.");
        writeSynchronizationScript(new OracleMetadata(referenceConnection, exclusions), referenceSchema,
                new OracleMetadata(targetConnection, exclusions), targetSchema, outputFile, exclusions);
    }

    /** Interner Einstieg für Tests ohne laufende Oracle-Instanz. */
    void writeSynchronizationScript(Metadata metadata, String referenceSchema,
                                    String targetSchema, Path outputFile) throws SQLException, IOException {
        writeSynchronizationScript(metadata, referenceSchema, targetSchema, outputFile, List.of());
    }

    /** Testbarer Einstieg mit denselben Ausschlussregeln wie die öffentliche JDBC-API. */
    void writeSynchronizationScript(Metadata metadata, String referenceSchema, String targetSchema,
                                    Path outputFile, List<String> excludedObjects) throws SQLException, IOException {
        validateSchemas(referenceSchema, targetSchema);
        writeSynchronizationScript(metadata, referenceSchema, metadata, targetSchema, outputFile, new ExclusionFilter(excludedObjects));
    }

    /** Testbarer Einstieg mit strikt getrennten Metadatenquellen. */
    void writeSynchronizationScript(Metadata referenceMetadata, String referenceSchema, Metadata targetMetadata,
                                    String targetSchema, Path outputFile, List<String> excludedObjects) throws SQLException, IOException {
        writeSynchronizationScript(referenceMetadata, referenceSchema, targetMetadata, targetSchema,
                outputFile, new ExclusionFilter(excludedObjects));
    }

    private void writeSynchronizationScript(Metadata referenceMetadata, String referenceSchema,
                                            Metadata targetMetadata, String targetSchema,
                                            Path outputFile, ExclusionFilter exclusions) throws SQLException, IOException {
        SqlText.identifier(referenceSchema);
        SqlText.identifier(targetSchema);
        String script = plan(referenceMetadata, targetMetadata, referenceSchema, targetSchema, exclusions);
        Path output = outputFile.toAbsolutePath().normalize();
        // Vorhandene Verzeichnislinks akzeptieren; createDirectories lehnt den Link selbst ab.
        if (!Files.isDirectory(output.getParent())) Files.createDirectories(output.getParent());
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
    private String plan(Metadata referenceMetadata, Metadata targetMetadata, String reference, String target,
                        ExclusionFilter exclusions) throws SQLException {
        ComparisonScope scope = new ComparisonScope(referenceMetadata.snapshot(reference), targetMetadata.snapshot(target), exclusions);
        Snapshot desired = scope.desired();
        Snapshot actual = scope.actual();
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
                    ddl = referenceMetadata.ddl(reference, target, object);
                } else if (old == null) {
                    ddl = referenceMetadata.ddl(reference, target, object);
                    if (type == Type.INDEX && actual.constraintIndexes().containsKey(object.name())) {
                        releaseConstraintIndexes.add(dropIndexIfPresent(target, object.name()));
                    }
                } else {
                    String alterXml;
                    try {
                        alterXml = comparisonXml(targetMetadata, type, targetMetadata.sxml(target, target, old),
                                referenceMetadata.sxml(reference, target, object));
                        MetadataXml.Difference diff = MetadataXml.inspect(alterXml);
                        if (!diff.hasStatements() && diff.unsupportedReason() == null) continue;
                        if (type == Type.INDEX) {
                            // Eine geänderte Spaltenliste ist nicht per ALTER INDEX ausdrückbar.
                            dropIndexes.add(drop(target, old));
                            ddl = referenceMetadata.ddl(reference, target, object);
                        } else if (type == Type.VIEW) {
                            // CREATE OR REPLACE erhält die Grants einer bestehenden View.
                            ddl = referenceMetadata.ddl(reference, target, object);
                        } else {
                            if (diff.unsupportedReason() != null) {
                                throw new SQLException("Oracle kann diese Änderung nicht per ALTER ausführen: " + diff.unsupportedReason());
                            }
                            if (type == Type.TABLE) {
                                var phases = MetadataXml.constraintPhases(alterXml);
                                if (phases.constraintDropsXml() != null) {
                                    constraintDrops.add(requireDdl(targetMetadata.alterDdl(type, phases.constraintDropsXml()), object));
                                }
                                changedTables.add(object.name());
                                if (phases.remainingXml() == null) continue;
                                ddl = targetMetadata.alterDdl(type, phases.remainingXml());
                            } else {
                                ddl = targetMetadata.alterDdl(type, alterXml);
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
        Set<String> changedViews = difference(actual.objects(Type.VIEW).keySet(), desired.objects(Type.VIEW).keySet());
        changedViews.addAll(views.keySet());
        scope.checkDependencies(changedTables, changedViews, reference, target);
        Map<String, String> actualKeys = foreignKeys(targetMetadata, actual, target, target);
        Map<String, String> desiredKeys = foreignKeys(referenceMetadata, desired, reference, target);
        boolean resetKeys = !changedTables.isEmpty() || !actualKeys.equals(desiredKeys);
        targetMetadata.checkExternalForeignKeys(target, changedTables);

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
            if (!desired.objects(Type.VIEW).isEmpty()) commands.add(validateViews(target, desired.objects(Type.VIEW).keySet()));
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
    private static String validateViews(String target, Set<String> selectedViews) {
        // Oracle 19c erlaubt höchstens 1000 Ausdrücke pro IN-Liste. Nur ausgewählte Views prüfen.
        List<String> names = new TreeSet<>(selectedViews).stream().map(name -> "'" + name.replace("'", "''") + "'").toList();
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < names.size(); i += 1000) {
            groups.add("object_name IN (" + String.join(", ", names.subList(i, Math.min(i + 1000, names.size()))) + ")");
        }
        return """
                DECLARE
                  invalid_count PLS_INTEGER;
                BEGIN
                  SELECT COUNT(*) INTO invalid_count FROM all_objects
                    WHERE owner = '%s' AND object_type = 'VIEW' AND status <> 'VALID'
                      AND (%s);
                  IF invalid_count > 0 THEN
                    RAISE_APPLICATION_ERROR(-20002, 'Schemaabgleich: Views sind ungueltig; ALL_ERRORS pruefen.');
                  END IF;
                END;
                /
                """.formatted(target.replace("'", "''"), String.join(" OR ", groups));
    }

    private static void validateSchemas(String reference, String target) {
        SqlText.identifier(reference);
        SqlText.identifier(target);
        if (reference.equals(target)) throw new IllegalArgumentException("Referenz- und Zielschema müssen verschieden sein.");
    }
}
