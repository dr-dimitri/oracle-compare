# Oracle-Schemaabgleich

Java-17-Bibliothek für Oracle 19c. Sie liest Definitionen aus Oracle-Dictionary-Sichten in
unveränderliche Java-Objekte, vergleicht diese in der Anwendung und erzeugt selbst das SQL.
Es gibt keine Aufrufe von `DBMS_METADATA` oder `DBMS_METADATA_DIFF` und keine XML-Konvertierung.
Die bisherigen paketbasierten Klassen und der Kommandozeileneinstieg wurden entfernt.

Der Abgleich bringt das **Zielschema auf den Stand der Referenz**: Tabellen einschließlich
Constraints, Indizes, normale Views und Sequenzen. Fehlende Objekte werden angelegt,
bestehende Definitionen geändert und überzählige Zielobjekte gelöscht. Die Bibliothek schreibt
ein SQL*Plus-/SQLcl-Skript sowie einen Markdown-Änderungsbericht und führt das SQL nicht aus.
Tabelleninhalte werden nicht kopiert.

## Öffentliche API

```java
import de.axa.oraclecompare.OracleSchemaCompare;
import java.nio.file.Path;
import java.sql.Connection;

Path schreibeAbgleich(Connection referenceConnection, Connection targetConnection)
        throws Exception {
    return new OracleSchemaCompare().writeSynchronizationScript(
        referenceConnection, targetConnection);
}
```

Die beiden Connections dürfen auf unterschiedliche Datenbanken/PDBs zeigen. Identische
Schemanamen sind dabei zulässig. Dieselbe Connection kann für zwei verschiedene Schemata
verwendet werden. Es werden keine Verbindungen geöffnet oder geschlossen; Auto-Commit,
Isolation und Sessionzustand bleiben unverändert. Die Analyse ruft weder `commit()` noch
`rollback()` auf und verwendet ausschließlich lesende Dictionary-Abfragen.
Statements und ResultSets werden auch im Fehlerfall geschlossen.

Während eines Aufrufs die Connections exklusiv verwenden und parallele Schema-DDL vermeiden.
Beide Benutzer benötigen Leserechte auf die verwendeten `DBA_*`-Sichten, typischerweise über
`SELECT_CATALOG_ROLE`. Zugriff auf die beiden bisherigen Metadaten-Packages ist nicht nötig.
Die Zielverbindung benötigt außerdem Lesezugriff auf `V$PARAMETER` (`SYS.V_$PARAMETER`),
um `MAX_STRING_SIZE` und `COMPATIBLE` zu prüfen. Der aktuelle Container wird mit `SYS_CONTEXT`
ermittelt; Session- und Datenbankparameter werden dabei nicht geändert.

## Konfiguration im Arbeitsverzeichnis

Alle Einstellungen werden intern aus **`oracle-compare.properties` im Arbeitsverzeichnis des
Java-Prozesses** geladen. Die Datei wird vor jedem Aufruf neu eingelesen. Sie wird nicht aus
dem Classpath geladen. Die mitgelieferte Datei ist eine Vorlage und muss angepasst werden:

```properties
reference.schema=REFERENZ
target.schema=ZIEL
output.path=sql/abgleich-ziel.sql
report.path=reports/abgleich-ziel.md
exclude.1=AUDIT_LOG
exclude.2=TMP_*
exclude.3=*_BACKUP?
```

Die drei ersten Eigenschaften sind Pflichtangaben. Schemanamen entsprechen exakt dem
Dictionary, ohne äußere SQL-Anführungszeichen: `APP` oder beispielsweise `MeineApp` bei einem
gequotet angelegten Benutzer. Relative Ausgabepfade beziehen sich auf das Arbeitsverzeichnis.
Die Properties-Datei ist UTF-8; Zugangsdaten gehören nicht hinein. Fehlende, unbekannte oder
ungültige Eigenschaften führen vor den Dictionary-Abfragen zum Fehler.

`report.path` ist optional und bestimmt die Markdown-Datei. Ohne Angabe wird an den SQL-Pfad
`.md` angehängt, beispielsweise `sql/abgleich-ziel.sql.md`. Ein expliziter leerer Wert ist ungültig.
SQL und Bericht müssen unterschiedliche Dateien bezeichnen; im selben Verzeichnis reicht ein
Unterschied nur in der Groß-/Kleinschreibung nicht aus. Die öffentliche Methode erhält
weiterhin nur die beiden Connections und liefert den SQL-Dateipfad zurück.

Ausschlüsse sind optional und werden als `exclude.1`, `exclude.2` usw. eingetragen. Positive
Nummern dürfen Lücken haben. Ein Eintrag bezeichnet ein vollständiges Muster; Kommas innerhalb
eines Objektnamens bleiben erhalten. Properties maskiert Backslashes selbst: Für einen wörtlichen
Stern im Namen `REPORT*` lautet der Dateieintrag `exclude.1=REPORT\\*`. Windows-Pfade mit
Vorwärtsschrägstrichen oder doppelt geschriebenen Backslashes angeben.

## Ausschlüsse und Abhängigkeiten

Muster gelten für beide Schemata und alle vier Objekttypen, ohne Schema-/Typpräfix.
Groß-/Kleinschreibung wird ignoriert. `*` bezeichnet beliebig viele Zeichen, `?` genau ein Zeichen.
Ein Backslash maskiert das folgende Zeichen; `_`, `%`, Punkte und Regex-Zeichen sind wörtlich.
Ohne Ausschlusseinträge werden alle unterstützten Objekte verglichen; `exclude.1=*` schließt
alle aus. Leere Muster und unvollständige Maskierungen werden abgelehnt.

Ausgeschlossene Objekte erhalten keine CREATE-/ALTER-/DROP-/COMPILE-Anweisungen. Der Ausschluss
einer Tabelle umfasst ihre Indizes und Constraints. Interne Speichertabellen ausgeschlossener
Domain-Indizes werden über `DBA_SECONDARY_OBJECTS` zugeordnet. Sie müssen nicht unter ihren
generierten Namen einzeln ausgeschlossen werden. Belegte Constraint-Namen bleiben geschützt.

Ein Abhängigkeitskonflikt führt bereits bei der Planung zu einer `SQLException` mit Objektkontext.
Das betrifft insbesondere ausgeschlossene Indizes und Fremdschlüssel, eingehende Fremdschlüssel
nicht verwalteter Tabellen sowie direkte und indirekte Grundlagen ausgeschlossener Ziel-Views.
Eine vorhandene Ausgabedatei bleibt erhalten. Lokale View-Abhängigkeiten werden verfolgt;
Referenzen über Datenbanklinks werden nicht mit gleichnamigen lokalen Objekten verwechselt.

Benötigt ein ausgewähltes Objekt eine ausgeschlossene Grundlage, muss diese passend im Ziel
vorhanden sein. Die Anwendung kann nicht aus Metadaten allein garantieren, dass bestehende
Daten neue Datentypen oder Constraints erfüllen.
Das gilt auch für Sequenzen in Spalten-Defaults. Eine ausgeschlossene Zieltabelle schützt ihre
Default-Sequenzen vor DROP und ALTER; fehlende lokale Sequenzen in gewünschten Defaults werden
vor der Ausgabe gemeldet. Kommentare, Literale und Referenzen auf andere Schemata oder über
Datenbanklinks werden nicht mit lokalen Sequenzabhängigkeiten verwechselt.

## Objektmodell und SQL-Planung

- `SchemaDefinition` beschreibt Tabellen, Spalten, Constraints, Indizes, Views, Sequenzen,
  Partitionen und externe Dateien als unveränderliche Records.
- `OracleDictionaryReader` liest das Modell über gebundene Schema-/Objektparameter. LONG-Felder
  wie View-Texte, Defaults und Check-Ausdrücke werden vollständig gelesen.
- `SchemaComparisonPlanner` vergleicht die Definitionen und prüft Abhängigkeiten und Ausschlüsse.
  Sein unveränderlicher `ComparisonPlan` enthält SQL und strukturierte Operationen in Ausführungsreihenfolge.
- `MarkdownReportRenderer` dokumentiert diesen Plan mit Zusammenfassung, Objektübersicht und SQL je Schritt.
- `OracleSqlRenderer` erzeugt Oracle-DDL aus dem Modell, einschließlich Schema-Remapping für
  eingebettete SQL-Ausdrücke. Literale, Kommentare und entfernte DB-Link-Referenzen bleiben erhalten.
- `OracleSchemaCompare` kapselt Konfiguration, beide Verbindungen und die Dateiausgabe.
- `OracleDatabaseCapabilities` bestimmt, welche Collation-Klauseln die Zieldatenbank unterstützt.

Sequenzen entstehen vor Tabellen mit Sequenz-Defaults. Fremdschlüssel werden vor betroffenen
Tabellenänderungen entfernt. Constraint-Namen werden schemaweit freigegeben, bevor sie auf
anderen Tabellen wiederverwendet werden. Alle Tabellen und Primär-/Unique-Schlüssel entstehen
vor der Fremdschlüsselphase; auch Selbstreferenzen und FK-Zyklen werden damit unterstützt.
Views werden nach ihren Abhängigkeiten angelegt und anschließend kompiliert und geprüft.
Geänderte Indizes werden bei Bedarf ersetzt, Views über `CREATE OR REPLACE VIEW` angepasst.
Virtuelle Spalten werden vor ihren zu löschenden Grundlagen entfernt. Beim vollständigen
Spaltenwechsel bleibt eine physische Spalte bis zur Anlage der neuen Spalten erhalten.
Spaltenabbau erfolgt vor dem abschließenden READ ONLY; nötigenfalls wird eine bereits
schreibgeschützte Tabelle vorübergehend auf READ WRITE gesetzt.

Bei `MAX_STRING_SIZE=STANDARD`, `COMPATIBLE<12.2` oder im Container `CDB$ROOT` wird die implizite
Collation `USING_NLS_COMP` ohne verbotene Deklarationsklauseln ausgegeben. Andere Collations
benötigen ein entsprechend konfiguriertes Ziel und werden andernfalls ausdrücklich abgelehnt.

Der Definitionsvergleich ignoriert laufende Sequenzpositionen und berücksichtigt Unterschiede
in generierten Constraint-Namen semantisch. Neue Sequenzen beginnen beim gelesenen Dictionary-Stand.
RANGE-, LIST- und HASH-Partitionierung, explizite Subpartitionen, lokale sowie globale partitionierte
Indizes und externe Tabellen mit `ORACLE_LOADER`/`ORACLE_DATAPUMP` werden im Objektmodell erfasst.
Nicht partitionierte BasicFile-/SecureFile-LOBs werden einschließlich ihrer Speicherattribute
angelegt. Tablespaces und physische Definitionsattribute werden nicht pauschal ausgeblendet.
Unterstützte Änderungen umfassen auch gewöhnliche Tablespace-Wechsel, PCTFREE/INITRANS,
Logging, Kompression, Parallelität und die Dateiquellen/Zugriffsparameter externer Tabellen.

Nicht sicher ausführbare Änderungen führen zu einem expliziten Fehler vor dem Schreiben.
Insbesondere gibt es keinen automatischen Tabellenneuaufbau mit Datenverlust. Änderungen einer
bestehenden Partitionierungsstrategie benötigen einen gesonderten Migrationsplan.
Spezielle Oracle-Ausprägungen, für die der Generator keine vollständige Darstellung besitzt,
werden ausdrücklich abgelehnt. Dazu zählen unter anderem Cluster- und aktive Domain-Indizes,
Bitmap-Join-Indizes, Reference-Partitionierung, Objekt-/Nested-/IOT-Tabellen sowie
Subpartition-Templates. Materialized Views einschließlich ihrer Speicher-/Logtabellen werden
nicht abgeglichen. Grants, Kommentare, Trigger, Packages, Types und Synonyme gehören ebenfalls
nicht zum Abgleich; benötigte externe Ressourcen müssen im Ziel bereits existieren.
Partitionierte externe Tabellen werden ausdrücklich abgelehnt: Ihre partitionsbezogenen
Dateiquellen und Zugriffsparameter sind noch nicht vollständig im Modell enthalten. Normale
partitionierte Tabellen und nicht partitionierte externe Tabellen bleiben unterstützt.

Weitere ausdrücklich abgelehnte Varianten sind verschlüsselte oder partitionierte LOBs,
interne LOB-Strukturen etwa für XMLType sowie LOB-`RETENTION MAX`, deren notwendige MAXSIZE
die verwendeten Dictionary-Sichten nicht vollständig beschreiben. Änderungen bestehender
LOB-Speicherung benötigen ebenfalls einen eigenen Migrationsplan. Besondere View-Ausprägungen
(etwa Editioning, eigene Constraints, unsichtbare Spalten oder abweichende Default-Collation)
werden nicht in eine gewöhnliche View umgewandelt. Mehrdeutige Kombinationen mehrerer
`IS NOT NULL`-Constraints auf derselben Spalte werden abgelehnt, statt ungültige DDL zu erzeugen.
Dies gilt auch für Views mit `WITH CHECK OPTION`. Vollständige Spaltenwechsel, die wegen
LONG-/LONG-RAW- oder 1000-Spalten-Grenzen keine Übergangsspalte erlauben, benötigen einen eigenen
Migrationsplan; einzelne Spaltenwechsel werden durch frühzeitiges Entfernen überzähliger Spalten
innerhalb dieser Grenzen gehalten. Zwischenzustände ohne physische oder sichtbare Spalte werden
ebenfalls abgelehnt. Globale HASH-Indizes mit abweichender Kompression einzelner Partitionen
benötigen einen eigenen Migrationsplan, da CREATE INDEX diese Kombination nicht direkt erlaubt.
Der Referenzschemaname darf in eingebettetem SQL nicht zugleich als Tabellenalias vorkommen;
dynamisches SQL innerhalb von Literalen wird nicht umgeschrieben.

## Ausgabe und Ausführung

Das SQL verwendet **Windows-1252 mit CRLF-Zeilenumbrüchen**, der Markdown-Bericht **UTF-8 mit CRLF**.
Im SQL nicht darstellbare Zeichen führen zu einer `IOException`. Fehlende Verzeichnisse werden
angelegt; Verzeichnislinks werden unterstützt. SQL und Bericht werden zunächst vollständig in
temporäre Dateien geschrieben. Erst danach werden die Ausgabedateien ersetzt. Jede einzelne
Ersetzung erfolgt atomar, wenn das Dateisystem `ATOMIC_MOVE` unterstützt; andernfalls per normalem
Move. Bei einem Fehler während der Ersetzung wird die Wiederherstellung bereits ersetzter Dateien
versucht. Die beiden Dateien bilden keine atomare Transaktion, insbesondere bei Prozessabbruch
oder über mehrere Dateisysteme hinweg. Datei-Symlinks als Ausgabeziel werden abgelehnt.

Der Bericht beschreibt **geplante Änderungen**, keine bereits ausgeführten Datenbankänderungen.
Er enthält Referenz- und Zielschema, Ausgabepfade, Ausschlussmuster, die Anzahl der Operationen
pro Aktion und alle Schritte mit Objekttyp, Objektname und SQL. Auch das vorübergehende Entfernen
und Wiederanlegen von Fremdschlüsseln oder Indizes sowie View-Kompilierungen und Prüfungen sind
enthalten. Die Zählung bezieht sich auf SQL-Operationen, nicht auf unterschiedliche Objekte.
Bei identischen Definitionen dokumentiert der Bericht, dass keine Objektänderungen geplant sind.

Das Skript als Zielbenutzer oder ausreichend berechtigter Benutzer in SQL*Plus/SQLcl ausführen:

```sql
@sql/abgleich-ziel.sql
```

Es enthält SQL*Plus-Anweisungen und SQL-/PLSQL-Terminierungen, ist also kein einzelner JDBC-Aufruf.
`CURRENT_SCHEMA` setzt die Namensauflösung und erteilt keine Rechte. Oracle-DDL führt implizite
Commits aus; ein späterer Fehler macht bereits ausgeführte Änderungen nicht rückgängig.

## Bauen und Tests

```sh
mvn verify
```

Die Bibliothek benötigt keine Laufzeitabhängigkeiten außerhalb von Java SE. Die aufrufende
Anwendung stellt einen für Java 17 und Oracle 19c geeigneten JDBC-Treiber bereit.
Die Unit-Tests prüfen den eigenen SQL-Planer, Dictionary-Abfragen mit JDBC-Testdoubles,
Konfiguration, Filter, Schema-Remapping und Dateiintegrität ohne Oracle-Server.

## Integrationstest über zwei Connections

Der explizit aufrufbare Test im Test-Classpath nimmt beide Connections von außen entgegen:

```java
OracleSchemaIntegration.Result result = OracleSchemaIntegration.run(
    referenceConnection, targetConnection, Path.of("target/integration-sync.sql"));
```

Die Schemanamen stammen aus den Connections. Der Test nutzt intern isolierte Einstellungen,
sodass er die Properties-Datei der Anwendung nicht überschreibt. Beide Testschema müssen leer
sein und dürfen keine Nutzdaten enthalten. Der Test öffnet oder schließt keine Connections.
Er benötigt die Dictionary-/CREATE-Rechte, Tablespace-Quotas, Partitionierungsunterstützung
und das Directory `EXPORT_HOST` mit READ/WRITE sowie Zugriff auf `UTL_FILE.FGETATTR`.
Die vom Referenzschema verwendeten Tablespaces müssen auch im Ziel vorhanden sein.

Die getrennten Fixtures `tests/oracle/reference.sql` und `tests/oracle/target.sql` sind
schemaneutral. Sie enthalten normale Tabellen und Indizes, Constraint-Wechsel zwischen Tabellen,
FK-Ketten und -Zyklen, Views und Sequenzen sowie RANGE-/LIST-Tabellen, lokale Indizes und einen
global RANGE-partitionierten Index.
Ein globaler HASH-Index sowie Spaltenwechsel mit virtuellen Spalten, LONG und READ ONLY decken
die zusätzlichen DDL-Regressionen ab. `external-data.sql` erzeugt eine externe Data-Pump-Tabelle
mit einer eindeutigen Datei in `EXPORT_HOST`. Bei getrennten Hosts wird dieselbe Testdatei auch
über die Zielverbindung erzeugt; bei gemeinsamem Directory wird sie wiederverwendet.

Der Test erzeugt und führt den Abgleich aus, prüft FK-/View-/Partitionszustände und externen
Lesezugriff und verlangt danach einen zweiten Abgleich ohne weitere Objekt-DDL.
Neben dem angegebenen SQL-Pfad bleibt der Markdown-Bericht mit zusätzlicher Endung `.md` erhalten;
temporäre Dateien des zweiten Abgleichs werden entfernt.
Testobjekte und Dumpdateien bleiben zur Untersuchung erhalten und sind anschließend durch die
Testumgebung aufzuräumen. `mvn verify` startet diesen Datenbanktest nicht automatisch.
Ein Lauf gegen echte Oracle-Datenbanken wurde in der Entwicklungsumgebung nicht ausgeführt.
