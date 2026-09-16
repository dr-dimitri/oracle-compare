package de.axa.oraclecompare;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Vergleicht Oracle-19c-Schemata über eigene Java-Objekte und erzeugt ein SQL*Plus-/SQLcl-Skript.
 * Tabellen, Indizes, Views und Sequenzen werden aus Dictionary-Sichten gelesen; der Vergleich
 * und die SQL-Erzeugung erfolgen ausschließlich in Java. Das Skript gleicht das Ziel an die
 * Referenz an und enthält auch DROP für überzählige Objekte. Ein Markdown-Bericht dokumentiert
 * die geplanten Schritte. Das SQL wird nicht automatisch ausgeführt.
 *
 * <p>Alle Einstellungen stammen aus {@code oracle-compare.properties} im Arbeitsverzeichnis.
 * Die Anwendung übergibt ausschließlich offene JDBC-Connections. Diese werden weder geschlossen
 * noch committed; auch ihre Session- und Transaktionseinstellungen bleiben unverändert.
 * Die Schemata dürfen während der Analyse nicht parallel per DDL geändert werden.</p>
 */
public final class OracleSchemaCompare {
    private final CompareConfiguration configuration;

    /** Erstellt den Einstieg; die Konfiguration wird vor jeder Analyse intern geladen und geprüft. */
    public OracleSchemaCompare() {
        configuration = null;
    }

    /** Interner Einstieg für isolierte Tests mit deterministischen Einstellungen. */
    OracleSchemaCompare(CompareConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /**
     * Liest beide Schemata, plant alle Änderungen und schreibt SQL und Markdown-Bericht erst
     * nach erfolgreicher Prüfung. {@code report.path} bestimmt den Berichtspfad; ohne Angabe
     * wird an den SQL-Dateinamen {@code .md} angehängt. Der Bericht ist UTF-8 mit CRLF kodiert
     * und beschreibt die Planung, keine bereits ausgeführten Datenbankänderungen.
     * Unterschiedliche Datenbanken und identische Schemanamen auf getrennten Verbindungen sind
     * zulässig. Bei derselben Connection müssen die konfigurierten Schemanamen verschieden sein.
     *
     * @param referenceConnection offene Verbindung zur Referenzdatenbank; bleibt beim Aufrufer
     * @param targetConnection offene Verbindung zur Zieldatenbank; bleibt beim Aufrufer
     * @return absoluter Pfad der vollständig geschriebenen SQL-Datei in Windows-1252 mit CRLF
     * @throws IOException bei Konfigurations-, Zeichenkodierungs- oder Dateifehlern
     * @throws SQLException bei Dictionary-, Verbindungs- oder nicht darstellbaren Abgleichsfehlern
     */
    public Path writeSynchronizationScript(Connection referenceConnection, Connection targetConnection)
            throws SQLException, IOException {
        Objects.requireNonNull(referenceConnection, "referenceConnection");
        Objects.requireNonNull(targetConnection, "targetConnection");
        CompareConfiguration settings = configuration == null ? CompareConfiguration.load() : configuration;
        SqlScriptWriter.validateOutputs(settings.outputFile(), settings.reportFile());
        if (referenceConnection == targetConnection && settings.referenceSchema().equals(settings.targetSchema())) {
            throw new IllegalArgumentException("Auf derselben Connection müssen Referenz- und Zielschema verschieden sein.");
        }
        if (referenceConnection.isClosed() || targetConnection.isClosed()) {
            throw new SQLException("Eine JDBC-Connection ist geschlossen.");
        }
        ExclusionFilter exclusions = new ExclusionFilter(settings.excludedObjects());
        SchemaDefinition reference = new OracleDictionaryReader(referenceConnection).read(settings.referenceSchema(), exclusions);
        SchemaDefinition target = new OracleDictionaryReader(targetConnection).read(settings.targetSchema(), exclusions);
        ComparisonPlan plan = new SchemaComparisonPlanner().comparisonPlan(reference, target, exclusions);
        String report = new MarkdownReportRenderer().render(plan, settings);
        return SqlScriptWriter.write(settings.outputFile(), plan.script(), settings.reportFile(), report);
    }
}
