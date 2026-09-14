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
Beide Schemata müssen in derselben Datenbank/PDB existieren und verschieden sein.

Die Klasse schließt die übergebene Connection nicht, verändert weder Auto-Commit noch
Session-Transformparameter und ruft weder `commit()` noch `rollback()` auf. Während eines
Aufrufs die Connection exklusiv verwenden und parallele DDL-Änderungen an den Schemata vermeiden.
Statements, ResultSets, Metadaten-Handles und temporäre CLOBs werden wieder freigegeben.
Eine vorhandene Ausgabedatei wird erst ersetzt, wenn die Planung und das Schreiben erfolgreich waren.
Zeichen, die sich nicht in Windows-1252 darstellen lassen, führen zu einer `IOException`;
eine vorhandene Ausgabedatei bleibt dabei erhalten.

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
  Cluster-/Domain-Objekte, Reference-Partitionierung und Bitmap-Join-Indizes führen vor der
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

Ein tatsächlicher Oracle-19c-Integrationstest erfordert eine bereitgestellte Datenbank und wurde
in der Entwicklungsumgebung nicht ausgeführt. Dafür liegt `tests/oracle/fixture.sql` bei:

1. Zwei leere, ausschließlich für diesen Test verwendete Schemata `ORACMP_REF` und `ORACMP_TARGET`
   bereitstellen; für Testobjekte passende CREATE-Rechte und Tablespace-Quota vergeben.
2. Das Fixture als berechtigter Testbenutzer ausführen. Es legt bewusst unterschiedliche Tabellen,
   Indizes, Views, Sequenzen und Fremdschlüssel in diesen beiden Schemata an. Der Check-Constraint
   `CK_MOVED` wechselt dabei von `MOVE_B` nach `MOVE_A`, während beide Tabellen geändert werden.
   Neue Tabellen bilden außerdem die Referenzkette `A_FK_LEAF` → `M_FK_MIDDLE` → `Z_FK_ROOT`,
   eine Selbstreferenz und den Zyklus `X_FK_CYCLE` ↔ `Y_FK_CYCLE` ab.
3. Das Programm mit Referenz `ORACMP_REF` und Ziel `ORACMP_TARGET` starten und das erzeugte Skript
   mit SQL*Plus/SQLcl ausführen.
4. Erneut generieren: Die Datei muss `Keine Unterschiede gefunden` enthalten. Außerdem prüfen,
   dass alle Ziel-Views gültig sind und keine überzähligen `EXTRA_*`-Objekte übrig bleiben.

API-Grundlagen: [DBMS_METADATA (Oracle 19c)](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_METADATA.html),
[DBMS_METADATA_DIFF (Oracle 19c)](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_METADATA_DIFF.html)
und [Oracle-Metadatenbeispiele](https://docs.oracle.com/en/database/oracle/oracle-database/19/sutil/using-oracle-dbms_metadata-api.html).
