package de.axa.oraclecompare;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;

/** Kleiner Kommandozeileneinstieg; Anwendungen können OracleSchemaComparator direkt verwenden. */
public final class CompareSchemas {
    private CompareSchemas() { }

    /**
     * Erwartet JDBC-URL, Benutzer, Referenzschema, Zielschema und Ausgabepfad;
     * danach optional beliebig viele Ausschlussmuster. Jokerzeichen in der Shell quotieren.
     * Das Passwort wird über ORACLE_PASSWORD gelesen und erscheint nicht in der Argumentliste.
     * Der Oracle-JDBC-Treiber muss im Laufzeit-Classpath liegen.
     *
     * @param args die fünf Pflichtargumente, gefolgt von optionalen Ausschlussmustern
     * @throws Exception bei Verbindungs-, Metadaten- oder Dateifehlern; beendet den Prozess mit Fehlerstatus
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException("Aufruf: CompareSchemas <jdbc-url> <benutzer> <referenzschema> <zielschema> <datei.sql> [ausschlussmuster ...]");
        }
        var excludedObjects = Arrays.asList(Arrays.copyOfRange(args, 5, args.length));
        new ExclusionFilter(excludedObjects); // Ungültige Muster bereits vor dem Verbindungsaufbau melden.
        String password = System.getenv("ORACLE_PASSWORD");
        if (password == null) throw new IllegalArgumentException("Umgebungsvariable ORACLE_PASSWORD fehlt.");
        try (var connection = DriverManager.getConnection(args[0], args[1], password)) {
            Path output = Path.of(args[4]);
            new OracleSchemaComparator().writeSynchronizationScript(connection, args[2], args[3], output,
                    excludedObjects);
            System.out.println("Abgleichsdatei geschrieben: " + output.toAbsolutePath());
        }
    }
}
