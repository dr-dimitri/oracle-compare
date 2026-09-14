package de.axa.oraclecompare;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüft Oracle-XML unabhängig von Namespace-Präfixen und sperrt externe Entitäten. */
class MetadataXmlTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "<ALTER_XML><SQL_LIST><SQL_LIST_ITEM><TEXT>ALTER TABLE T ADD C NUMBER</TEXT></SQL_LIST_ITEM></SQL_LIST></ALTER_XML>",
            "<ALTER_XML xmlns='http://xmlns.oracle.com/ku'><SQL_LIST><SQL_LIST_ITEM><TEXT>ALTER TABLE T ADD C NUMBER</TEXT></SQL_LIST_ITEM></SQL_LIST></ALTER_XML>",
            "<m:ALTER_XML xmlns:m='http://xmlns.oracle.com/ku'><m:SQL_LIST><m:SQL_LIST_ITEM><m:TEXT>ALTER TABLE T ADD C NUMBER</m:TEXT></m:SQL_LIST_ITEM></m:SQL_LIST></m:ALTER_XML>"
    })
    void recognizesStatementsWithDefaultPrefixedOrNoNamespace(String xml) throws SQLException {
        MetadataXml.Difference difference = MetadataXml.inspect(xml);
        assertTrue(difference.hasStatements());
        assertNull(difference.unsupportedReason());
    }

    @Test
    void examinesAllSqlListItemsEvenWhenTheFirstIsBlank() throws SQLException {
        var difference = MetadataXml.inspect("""
                <ALTER_XML xmlns="http://xmlns.oracle.com/ku"><SQL_LIST>
                  <SQL_LIST_ITEM><TEXT>  </TEXT></SQL_LIST_ITEM>
                  <SQL_LIST_ITEM><TEXT>ALTER TABLE T ADD C NUMBER</TEXT></SQL_LIST_ITEM>
                  <SQL_LIST_ITEM><TEXT>ALTER TABLE T MODIFY D NOT NULL</TEXT></SQL_LIST_ITEM>
                </SQL_LIST></ALTER_XML>
                """);
        assertTrue(difference.hasStatements());
        assertNull(difference.unsupportedReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<ALTER_XML/>", "<ALTER_XML><SQL_LIST_ITEM><TEXT> </TEXT></SQL_LIST_ITEM></ALTER_XML>"})
    void treatsEmptySqlListsAsNoChange(String xml) throws SQLException {
        var difference = MetadataXml.inspect(xml);
        assertFalse(difference.hasStatements());
        assertNull(difference.unsupportedReason());
    }

    @Test
    void reportsNotAlterableEvenWhenOtherChangesHaveSql() throws SQLException {
        var difference = MetadataXml.inspect("""
                <m:ALTER_XML xmlns:m="http://xmlns.oracle.com/ku">
                  <m:SQL_LIST_ITEM><m:TEXT>ALTER TABLE T ADD C NUMBER</m:TEXT></m:SQL_LIST_ITEM>
                  <m:NOT_ALTERABLE>  unsupported column conversion  </m:NOT_ALTERABLE>
                </m:ALTER_XML>
                """);
        assertTrue(difference.hasStatements());
        assertEquals("unsupported column conversion", difference.unsupportedReason());
    }

    @Test
    void readsNotAlterableFromOracleParseItems() throws SQLException {
        var difference = MetadataXml.inspect("""
                <ALTER_XML xmlns="http://xmlns.oracle.com/ku"><PARSE_LIST>
                  <PARSE_LIST_ITEM><ITEM>OBJECT_NAME</ITEM><VALUE>T</VALUE></PARSE_LIST_ITEM>
                  <PARSE_LIST_ITEM><ITEM> NOT_ALTERABLE </ITEM><VALUE> cannot change type </VALUE></PARSE_LIST_ITEM>
                </PARSE_LIST></ALTER_XML>
                """);
        assertFalse(difference.hasStatements());
        assertEquals("cannot change type", difference.unsupportedReason());
    }

    @Test
    void retainsAnEmptyNotAlterableMarkerAsUnsupported() throws SQLException {
        assertNotNull(MetadataXml.inspect("<ALTER_XML><NOT_ALTERABLE/></ALTER_XML>")
                .unsupportedReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-- ORA-39127: unexpected error from call to metadata API",
            "/* ORA-39273: cannot alter the requested property */",
            "ALTER TABLE T ADD C NUMBER;\n-- ORA-39273: cannot alter the requested property"
    })
    void treatsOracleErrorCommentsAsUnsupportedInsteadOfSuccessfulDdl(String sql) throws SQLException {
        var difference = MetadataXml.inspect("<ALTER_XML><SQL_LIST_ITEM><TEXT><![CDATA["
                + sql + "]]></TEXT></SQL_LIST_ITEM></ALTER_XML>");
        assertEquals(sql, difference.unsupportedReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "default", "prefixed"})
    void preservesOracleDocumentEnvelopeAndParseItemsInBothConstraintPhases(String namespace) throws Exception {
        String prefix = namespace.equals("prefixed") ? "m:" : "";
        String declaration = switch (namespace) {
            case "default" -> "xmlns='http://xmlns.oracle.com/ku'";
            case "prefixed" -> "xmlns:m='http://xmlns.oracle.com/ku'";
            default -> "";
        };
        String xml = """
                <%1$sALTER_XML %2$s version="1.0">
                  <%1$sOBJECT_TYPE>TABLE</%1$sOBJECT_TYPE>
                  <%1$sOBJECT1><%1$sSCHEMA>DST</%1$sSCHEMA><%1$sNAME>T</%1$sNAME></%1$sOBJECT1>
                  <%1$sOBJECT2><%1$sSCHEMA>DST</%1$sSCHEMA><%1$sNAME>T</%1$sNAME></%1$sOBJECT2>
                  <%1$sALTER_LIST><%1$sALTER_LIST_ITEM>
                    <%1$sPARSE_LIST><%1$sPARSE_LIST_ITEM>
                      <%1$sITEM>NAME</%1$sITEM><%1$sVALUE>CK_VALUE</%1$sVALUE>
                    </%1$sPARSE_LIST_ITEM></%1$sPARSE_LIST>
                    <%1$sSQL_LIST>
                      <%1$sSQL_LIST_ITEM><%1$sTEXT>ALTER TABLE "DST"."T" DROP CONSTRAINT "CK_VALUE"</%1$sTEXT></%1$sSQL_LIST_ITEM>
                      <%1$sSQL_LIST_ITEM><%1$sTEXT>ALTER TABLE "DST"."T" ADD CONSTRAINT "CK_VALUE" CHECK (C &gt; 0)</%1$sTEXT></%1$sSQL_LIST_ITEM>
                    </%1$sSQL_LIST>
                  </%1$sALTER_LIST_ITEM></%1$sALTER_LIST>
                </%1$sALTER_XML>
                """.formatted(prefix, declaration);

        var phases = MetadataXml.constraintPhases(xml);
        for (String phase : List.of(phases.constraintDropsXml(), phases.remainingXml())) {
            Document doc = parseTrustedResult(phase);
            assertEquals("ALTER_XML", doc.getDocumentElement().getLocalName());
            assertEquals(namespace.equals("none") ? null : "http://xmlns.oracle.com/ku",
                    doc.getDocumentElement().getNamespaceURI());
            assertEquals(namespace.equals("prefixed") ? "m" : null, doc.getDocumentElement().getPrefix());
            assertEquals("1.0", doc.getDocumentElement().getAttribute("version"));
            assertEquals("TABLE", text(doc, "OBJECT_TYPE"));
            assertEquals("DSTT", text(doc, "OBJECT1"));
            assertEquals("DSTT", text(doc, "OBJECT2"));
            assertEquals("NAME", text(doc, "ITEM"));
            assertEquals("CK_VALUE", text(doc, "VALUE"));
            assertEquals(1, doc.getElementsByTagNameNS("*", "ALTER_LIST_ITEM").getLength());
            assertEquals(1, doc.getElementsByTagNameNS("*", "SQL_LIST_ITEM").getLength());
        }
        assertEquals(List.of("ALTER TABLE \"DST\".\"T\" DROP CONSTRAINT \"CK_VALUE\""),
                statements(phases.constraintDropsXml()));
        assertEquals(List.of("ALTER TABLE \"DST\".\"T\" ADD CONSTRAINT \"CK_VALUE\" CHECK (C > 0)"),
                statements(phases.remainingXml()));
    }

    @Test
    void keepsStatementOrderAndLiteralSemicolonsWhileRemovingEmptyOperationGroups() throws Exception {
        String firstDrop = "ALTER TABLE T DROP CONSTRAINT CK_OLD";
        String secondDrop = "ALTER TABLE T DROP PRIMARY KEY KEEP INDEX";
        String add = "ALTER TABLE T ADD CONSTRAINT CK_NEW CHECK (C <> '; DROP CONSTRAINT X;')";
        String modify = "ALTER TABLE T MODIFY (D DEFAULT q'[a;b;ALTER TABLE T DROP CONSTRAINT C;]')";
        String xml = "<ALTER_XML><ALTER_LIST>"
                + operation(sqlItem(firstDrop))
                + operation(sqlItem(add) + sqlItem("  ") + sqlItem(secondDrop) + sqlItem(modify))
                + operation(sqlItem("\n"))
                + "</ALTER_LIST></ALTER_XML>";

        var phases = MetadataXml.constraintPhases(xml);

        assertEquals(List.of(firstDrop, secondDrop), statements(phases.constraintDropsXml()));
        assertEquals(List.of(add, modify), statements(phases.remainingXml()));
        assertEquals(2, parseTrustedResult(phases.constraintDropsXml())
                .getElementsByTagNameNS("*", "ALTER_LIST_ITEM").getLength());
        assertEquals(1, parseTrustedResult(phases.remainingXml())
                .getElementsByTagNameNS("*", "ALTER_LIST_ITEM").getLength());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE T DROP CONSTRAINT CK_OLD",
            "ALTER TABLE DST.T DROP PRIMARY KEY CASCADE KEEP INDEX",
            "ALTER TABLE T DROP UNIQUE (C, D) KEEP INDEX",
            "alter table dst.t drop constraint ck_old",
            "ALTER TABLE \"schema.with.dot\".\"table\"\"name\" DROP CONSTRAINT \"CK with spaces\"",
            "-- exported constraint\r\nALTER /* operation */ TABLE DST /* schema */ . T DROP /* old */ PRIMARY -- key\n KEY",
            "/* leading comment */ ALTER TABLE \"DROP CONSTRAINT\" DROP UNIQUE (\"A.B\")"
    })
    void schedulesRealConstraintDropsInTheEarlyPhase(String sql) throws Exception {
        var phases = MetadataXml.constraintPhases("<ALTER_XML><ALTER_LIST>"
                + operation(sqlItem(sql)) + "</ALTER_LIST></ALTER_XML>");
        // XML normalisiert CRLF bereits beim Parsen zu LF.
        assertEquals(List.of(sql.replace("\r\n", "\n")), statements(phases.constraintDropsXml()));
        assertNull(phases.remainingXml());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE T DROP COLUMN C",
            "ALTER TABLE T DROP (C, D)",
            "ALTER TABLE T RENAME CONSTRAINT OLD_NAME TO NEW_NAME",
            "ALTER TABLE T MODIFY (C DEFAULT 'ALTER TABLE T DROP CONSTRAINT CK;')",
            "ALTER TABLE T ADD CONSTRAINT CK CHECK (C <> 'DROP CONSTRAINT')",
            "/* ALTER TABLE T DROP CONSTRAINT CK */ ALTER TABLE T ADD (C NUMBER)",
            "ALTER TABLE \"DROP CONSTRAINT\" ADD (C NUMBER)"
    })
    void keepsOtherOperationsOutOfTheConstraintDropPhase(String sql) throws Exception {
        var phases = MetadataXml.constraintPhases("<ALTER_XML><ALTER_LIST>"
                + operation(sqlItem(sql)) + "</ALTER_LIST></ALTER_XML>");
        assertNull(phases.constraintDropsXml());
        assertEquals(List.of(sql), statements(phases.remainingXml()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<ALTER_XML/>", "<ALTER_XML><ALTER_LIST/></ALTER_XML>",
            "<ALTER_XML><ALTER_LIST><ALTER_LIST_ITEM><SQL_LIST><SQL_LIST_ITEM><TEXT>  </TEXT></SQL_LIST_ITEM></SQL_LIST></ALTER_LIST_ITEM></ALTER_LIST></ALTER_XML>"})
    void returnsNoConstraintPhasesWhenNoStatementsExist(String xml) throws SQLException {
        var phases = MetadataXml.constraintPhases(xml);
        assertNull(phases.constraintDropsXml());
        assertNull(phases.remainingXml());
    }

    private static String operation(String statements) {
        return "<ALTER_LIST_ITEM><SQL_LIST>" + statements + "</SQL_LIST></ALTER_LIST_ITEM>";
    }

    private static String sqlItem(String sql) {
        return "<SQL_LIST_ITEM><TEXT><![CDATA[" + sql + "]]></TEXT></SQL_LIST_ITEM>";
    }

    private static List<String> statements(String xml) throws Exception {
        Document doc = parseTrustedResult(xml);
        List<String> statements = new ArrayList<>();
        var nodes = doc.getElementsByTagNameNS("*", "TEXT");
        for (int i = 0; i < nodes.getLength(); i++) statements.add(nodes.item(i).getTextContent());
        return statements;
    }

    @Test
    void remapsEmbeddedSqlAndPreservesXmlNamespacesAndLiteralContent() throws Exception {
        String result = MetadataXml.remapEmbeddedSql("""
                <m:VIEW xmlns:m="http://xmlns.oracle.com/ku">
                  <m:NAME>V</m:NAME>
                  <m:QUERY><![CDATA[SELECT 'SRC.T & text', q'[SRC.T ' SRC.U]', SRC.C FROM SRC.T /* SRC.U */ WHERE SRC.C < 5]]></m:QUERY>
                  <m:COLUMN><m:DEFAULT>SRC.SEQ.NEXTVAL</m:DEFAULT></m:COLUMN>
                </m:VIEW>
                """, "SRC", "DST");
        Document doc = parseTrustedResult(result);
        assertEquals("http://xmlns.oracle.com/ku", doc.getDocumentElement().getNamespaceURI());
        assertEquals("V", text(doc, "NAME"));
        assertEquals("SELECT 'SRC.T & text', q'[SRC.T ' SRC.U]', \"DST\".C FROM \"DST\".T /* SRC.U */ WHERE \"DST\".C < 5",
                text(doc, "QUERY"));
        assertEquals("\"DST\".SEQ.NEXTVAL", text(doc, "DEFAULT"));
    }

    @Test
    void remapsViewSubqueriesWithoutChangingDictionaryNamesContainingDots() throws Exception {
        String result = MetadataXml.remapEmbeddedSql("""
                <m:VIEW xmlns:m="http://xmlns.oracle.com/ku">
                  <m:NAME>SRC.part</m:NAME>
                  <m:SUBQUERY><![CDATA[SELECT SRC.C, 'SRC.part' FROM SRC.T]]></m:SUBQUERY>
                  <m:COLUMN_LIST><m:COLUMN_LIST_ITEM><m:NAME>SRC.column</m:NAME></m:COLUMN_LIST_ITEM></m:COLUMN_LIST>
                </m:VIEW>
                """, "SRC", "DST");
        Document doc = parseTrustedResult(result);
        var names = doc.getElementsByTagNameNS("*", "NAME");
        assertEquals("SRC.part", names.item(0).getTextContent());
        assertEquals("SRC.column", names.item(1).getTextContent());
        assertEquals("SELECT \"DST\".C, 'SRC.part' FROM \"DST\".T", text(doc, "SUBQUERY"));
    }

    @Test
    void removesOnlyTheSequenceStartPositionAndPreservesDefinitionAndXmlAttributes() throws Exception {
        String result = MetadataXml.withoutSequencePosition("""
                <m:SEQUENCE xmlns:m="http://xmlns.oracle.com/ku" version="1.0">
                  <m:SCHEMA>SRC</m:SCHEMA><m:NAME>SEQ</m:NAME><m:START_WITH>1042</m:START_WITH>
                  <m:INCREMENT_BY unit="number">5</m:INCREMENT_BY>
                  <m:MINVALUE>1</m:MINVALUE><m:MAXVALUE>999999</m:MAXVALUE>
                  <m:CACHE_SIZE>20</m:CACHE_SIZE><m:CYCLE_FLAG>N</m:CYCLE_FLAG><m:ORDER_FLAG>Y</m:ORDER_FLAG>
                  <m:EXTENSION><m:START_WITH retained="yes">other-context</m:START_WITH></m:EXTENSION>
                </m:SEQUENCE>
                """);
        Document doc = parseTrustedResult(result);
        assertEquals("http://xmlns.oracle.com/ku", doc.getDocumentElement().getNamespaceURI());
        assertEquals("1.0", doc.getDocumentElement().getAttribute("version"));
        var starts = doc.getElementsByTagNameNS("*", "START_WITH");
        assertEquals(1, starts.getLength(), "Ein gleichnamiges Feld in anderem Kontext darf nicht entfernt werden.");
        assertEquals("EXTENSION", starts.item(0).getParentNode().getLocalName());
        assertEquals("other-context", starts.item(0).getTextContent());
        assertEquals("SRC", text(doc, "SCHEMA"));
        assertEquals("SEQ", text(doc, "NAME"));
        assertEquals("5", text(doc, "INCREMENT_BY"));
        assertEquals("number", doc.getElementsByTagNameNS("*", "INCREMENT_BY").item(0)
                .getAttributes().getNamedItem("unit").getNodeValue());
        assertEquals("1", text(doc, "MINVALUE"));
        assertEquals("999999", text(doc, "MAXVALUE"));
        assertEquals("20", text(doc, "CACHE_SIZE"));
        assertEquals("N", text(doc, "CYCLE_FLAG"));
        assertEquals("Y", text(doc, "ORDER_FLAG"));
    }

    @Test
    void acceptsSequencesWithoutStartPositionAndRejectsOtherObjectTypes() throws Exception {
        Document sequence = parseTrustedResult(MetadataXml.withoutSequencePosition(
                "<SEQUENCE><NAME>SEQ</NAME><INCREMENT_BY>1</INCREMENT_BY></SEQUENCE>"));
        assertEquals("SEQ", text(sequence, "NAME"));
        assertEquals("1", text(sequence, "INCREMENT_BY"));
        assertThrows(SQLException.class, () -> MetadataXml.withoutSequencePosition(
                "<TABLE><START_WITH>100</START_WITH></TABLE>"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "<ALTER_XML>", "<TABLE/>", "<ROOT><ALTER_XML/></ROOT>"})
    void rejectsMissingMalformedAndUnexpectedMetadata(String xml) {
        assertThrows(SQLException.class, () -> MetadataXml.inspect(xml));
    }

    @Test
    void rejectsDoctypeAndExternalEntitiesInBothXmlEntryPoints(@TempDir Path directory) throws Exception {
        Path entity = Files.writeString(directory.resolve("entity.txt"), "ALTER TABLE T ADD C NUMBER");
        String xml = """
                <!DOCTYPE ALTER_XML [<!ENTITY external SYSTEM "%s">]>
                <ALTER_XML><SQL_LIST_ITEM><TEXT>&external;</TEXT></SQL_LIST_ITEM></ALTER_XML>
                """.formatted(entity.toUri());
        assertThrows(SQLException.class, () -> MetadataXml.inspect(xml));
        assertThrows(SQLException.class, () -> MetadataXml.remapEmbeddedSql(xml, "SRC", "DST"));
    }

    private static Document parseTrustedResult(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String text(Document document, String localName) {
        return document.getElementsByTagNameNS("*", localName).item(0).getTextContent();
    }
}
