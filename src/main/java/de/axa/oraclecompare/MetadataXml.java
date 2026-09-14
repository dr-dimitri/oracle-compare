package de.axa.oraclecompare;

import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Namespace-bewusste Verarbeitung von Oracle-SXML und ALTER_XML. Externe XML-Zugriffe sind gesperrt. */
final class MetadataXml {
    private static final Set<String> SQL_FIELDS = Set.of("SUBQUERY", "QUERY", "DEFAULT", "CONDITION", "EXPRESSION", "EXPR", "TEXT");
    private static final String TRIVIA = "(?:\\s|/\\*[\\s\\S]*?\\*/|--[^\\r\\n]*(?:\\r?\\n|\\r|$))*";
    private static final String IDENTIFIER = "(?:\"(?:[^\"]|\"\")*\"|[\\p{L}_$#][\\p{L}\\p{N}_$#]*)";
    private static final Pattern CONSTRAINT_DROP = Pattern.compile(
            "\\A" + TRIVIA + "ALTER\\b" + TRIVIA + "TABLE\\b" + TRIVIA
                    + IDENTIFIER + TRIVIA + "(?:\\." + TRIVIA + IDENTIFIER + TRIVIA + ")?"
                    + "DROP\\b" + TRIVIA + "(?:CONSTRAINT\\b|PRIMARY\\b" + TRIVIA + "KEY\\b|UNIQUE\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private MetadataXml() { }

    record Difference(boolean hasStatements, String unsupportedReason) { }

    /** Null bedeutet, dass für diese Phase keine Statements vorhanden sind. */
    record AlterPhases(String constraintDropsXml, String remainingXml) { }

    /**
     * Trennt Constraint-Drops von den übrigen Tabellenoperationen. Oracle liefert jedes
     * Statement in einem eigenen SQL_LIST_ITEM; Semikolons in SQL-Literalen bleiben daher
     * unberührt. Beide Dokumente behalten die originale Hülle und werden mit ALTERDDL konvertiert.
     */
    static AlterPhases constraintPhases(String xml) throws SQLException {
        Document doc = parse(xml);
        if (!"ALTER_XML".equals(doc.getDocumentElement().getLocalName())) {
            throw new SQLException("Oracle lieferte kein ALTER_XML-Dokument.");
        }
        boolean hasDrops = false;
        boolean hasRemaining = false;
        var statements = doc.getElementsByTagNameNS("*", "SQL_LIST_ITEM");
        for (int i = 0; i < statements.getLength(); i++) {
            String sql = childText((Element) statements.item(i), "TEXT");
            if (sql.isBlank()) continue;
            if (isConstraintDrop(sql)) hasDrops = true;
            else hasRemaining = true;
        }
        if (!hasDrops) return new AlterPhases(null, hasRemaining ? xml : null);
        if (!hasRemaining) return new AlterPhases(xml, null);
        return new AlterPhases(filteredStatements(doc, true), filteredStatements(doc, false));
    }

    /** Nur den SQL-Präfix auswerten; quoted Tabellennamen und Kommentare sind erlaubt. */
    private static boolean isConstraintDrop(String sql) {
        return CONSTRAINT_DROP.matcher(sql).find();
    }

    private static String filteredStatements(Document original, boolean keepDrops) throws SQLException {
        Document copy = (Document) original.cloneNode(true);
        var statements = copy.getElementsByTagNameNS("*", "SQL_LIST_ITEM");
        for (int i = statements.getLength() - 1; i >= 0; i--) {
            Element statement = (Element) statements.item(i);
            String sql = childText(statement, "TEXT");
            if (sql.isBlank() || isConstraintDrop(sql) != keepDrops) statement.getParentNode().removeChild(statement);
        }
        var operations = copy.getElementsByTagNameNS("*", "ALTER_LIST_ITEM");
        for (int i = operations.getLength() - 1; i >= 0; i--) {
            Element operation = (Element) operations.item(i);
            if (operation.getElementsByTagNameNS("*", "SQL_LIST_ITEM").getLength() == 0) {
                operation.getParentNode().removeChild(operation);
            }
        }
        return serialize(copy);
    }

    /** Prüft auch NOT_ALTERABLE, damit Oracle-Warnungen keine unvollständigen Skripte erzeugen. */
    static Difference inspect(String xml) throws SQLException {
        Document doc = parse(xml);
        if (!"ALTER_XML".equals(doc.getDocumentElement().getLocalName())) {
            throw new SQLException("Oracle lieferte kein ALTER_XML-Dokument.");
        }
        var unsupported = doc.getElementsByTagNameNS("*", "NOT_ALTERABLE");
        String reason = unsupported.getLength() == 0 ? null : unsupported.item(0).getTextContent().strip();
        var items = doc.getElementsByTagNameNS("*", "PARSE_LIST_ITEM");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if ("NOT_ALTERABLE".equals(childText(item, "ITEM"))) reason = childText(item, "VALUE");
        }
        boolean hasSql = false;
        var statements = doc.getElementsByTagNameNS("*", "SQL_LIST_ITEM");
        for (int i = 0; i < statements.getLength(); i++) {
            String sql = childText((Element) statements.item(i), "TEXT");
            if (sql.matches("(?s).*?(?:--|/\\*)\\s*ORA-\\d{5}:.*")) reason = sql;
            hasSql |= !sql.isBlank();
        }
        return new Difference(hasSql, reason);
    }

    /** Remappt SQL in Blattknoten, insbesondere View-Abfragen und Spalten-Defaults. */
    static String remapEmbeddedSql(String xml, String source, String target) throws SQLException {
        if (source.equals(target)) return xml;
        Document doc = parse(xml);
        var elements = doc.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            boolean leaf = true;
            for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element) { leaf = false; break; }
            }
            if (leaf && SQL_FIELDS.contains(element.getLocalName())) {
                element.setTextContent(SqlText.remap(element.getTextContent(), source, target));
            }
        }
        return serialize(doc);
    }

    /** NEXTVAL-Verbrauch ist Datenzustand, keine zu migrierende Sequenzdefinition. */
    static String withoutSequencePosition(String xml) throws SQLException {
        Document doc = parse(xml);
        Element sequence = doc.getDocumentElement();
        if (!"SEQUENCE".equals(sequence.getLocalName())) throw new SQLException("Kein SEQUENCE-SXML erhalten.");
        for (Node child = sequence.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if (child instanceof Element && "START_WITH".equals(child.getLocalName())) sequence.removeChild(child);
            child = next;
        }
        return serialize(doc);
    }

    private static String serialize(Document doc) throws SQLException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            StringWriter result = new StringWriter();
            factory.newTransformer().transform(new DOMSource(doc), new StreamResult(result));
            return result.toString();
        } catch (Exception e) {
            throw new SQLException("SXML konnte nicht serialisiert werden.", e);
        }
    }

    private static String childText(Element element, String name) {
        var nodes = element.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private static Document parse(String xml) throws SQLException {
        if (xml == null || xml.isBlank()) throw new SQLException("Oracle lieferte leere XML-Metadaten.");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new SQLException("Ungültiges Oracle-Metadaten-XML.", e);
        }
    }
}
