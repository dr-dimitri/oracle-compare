package de.axa.oraclecompare;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static de.axa.oraclecompare.ComparisonPlan.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Prüft verständliche Berichte sowie unverfälschte SQL-Blöcke bei ungewöhnlichen Objektnamen. */
class MarkdownReportRendererTest {
    @Test
    void reportsDirectionExclusionsAndNoDifferencesWithoutClaimingExecution() {
        CompareConfiguration settings = settings(List.of("TMP_*", "A|B"));
        String report = new MarkdownReportRenderer().render(new ComparisonPlan("SOURCE", "TARGET", "", List.of()), settings);

        assertTrue(report.contains("| Referenzschema | <code>SOURCE</code> |"));
        assertTrue(report.contains("| Zielschema | <code>TARGET</code> |"));
        assertTrue(report.contains("| Berichtsdatei | <code>"));
        assertTrue(report.contains("sync.md</code> |"));
        assertTrue(report.contains("- <code>TMP&#95;&#42;</code>"));
        assertTrue(report.contains("- <code>A&#124;B</code>"));
        assertTrue(report.contains("Keine Unterschiede im berücksichtigten Objektumfang"));
        assertTrue(report.contains("Die Anwendung hat das Skript nicht ausgeführt."));
        assertFalse(report.contains("## SQL je Schritt"));
    }

    @Test
    void includesEveryStepWithCountsAndTableContextInPlanOrder() {
        List<Operation> operations = List.of(
                new Operation(Action.DROP, ObjectType.CONSTRAINT, "FK_CHILD", "CHILD", "ALTER TABLE CHILD DROP CONSTRAINT FK_CHILD;"),
                new Operation(Action.CREATE, ObjectType.SEQUENCE, "SEQ", null, "CREATE SEQUENCE SEQ;"),
                new Operation(Action.ALTER, ObjectType.TABLE, "PARENT", null, "ALTER TABLE PARENT ADD (N NUMBER);"),
                new Operation(Action.CREATE, ObjectType.INDEX, "IX_PARENT", "PARENT", "CREATE INDEX IX_PARENT ON PARENT(N);"),
                new Operation(Action.CREATE, ObjectType.CONSTRAINT, "FK_CHILD", "CHILD", "ALTER TABLE CHILD ADD CONSTRAINT FK_CHILD FOREIGN KEY(N) REFERENCES PARENT(N);"),
                new Operation(Action.ALTER, ObjectType.VIEW, "V", null, "CREATE OR REPLACE VIEW V AS SELECT N FROM PARENT;"),
                new Operation(Action.COMPILE, ObjectType.VIEW, "V", null, "ALTER VIEW V COMPILE;"),
                new Operation(Action.VALIDATE, ObjectType.SCHEMA, "TARGET", null, "BEGIN NULL; END;\n/"));
        String report = new MarkdownReportRenderer().render(new ComparisonPlan("SOURCE", "TARGET", "", operations), settings(List.of()));

        assertTrue(report.contains("**8 geplante Schritte.**"));
        assertTrue(report.contains("| Anlegen | 3 |"));
        assertTrue(report.contains("| Ändern | 2 |"));
        assertTrue(report.contains("| Entfernen | 1 |"));
        assertTrue(report.contains("| Kompilieren | 1 |"));
        assertTrue(report.contains("| Prüfen | 1 |"));
        assertTrue(report.contains("| 1 | Entfernen | Constraint | <code>&#34;TARGET&#34;.&#34;FK&#95;CHILD&#34;</code> | <code>&#34;TARGET&#34;.&#34;CHILD&#34;</code> |"));
        int last = -1;
        for (Operation operation : operations) {
            int current = report.indexOf("```sql\n" + operation.sql() + "\n```");
            assertTrue(current > last, operation.sql());
            last = current;
        }
        assertTrue(report.contains("Prüfen: Schema <code>&#34;TARGET&#34;</code>"));
    }

    @Test
    void escapesMarkdownNamesAndKeepsEmbeddedSemicolonsAndFencesInsideOneSqlBlock() {
        String name = "V|<script>`[x]*~~";
        String sql = "CREATE VIEW \"" + name + "\" AS SELECT 'first;second\n```\n# fake heading' N FROM DUAL;";
        Operation operation = new Operation(Action.CREATE, ObjectType.VIEW, name, null, sql);
        String report = new MarkdownReportRenderer().render(
                new ComparisonPlan("SOURCE", "TARGET", "", List.of(operation)), settings(List.of()));

        assertTrue(report.contains("V&#124;&#60;script&#62;&#96;&#91;x&#93;&#42;&#126;&#126;"));
        assertTrue(report.contains("````sql\n" + sql + "\n````\n"));
        assertTrue(report.contains("**1 geplanter Schritt.**"));
        assertFalse(report.contains("<script>`[x]*~~</code>"));
    }

    @Test
    void escapesLineBreaksInFilePathsWithoutBreakingMetadataTables() {
        assumeTrue(java.io.File.separatorChar == '/', "Windows lässt keine Zeilenumbrüche in Dateipfaden zu.");
        CompareConfiguration settings = new CompareConfiguration("SOURCE", "TARGET", Path.of("sql/sync.sql"),
                Path.of("reports/first\nsecond.md"), List.of());
        String report = new MarkdownReportRenderer().render(new ComparisonPlan("SOURCE", "TARGET", "", List.of()), settings);
        assertTrue(report.contains("reports/first&#10;second.md</code> |"));
        assertFalse(report.contains("first\nsecond.md"));
    }

    private static CompareConfiguration settings(List<String> exclusions) {
        return new CompareConfiguration("SOURCE", "TARGET", Path.of("sql/sync.sql"), Path.of("reports/sync.md"), exclusions);
    }
}
