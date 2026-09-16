package de.axa.oraclecompare;

import java.util.EnumMap;
import java.util.Map;

import static de.axa.oraclecompare.ComparisonPlan.*;

/** Erstellt einen lesbaren Änderungsbericht aus genau dem Plan, der auch das SQL liefert. */
final class MarkdownReportRenderer {
    /**
     * Dokumentiert alle geplanten Schritte in Ausführungsreihenfolge, einschließlich Hilfs-DDL.
     * Ein SQL-Block bleibt eine Einheit; insbesondere werden Semikolons in Literalen oder
     * PL/SQL nicht als Trennzeichen interpretiert. Die Methode führt keine Datenbankaktion aus.
     */
    String render(ComparisonPlan plan, CompareConfiguration configuration) {
        StringBuilder report = new StringBuilder("# Schemaabgleich: Änderungsbericht\n\n")
                .append("Dieser Bericht beschreibt **geplante Änderungen** des erzeugten SQL-Skripts. ")
                .append("Die Anwendung hat das Skript nicht ausgeführt.\n\n")
                .append("| Einstellung | Wert |\n| --- | --- |\n")
                .append("| Referenzschema | ").append(code(plan.referenceSchema())).append(" |\n")
                .append("| Zielschema | ").append(code(plan.targetSchema())).append(" |\n")
                .append("| SQL-Datei | ").append(code(configuration.outputFile().toAbsolutePath().normalize().toString())).append(" |\n")
                .append("| Berichtsdatei | ").append(code(configuration.reportFile().toAbsolutePath().normalize().toString())).append(" |\n\n")
                .append("## Ausschlussmuster\n\n");
        if (configuration.excludedObjects().isEmpty()) {
            report.append("Keine Ausschlussmuster konfiguriert.\n\n");
        } else {
            report.append("Die Muster gelten für beide Schemata ohne Beachtung der Groß-/Kleinschreibung.\n\n");
            for (String pattern : configuration.excludedObjects()) report.append("- ").append(code(pattern)).append('\n');
            report.append('\n');
        }
        report.append("## Zusammenfassung\n\n");
        if (plan.operations().isEmpty()) {
            return report.append("Keine Unterschiede im berücksichtigten Objektumfang gefunden. ")
                    .append("Es sind keine Objektänderungen geplant.\n").toString();
        }
        Map<Action, Integer> counts = new EnumMap<>(Action.class);
        for (Operation operation : plan.operations()) counts.merge(operation.action(), 1, Integer::sum);
        report.append("**").append(plan.operations().size())
                .append(plan.operations().size() == 1 ? " geplanter Schritt.** " : " geplante Schritte.** ")
                .append("Gezählt werden SQL-Operationen, keine unterschiedlichen Objekte. ")
                .append("Auch das vorübergehende Entfernen und Wiederanlegen von Constraints oder Indizes ")
                .append("sowie View-Kompilierungen und Prüfungen sind enthalten. ")
                .append("Session-Einstellungen und SQL*Plus-Anweisungen werden nicht mitgezählt.\n\n")
                .append("| Aktion | Anzahl |\n| --- | ---: |\n");
        for (Action action : Action.values()) {
            report.append("| ").append(actionLabel(action)).append(" | ").append(counts.getOrDefault(action, 0)).append(" |\n");
        }
        report.append("\n## Ausführungsreihenfolge\n\n")
                .append("| Schritt | Aktion | Objekttyp | Zielobjekt | Zugehörige Tabelle |\n")
                .append("| ---: | --- | --- | --- | --- |\n");
        int number = 0;
        for (Operation operation : plan.operations()) {
            report.append("| ").append(++number).append(" | ").append(actionLabel(operation.action()))
                    .append(" | ").append(typeLabel(operation.objectType()))
                    .append(" | ").append(code(objectName(plan, operation)))
                    .append(" | ").append(operation.tableName() == null ? "–" : code(SqlText.qualified(plan.targetSchema(), operation.tableName())))
                    .append(" |\n");
        }
        report.append("\n## SQL je Schritt\n\n")
                .append("Für die Ausführung die vollständige SQL-Datei verwenden; sie enthält zusätzlich ")
                .append("die erforderlichen SQL*Plus-/SQLcl- und Session-Einstellungen.\n\n");
        number = 0;
        for (Operation operation : plan.operations()) {
            report.append("### ").append(++number).append(". ").append(actionLabel(operation.action()))
                    .append(": ").append(typeLabel(operation.objectType())).append(' ')
                    .append(code(objectName(plan, operation))).append("\n\n");
            appendSql(report, operation.sql());
        }
        return report.toString();
    }

    /** Schemaprüfungen beziehen sich auf das Schema selbst, alle anderen Schritte auf Zielobjekte. */
    private static String objectName(ComparisonPlan plan, Operation operation) {
        return operation.objectType() == ObjectType.SCHEMA ? SqlText.identifier(operation.objectName())
                : SqlText.qualified(plan.targetSchema(), operation.objectName());
    }

    private static String actionLabel(Action action) {
        return switch (action) {
            case CREATE -> "Anlegen";
            case ALTER -> "Ändern";
            case DROP -> "Entfernen";
            case COMPILE -> "Kompilieren";
            case VALIDATE -> "Prüfen";
        };
    }

    private static String typeLabel(ObjectType type) {
        return switch (type) {
            case TABLE -> "Tabelle";
            case INDEX -> "Index";
            case VIEW -> "View";
            case SEQUENCE -> "Sequenz";
            case CONSTRAINT -> "Constraint";
            case SCHEMA -> "Schema";
        };
    }

    /** Maskiert auch Markdown-Steuerzeichen und Zeilenumbrüche in gequoteten Oracle-Namen/Pfaden. */
    private static String code(String value) {
        StringBuilder escaped = new StringBuilder("<code>");
        value.codePoints().forEach(character -> {
            if ("&<>\"'|`\\*_[]!~:@\r\n\t".indexOf(character) >= 0) {
                escaped.append("&#").append(character).append(';');
            } else {
                escaped.appendCodePoint(character);
            }
        });
        return escaped.append("</code>").toString();
    }

    /** Eine längere Fence verhindert, dass Backticks innerhalb des SQL den Codeblock schließen. */
    private static void appendSql(StringBuilder report, String sql) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < sql.length(); i++) {
            run = sql.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        report.append(fence).append("sql\n").append(sql.strip()).append('\n').append(fence).append("\n\n");
    }
}
