package de.axa.oraclecompare;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Sichert das Remapping gegen Veränderungen an SQL-Inhalten und Bezeichnern ab. */
class SqlTextTest {
    @Test
    void findsLocalSequenceDefaultsWithExactQuotedNamesAndUnquotedCaseFolding() {
        assertEquals(Set.of("ID_SEQ", "Mixed Case", "A\"B"), SqlText.localSequenceReferences(
                "id_seq.nextval + source.id_seq.currval + \"SOURCE\".\"Mixed Case\".NEXTVAL "
                        + "+ \"A\"\"B\".CURRVAL + \"source\".WRONG_CASE.NEXTVAL + OTHER.OTHER_SEQ.NEXTVAL", "SOURCE"));
        assertEquals(Set.of("Seq"), SqlText.localSequenceReferences("\"Source\".\"Seq\".NEXTVAL", "Source"));
    }

    @Test
    void findsSequenceReferenceAcrossWhitespaceAndComments() {
        assertEquals(Set.of("ID_SEQ"), SqlText.localSequenceReferences(
                "SOURCE /* schema */ . ID_SEQ -- sequence\n . NEXTVAL", "SOURCE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'SOURCE.ID_SEQ.NEXTVAL'", "'it''s SOURCE.ID_SEQ.NEXTVAL'", "N'ID_SEQ.NEXTVAL'",
            "q'[SOURCE.ID_SEQ.NEXTVAL ' S.CURRVAL]'", "NQ'!S.NEXTVAL ' S.CURRVAL!'",
            "/* SOURCE.ID_SEQ.NEXTVAL */ 42 -- ID_SEQ.CURRVAL", "PACKAGE.SEQ.NEXTVAL",
            "SOURCE.PACKAGE.SEQ.NEXTVAL", "ID_SEQ.\"nextval\"", "SOURCE.ID_SEQ.NEXTVAL@REMOTE",
            "ID_SEQ.CURRVAL@SOURCE.EXAMPLE.COM", "\"SOURCE\".\"ID_SEQ\".NEXTVAL@\"R\"\"L\"",
            "SOURCE /* schema */ . ID_SEQ . NEXTVAL /* object */ @ /* link */ SOURCE . EXAMPLE . COM",
            "SOURCE.ID_SEQ@REMOTE.NEXTVAL"
    })
    void ignoresSequenceLikeLiteralsCommentsForeignSchemasAndRemoteReferences(String expression) {
        assertEquals(Set.of(), SqlText.localSequenceReferences(expression, "SOURCE"));
    }

    @Test
    void keepsLocalSequenceAfterRemoteReferenceAndIgnoresLinkLikeComments() {
        assertEquals(Set.of("LOCAL_SEQ"), SqlText.localSequenceReferences(
                "SOURCE.S.NEXTVAL@REMOTE.EXAMPLE.COM + SOURCE /* @REMOTE */ . LOCAL_SEQ.NEXTVAL", "SOURCE"));
        assertEquals(Set.of(), SqlText.localSequenceReferences(null, "SOURCE"));
    }

    @Test
    void quotesDictionaryNamesAndEscapesEmbeddedQuotes() {
        assertEquals("\"Sales\"\" Europe\"", SqlText.identifier("Sales\" Europe"));
        assertEquals("\"Sales\"\" Europe\".\"Order; DROP TABLE X --\"",
                SqlText.qualified("Sales\" Europe", "Order; DROP TABLE X --"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "A\nB", "A\rB", "A\u0000B", "A\u007fB"})
    void rejectsEmptyAndControlCharacterIdentifiers(String name) {
        assertThrows(IllegalArgumentException.class, () -> SqlText.identifier(name));
    }

    @Test
    void remapsOnlyTheExactQualifiedSchemaAndQuotesTheTarget() {
        String sql = "SELECT SRC.C, src.C, \"SRC\".C, \"src\".C, SRC2.C, SRC FROM SRC.T";
        assertEquals("SELECT \"Target\"\"Schema\".C, \"Target\"\"Schema\".C, "
                        + "\"Target\"\"Schema\".C, \"src\".C, SRC2.C, SRC FROM \"Target\"\"Schema\".T",
                SqlText.remap(sql, "SRC", "Target\"Schema"));
    }

    @Test
    void understandsEscapedQuotesInTheSourceIdentifier() {
        assertEquals("SELECT * FROM \"DST\".\"T\"",
                SqlText.remap("SELECT * FROM \"Src\"\"Name\".\"T\"", "Src\"Name", "DST"));
    }

    @Test
    void doesNotRemapTableOrPackageNamesInsideAnotherQualifiedName() {
        String sql = "SELECT OTHER.SRC.C, \"OTHER\" . /* qualifier */ \"SRC\".C, SRC.T.C FROM OTHER.SRC";
        assertEquals("SELECT OTHER.SRC.C, \"OTHER\" . /* qualifier */ \"SRC\".C, \"DST\".T.C FROM OTHER.SRC",
                SqlText.remap(sql, "SRC", "DST"));
    }

    @Test
    void preservesWhitespaceAndCommentsBetweenSchemaAndDot() {
        String sql = "SELECT * FROM SRC /* SRC.FAKE */ .T JOIN SRC -- SRC.FAKE\n .U ON 1=1";
        assertEquals("SELECT * FROM \"DST\" /* SRC.FAKE */ .T JOIN \"DST\" -- SRC.FAKE\n .U ON 1=1",
                SqlText.remap(sql, "SRC", "DST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "OTHER.T@SRC.EXAMPLE.COM", "OTHER.T@OTHER.SRC.COM", "SRC.T@REMOTE",
            "src.t@remote", "\"SRC\".\"T\"@\"REMOTE\"", "\"OTHER\".\"T\"@\"SRC\".\"EXAMPLE\".\"COM\"",
            "OTHER.T@\"SRC.EXAMPLE.COM\"", "SRC.PKG.FUNC@REMOTE()",
            "SRC /* schema */ . \"T\" /* object */ @ /* link */ SRC /* domain */ . EXAMPLE . COM",
            "SRC -- schema\n . T -- object\n @ -- link\n SRC -- domain\n . EXAMPLE . COM"
    })
    void preservesRemoteSchemasAndDatabaseLinkNames(String remoteReference) {
        assertEquals("SELECT \"DST\".LOCAL_VALUE FROM " + remoteReference + ", \"DST\".LOCAL_TABLE",
                SqlText.remap("SELECT SRC.LOCAL_VALUE FROM " + remoteReference + ", SRC.LOCAL_TABLE",
                        "SRC", "DST"));
    }

    @Test
    void preservesEscapedQuotedRemoteNamesWhileRemappingTheSameLocalSchema() {
        String sql = "SELECT * FROM \"Src\"\"Name\".\"T\"\"1\"@\"LINK\"\"1\", "
                + "OTHER.T@\"Src\"\"Name\".EXAMPLE.COM, \"Src\"\"Name\".\"Local\"";
        assertEquals("SELECT * FROM \"Src\"\"Name\".\"T\"\"1\"@\"LINK\"\"1\", "
                        + "OTHER.T@\"Src\"\"Name\".EXAMPLE.COM, \"DST\".\"Local\"",
                SqlText.remap(sql, "Src\"Name", "DST"));
    }

    @Test
    void doesNotTreatLinkMarkersInLiteralsOrCommentsAsRemoteReferences() {
        String sql = "SELECT '@SRC.EXAMPLE.COM', q'[SRC.T@REMOTE]', SRC.T.C "
                + "FROM SRC /* @REMOTE */ .T, SRC.U -- @REMOTE\n WHERE 1 = 1";
        assertEquals("SELECT '@SRC.EXAMPLE.COM', q'[SRC.T@REMOTE]', \"DST\".T.C "
                        + "FROM \"DST\" /* @REMOTE */ .T, \"DST\".U -- @REMOTE\n WHERE 1 = 1",
                SqlText.remap(sql, "SRC", "DST"));
    }

    @Test
    void preservesOrdinaryNationalAndEscapedStringLiteralsAndComments() {
        String sql = "SELECT 'SRC.T', 'it''s SRC.T', N'SRC.T', n'it''s SRC.T' FROM SRC.T "
                + "/* SRC.T */ -- SRC.T";
        assertEquals("SELECT 'SRC.T', 'it''s SRC.T', N'SRC.T', n'it''s SRC.T' FROM \"DST\".T "
                        + "/* SRC.T */ -- SRC.T",
                SqlText.remap(sql, "SRC", "DST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "q'[SRC.T ' SRC.U]'", "Q'(SRC.T ' SRC.U)'", "q'{SRC.T ' SRC.U}'",
            "q'<SRC.T ' SRC.U>'", "q'!SRC.T ' SRC.U!'", "q'\"SRC.T ' SRC.U\"'",
            "nq'[SRC.T ' SRC.U]'", "NQ'!SRC.T ' SRC.U!'"
    })
    void preservesAlternativeQuotedLiteralsIncludingNationalLiterals(String literal) {
        assertEquals("SELECT " + literal + " FROM \"DST\".T",
                SqlText.remap("SELECT " + literal + " FROM SRC.T", "SRC", "DST"));
    }

    @Test
    void leavesSqlUntouchedWhenSchemasAreEqual() {
        String sql = "SELECT 'SRC.T', SRC.C FROM SRC.T -- SRC.T";
        assertEquals(sql, SqlText.remap(sql, "SRC", "SRC"));
    }
}
