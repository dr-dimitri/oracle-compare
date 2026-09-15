-- Für Quelle und optional den Ziel-Seed dieselben Daten und denselben Dateinamen verwenden.
-- ORACLE_DATAPUMP erzeugt die Datei; vorhandene Dateien werden nicht wiederverwendet/überschrieben.
CREATE TABLE ${SCHEMA_IDENTIFIER}.${EXTERNAL_TABLE_IDENTIFIER}
ORGANIZATION EXTERNAL (
  TYPE ORACLE_DATAPUMP
  DEFAULT DIRECTORY EXPORT_HOST
  ACCESS PARAMETERS (NOLOGFILE)
  LOCATION ('${EXTERNAL_FILE}')
)
AS
  SELECT CAST(1 AS NUMBER(10)) AS ID, CAST('First external row' AS VARCHAR2(40 CHAR)) AS LABEL FROM dual
  UNION ALL
  SELECT CAST(2 AS NUMBER(10)), CAST('Second external row' AS VARCHAR2(40 CHAR)) FROM dual;
