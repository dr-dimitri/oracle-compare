# Oracle-Schemaabgleich

Java-17-Bibliothek mit Kommandozeileneinstieg für Oracle 19c. Sie erzeugt eine SQL-Datei in
Windows-1252 (CP1252) mit Windows-Zeilenumbrüchen (CRLF),
die das **Zielschema an das Referenzschema** angleicht. Berücksichtigt werden Tabellen samt
Constraints, eigenständige Indizes, normale Views und Sequenzen. Fehlende Objekte werden angelegt,
bestehende Definitionen geändert und ausschließlich im Ziel vorhandene Objekte gelöscht.
Das Programm erzeugt das Skript; es führt keine Schemaänderungen aus.

## Verwendung mit vorhandener Connection

```java
import de.axa.oraclecompare.OracleSchemaComparator;
import java.nio.file.Path;
import java.sql.Connection;

// connection wird von der Anwendung bereitgestellt.
void schreibeAbgleich(Connection connection) throws Exception {
    new OracleSchemaComparator().writeSynchronizationScript(
        connection,
        "REFERENZ",                         // gewünschter Zustand
        "ZIEL",                             // dieses Schema wird angepasst
        Path.of("sql", "abgleich-ziel.sql")
    );
}
```

Schemanamen sind exakte Dictionary-Namen ohne äußere Anführungszeichen. Für unquoted angelegte
Schemata daher beispielsweise `REFERENZ`, für `CREATE USER "MeineApp" ...` dagegen `MeineApp`.
Bei Verwendung einer Connection müssen beide Schemata in derselben Datenbank/PDB existieren
und verschieden sein. Für getrennte Datenbanken können zwei Connections übergeben werden;
dabei dürfen die Schemanamen identisch sein:

```java
new OracleSchemaComparator().writeSynchronizationScript(
    referenceConnection, referenceConnection.getSchema(),
    targetConnection, targetConnection.getSchema(),
    Path.of("abgleich.sql"), java.util.List.of("TMP_*")
);
```

Die Referenz wird über die erste, das Ziel über die zweite Connection gelesen. Die
XML-Differenzbildung und ALTER-DDL-Konvertierung erfolgen auf der Zieldatenbank.
Die Überladung ist auch ohne abschließende Ausschlussliste verfügbar. Beide Datenbanken
müssen die verwendeten Oracle-19c-Pakete und Metadatenformate unterstützen.

Die Klasse schließt die übergebenen Connections nicht, verändert weder Auto-Commit noch
Session-Transformparameter und ruft weder `commit()` noch `rollback()` auf. Während eines
Aufrufs die Connection exklusiv verwenden und parallele DDL-Änderungen an den Schemata vermeiden.
Statements, ResultSets, Metadaten-Handles und temporäre CLOBs werden wieder freigegeben.
Eine vorhandene Ausgabedatei wird erst ersetzt, wenn die Planung und das Schreiben erfolgreich waren.
Zeichen, die sich nicht in Windows-1252 darstellen lassen, führen zu einer `IOException`;
eine vorhandene Ausgabedatei bleibt dabei erhalten.

## Objekte ausschließen

Die zusätzliche Überladung nimmt als letzten Parameter eine Liste von Objektnamen oder
Ausschlussmustern entgegen. Die bisherige Signatur bleibt erhalten und schließt nichts aus.

```java
new OracleSchemaComparator().writeSynchronizationScript(
    connection, "REFERENZ", "ZIEL", Path.of("abgleich.sql"),
    java.util.List.of("AUDIT_LOG", "tmp_*", "*_BACKUP?")
);
```

Die Muster gelten für vollständige Dictionary-Objektnamen in **beiden Schemata**, ohne
Schema-/Typpräfix oder äußere Anführungszeichen. Groß-/Kleinschreibung wird ignoriert.
Sobald ein Muster passt, wird das Objekt ausgeschlossen; die übrige Namensauflösung bleibt
unverändert und verwendet weiterhin die exakten Oracle-Namen.

| Muster | Bedeutung |
| --- | --- |
| `AUDIT_LOG` | Exakter Name, beispielsweise auch `Audit_Log` |
| `TMP_*` | Namen mit Präfix `TMP_`; `*` steht für null oder mehr Zeichen |
| `BACKUP_?` | `BACKUP_` gefolgt von genau einem Zeichen |
| `REPORT\*` | Wörtlicher Name `REPORT*`; in einem Java-String `"REPORT\\*"` schreiben |

`_`, `%`, Punkte und Regex-Sonderzeichen sind wörtlich. Ein Backslash maskiert das nächste
Zeichen. `List.of()` schließt nichts aus; `List.of("*")` schließt alles aus. Null, leere Muster,
Kontrollzeichen und ein abschließender unvollständiger Backslash führen zu einer
`IllegalArgumentException`, bevor auf die Datenbank zugegriffen wird.

Ausgeschlossene Tabellen, Indizes, Views und eigenständige Sequenzen erhalten kein
CREATE-/ALTER-/DROP-/COMPILE-DDL. Ausgeschlossene Views sind auch von der abschließenden
Gültigkeitsprüfung ausgenommen. Bei einem Tabellenausschluss werden ihre Indizes und
ausgehenden Fremdschlüssel ebenfalls ausgeschlossen. Tabellengebundene Constraints und
Identity-Sequenzen werden über die zugehörige Tabelle ausgewählt.

Der Plan behält die notwendigen Schutzinformationen: Ein ausgeschlossener Index darf nicht
durch ein Tabellen-ALTER oder DROP verschwinden. Ebenso bleiben Fremdschlüssel auf
ausgeschlossenen Tabellen erhalten. Wenn eine geplante Tabellenänderung mit diesem Schutz
kollidiert, entsteht eine `SQLException` mit Objektkontext; eine vorhandene Datei bleibt
unverändert. Das gilt auch für ausgeschlossene PK-/UK-Indizes, die Oracle im Tabellen-DDL
mitliefern würde. Ausgeschlossene Bitmap-Join-Indizes im Ziel verhindern konservativ jede
Tabellenänderung, da ihre Dimensionstabellen ebenfalls betroffen sein können.

Ausgeschlossene Ziel-Views schützen auch ihre direkt oder über weitere Views referenzierten
Tabellen und Views vor Änderungen. Der Plan bricht bei einem geplanten DROP, Tabellen-ALTER
oder View-Ersatz dieser Grundlagen ab, da die ausgeschlossene View dadurch ungültig werden
könnte. Unbeteiligte Änderungen bleiben möglich. Berücksichtigt werden lokale Abhängigkeiten
innerhalb des Zielschemas; Referenzen über Datenbanklinks werden nicht als lokale Objekte behandelt.

Interne Speichertabellen eines ausgeschlossenen Domain-Index oder einer ausgeschlossenen
Basistabelle werden über `DBA_SECONDARY_OBJECTS` zugeordnet und einschließlich ihrer Indizes
und Constraints aus dem Abgleich genommen. Ihre generierten Namen müssen nicht zusätzlich
als Ausschlussmuster angegeben werden. Belegte Constraint-Namen bleiben für die Prüfung
auf Namenskonflikte sichtbar.

Benötigt ein ausgewähltes Objekt eine ausgeschlossene Tabelle, View oder Sequenz, muss diese
Abhängigkeit bereits passend im Ziel vorhanden sein. Fehlende ausgeschlossene FK-Elterntabellen
und ausgeschlossene Tabellen-/View-Grundlagen ausgewählter Views werden bei der Planung
gemeldet; die passenden referenzierten Schlüssel bleiben Voraussetzung.
Dictionary-Zugriff bleibt auch für ausgeschlossene Objekte nötig, um Abhängigkeiten zu prüfen.

## Bauen und starten

```sh
mvn test
mvn package
```

Die Bibliothek benötigt außer Java SE keine Laufzeitabhängigkeiten. Die aufrufende Anwendung
stellt einen für Java 17 und Oracle 19c geeigneten Oracle-JDBC-Treiber bereit, etwa `ojdbc11.jar`.

```sh
# ORACLE_PASSWORD vorher in der Umgebung setzen.
java -cp "target/oracle-compare-1.0.0-SNAPSHOT.jar:/pfad/ojdbc11.jar" \
  de.axa.oraclecompare.CompareSchemas \
  'jdbc:oracle:thin:@//localhost:1521/ORCLPDB1' META_READER \
  REFERENZ ZIEL abgleich-ziel.sql
```

Optional folgen nach dem Ausgabepfad die Ausschlussmuster, etwa
`REFERENZ ZIEL abgleich-ziel.sql "AUDIT_LOG" "TMP_*" "*_BACKUP?"`.
Muster in der Shell quotieren, damit sie unverändert beim Programm ankommen.

Unter Windows trennt `;` die Classpath-Einträge. Das erzeugte Skript mit SQL*Plus oder SQLcl
als Zielschema-Eigentümer bzw. ausreichend berechtigter Benutzer ausführen:

```sql
@abgleich-ziel.sql
```

Es enthält SQL*Plus-Kommandos und SQL-/PLSQL-Terminierungen; die gesamte Datei ist kein einzelner
JDBC-`Statement.execute`-Aufruf. `CURRENT_SCHEMA` setzt die Namensauflösung, erteilt aber keine Rechte.
`WHENEVER SQLERROR` beendet die Ausführung bei Fehlern. Oracle-DDL führt implizite Commits aus;
ein bereits ausgeführtes DROP oder ALTER wird durch das dort angegebene ROLLBACK nicht rückgängig.

## Metadatenzugriff und Ablauf

Der Analysebenutzer benötigt Zugriff auf die verwendeten `DBA_*`-Sichten und auf
`SYS.DBMS_METADATA`/`SYS.DBMS_METADATA_DIFF`. Für den schemaübergreifenden Metadatenzugriff
ist üblicherweise die aktivierte Rolle `SELECT_CATALOG_ROLE` erforderlich; bloße SELECT-Rechte
auf fremde Tabellen reichen dafür nicht. Die Aufrufe erfolgen als anonyme PL/SQL-Blöcke.

1. Dictionary-Inventar lesen; Indizes für PK-/UK-Constraints, LOB-/IOT-Indizes und
   Identity-Sequenzen ihren Tabellen zuordnen, damit kein doppeltes CREATE entsteht.
2. Pro Objekt `DBMS_METADATA.OPEN`, Schema-/Namensfilter, `MODIFY` mit `REMAP_SCHEMA`
   und anschließend `SXML` verwenden. Vor dem Vergleich trägt die Referenz bereits den Zielowner.
3. `DBMS_METADATA_DIFF.OPENC` aufrufen und das Ziel **zuerst**, die Referenz danach mit
   `ADD_DOCUMENT` übergeben. `FETCH_CLOB` samt `has_diff` liefert den tatsächlichen Unterschied.
4. Unterschiede über `OPENW` und `ALTERXML` konvertieren. Nicht ausführbare Änderungen,
   `NOT_ALTERABLE` und Oracle-Fehlerkommentare prüfen. Danach mit einem eigenen `OPENW`-Handle
   über `ALTERDDL` und `SQLTERMINATOR = TRUE` das vollständige SQL erzeugen.
5. Fehlende Objekte über `MODIFY` und `DDL` erzeugen, ebenfalls mit `SQLTERMINATOR = TRUE`.
   Bei Tabellen sorgt `CONSTRAINTS_AS_ALTER` auch für separate Constraint-/Index-DDL, wo nötig.
6. Fremdschlüssel vor Tabellenänderungen lösen und nach Tabellen/Indizes wiederherstellen.
   Tabellen werden in zwei Phasen angelegt: zuerst **alle Tabellen einschließlich ihrer
   Primär-/Unique-Schlüssel**, danach die Fremdschlüssel mit separaten `ALTER TABLE`-Anweisungen.
   `CONSTRAINTS = TRUE` und `REF_CONSTRAINTS = FALSE` stellen das für Tabellen-DDL und SXML
   sicher. Dadurch funktionieren auch mehrstufige Referenzen, Selbstreferenzen und gegenseitige
   Fremdschlüsselzyklen. Die alphabetische Reihenfolge innerhalb der Tabellenphase ist deshalb
   unabhängig von den Foreign-Key-Abhängigkeiten.
   Constraint-Drops aus den einzelnen `SQL_LIST_ITEM`-Elementen des `ALTER_XML` schemaweit vor
   Tabellenneuanlagen und den übrigen Tabellenänderungen ausführen. Dadurch werden Namen auch
   beim Wechsel eines Constraints zwischen Tabellen rechtzeitig freigegeben. Beide XML-Teilmengen
   werden weiterhin durch Oracle `ALTERDDL` konvertiert; SQL-Literale werden nicht zerlegt.
   Überzählige abhängige Objekte zuerst entfernen; Sequenzen vor Tabellen anlegen. Views
   nach ihren Abhängigkeiten erstellen und zum Schluss kompilieren und auf Gültigkeit prüfen.

Geänderte freie Indizes werden neu erstellt, auch wenn Oracle ihre Spaltenliste nicht per ALTER
ändern kann. Views werden mit der von Oracle gelieferten CREATE-OR-REPLACE-DDL ersetzt.
Bei Tabellenänderungen werden zugehörige freie Indizes ebenfalls neu erstellt, da beispielsweise
DROP COLUMN sie automatisch entfernen kann. Der Wechsel von einem PK-/UK-Index zu einem freien
Index berücksichtigt, dass Oracle den alten Index beim Constraint-Drop behalten kann.

## Umfang und Grenzen

- Der Abgleich betrifft Definitionen, keine Tabelleninhalte. Bestehende Sequenzpositionen
  (`START_WITH` im SXML) werden beim Vergleich ausgeblendet; neue Sequenzen erhalten die exportierte
  Startposition. Tablespaces, Storage und andere von Oracle unterstützte Definitionsattribute
  bleiben im Abgleich enthalten.
- Oracle kann nicht jede Tabellen-/Sequenzänderung als ALTER ausdrücken. In diesem Fall entsteht
  eine `SQLException` mit Objektkontext und keine neue Abgleichsdatei. Es gibt keinen automatischen
  Tabellenneuaufbau mit implizitem Datenverlust. Vorhandene Daten müssen die gewünschten
  Datentypen und Constraints erfüllen; das lässt sich durch Metadatenvergleich allein nicht zusichern.
- Materialized Views und deren Storage-/Logtabellen, Grants, Kommentare, Trigger, Packages, Types und
  Synonyme werden nicht abgeglichen. Von den ausgewählten Objekten benötigte Types, Funktionen,
  Tablespaces und andere externe Ressourcen müssen im Ziel bereits vorhanden sein.
  Nicht ausgeschlossene Cluster-/Domain-Objekte, Reference-Partitionierung und Bitmap-Join-Indizes führen vor der
  Skripterzeugung zu einem expliziten Abbruch. Bei Bitmap-Join-Indizes sind Tabellenabhängigkeiten
  jenseits der Fakttabelle relevant; eine automatische Migration wird deshalb nicht angeboten.
- Eingehende Fremdschlüssel aus nicht verwalteten Tabellen dürfen geänderte Zieltabellen nicht
  blockieren; die Analyse bricht damit ab. View-Zyklen werden ebenfalls gemeldet.
- `REMAP_SCHEMA` erfasst SQL-Text in Views und Defaults nicht vollständig. Deshalb ergänzt ein
  Lexer das Remapping von Schemaqualifizierungen in SQL-Feldern; Literale, Kommentare und quoted
  Namen bleiben erhalten. Der Referenzschemaname darf dort nicht zugleich als Tabellenalias
  verwendet werden. DB-Link-Namen hinter `@` und die über einen Link angesprochenen entfernten
  Schemaqualifizierungen bleiben unverändert; nur lokale Schemanamen werden remappt.
  Dynamisches SQL in Strings und semantisch mehrdeutige Namensauflösung
  erfordern einen eigenen Migrationsplan.
- Das gesamte Metadaten-/DDL-Ergebnis wird zur vollständigen Vorabprüfung im Java-Speicher gehalten.
  Das Ersetzen der Datei erfolgt atomar, sofern das Dateisystem `ATOMIC_MOVE` unterstützt; sonst
  wird die vollständig geschriebene temporäre Datei per normalem Move ersetzt.

## Tests

`mvn test` prüft die Planung ohne Datenbank: Abgleichsrichtung, CREATE-/DROP-Reihenfolge,
View-Abhängigkeiten, Indexwechsel, Fehlerweitergabe und Erhalt bestehender Dateien. Weitere Tests
prüfen Constraint-Wechsel zwischen Tabellen, die Aufteilung vollständiger XML-Statements,
DB-Link-Referenzen, Quotes, Kommentare und Literale. JDBC-Testdoubles prüfen den Ausschluss
von Materialized-View-Logs, den frühzeitigen Abbruch bei Bitmap-Join-Indizes und die Einstellungen
für den getrennten Tabellen-/Fremdschlüsselexport. Reihenfolgetests decken mehrstufige
Foreign-Key-Abhängigkeiten, Selbstreferenzen, Zyklen und die vorherige PK-/UK-Anlage ab.
Filtertests prüfen exakte Namen, Joker, Maskierung, Unicode und schreibungsunabhängige Vergleiche
sowie Ausschlüsse für alle vier Objekttypen und den Schutz abhängiger Indizes/Fremdschlüssel.

## Integrationstest mit zwei Datenbanken

`OracleSchemaIntegration` ist ein explizit aufrufbarer Test im Test-Classpath. Er nimmt beide
JDBC-Connections von außen entgegen und öffnet oder schließt selbst keine Verbindung:

```java
import de.axa.oraclecompare.OracleSchemaIntegration;

// Beide Connections werden von der eigenen Testumgebung bereitgestellt.
OracleSchemaIntegration.Result result = OracleSchemaIntegration.run(
    referenceConnection, targetConnection, Path.of("target", "integration-sync.sql")
);
System.out.println(result.synchronizationScript());
System.out.println(result.externalDataFile());
```

Die Schemanamen stammen jeweils aus `Connection.getSchema()`. Die Verbindungen können auf
verschiedene Datenbanken/PDBs zeigen; gleiche Schemanamen auf getrennten Datenbanken sind
erlaubt. Es sind keine Schemabezeichnungen im Test festgeschrieben. Die beiden Fixtures liegen
getrennt in `tests/oracle/reference.sql` und `tests/oracle/target.sql`. Ihre Platzhalter werden
vom Java-Test ersetzt; die Ressourcen werden durch Maven nach `target/test-classes/oracle`
kopiert. Die Dateien sind daher für den Aufruf durch den Test vorgesehen.

Voraussetzungen für beide Datenbankzugänge:

- Leere, ausschließlich für diesen Test bestimmte Schemata und eigene JDBC-Verbindungen ohne
  offene Anwendungstransaktionen. Der Test prüft beide Schemata vor dem Aufbau.
- Oracle 19c mit Partitionierungsunterstützung, den oben beschriebenen Metadatenrechten und
  CREATE-Rechten für Tabellen, Indizes, Views und Sequenzen sowie passenden Tablespace-Quotas.
  Die von Referenzobjekten verwendeten Tablespaces müssen auch im Ziel verfügbar sein;
  Tablespace- und Storage-Angaben gehören weiterhin zum Vergleich.
- Das bereits eingerichtete Oracle-Directory `EXPORT_HOST` mit READ-/WRITE-Rechten und
  Zugriff auf `UTL_FILE.FGETATTR`. Ein Directory wird vom Test nicht angelegt.

Der Test baut die Referenz über die erste Connection und den abweichenden Zielbestand über
die zweite Connection auf. Er erzeugt dann das Abgleichsskript, führt es auf dem Ziel aus und
verlangt bei einem zweiten Vergleich, dass keine weitere Objekt-DDL entsteht. Zusätzlich
prüft er reale FK-/View-Zustände, Tabellenpartitionen und lokale/globale Indexpartitionen.

Das Fixture umfasst normale Tabellen, Indizes, Views und Sequenzen, den Constraint-Wechsel
`CK_MOVED` zwischen Tabellen, FK-Ketten, Selbstreferenzen und einen FK-Zyklus. Hinzu kommen
RANGE- und LIST-partitionierte Tabellen, lokale Indizes sowie ein global RANGE-partitionierter
Index. Die Partitionierungsfälle prüfen die vollständige Neuanlage auf dem Ziel; eine Änderung
der Partitionierungsstrategie bestehender Tabellen ist nicht Bestandteil dieses Tests.

Für externe Tabellen verwendet der Test `ORACLE_DATAPUMP` und `EXPORT_HOST`. Eine eindeutig
benannte Dumpdatei mit kleinen Testdaten wird auf der Referenz erzeugt und bei Bedarf über die
Zielverbindung ebenfalls angelegt. Bei einem gemeinsam verwendeten Verzeichnis wird die
vorhandene Datei genutzt. Beide Definitionen verwenden denselben Dateinamen; nach dem Abgleich
prüft der Test auch den tatsächlichen Lesezugriff auf die externe Zieltabelle.

Der Test verändert keine Autocommit-/Isolationseinstellungen. Seine DDL führt trotzdem die
Oracle-üblichen impliziten Commits aus. Testobjekte und die im Ergebnis genannte Dumpdatei
bleiben zur Untersuchung erhalten, auch bei einem Fehler, und müssen anschließend in der
Testumgebung aufgeräumt werden. `mvn test` startet diesen Datenbanktest nicht automatisch.
Ein Lauf gegen echte Oracle-Datenbanken wurde in der Entwicklungsumgebung nicht ausgeführt.

API-Grundlagen: [DBMS_METADATA (Oracle 19c)](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_METADATA.html),
[DBMS_METADATA_DIFF (Oracle 19c)](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_METADATA_DIFF.html)
und [Oracle-Metadatenbeispiele](https://docs.oracle.com/en/database/oracle/oracle-database/19/sutil/using-oracle-dbms_metadata-api.html).
