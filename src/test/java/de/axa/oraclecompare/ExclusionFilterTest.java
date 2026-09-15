package de.axa.oraclecompare;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüft die Namensfilter unabhängig von JDBC und der Abgleichsplanung. */
class ExclusionFilterTest {
    @Test
    void emptyFilterExcludesNothing() {
        ExclusionFilter filter = ExclusionFilter.none();
        assertTrue(filter.isEmpty());
        assertFalse(filter.excludes("ANY_TABLE"));
    }

    @Test
    void matchesOnlyCompleteNamesIgnoringCaseAndPreservingWhitespace() {
        ExclusionFilter filter = new ExclusionFilter(List.of("Orders", " With Space "));
        assertFalse(filter.isEmpty());
        assertTrue(filter.excludes("Orders"));
        assertTrue(filter.excludes("ORDERS"));
        assertTrue(filter.excludes("orders"));
        assertFalse(filter.excludes("OldOrders"));
        assertFalse(filter.excludes("OrdersArchive"));
        assertTrue(filter.excludes(" With Space "));
        assertFalse(filter.excludes("With Space"));
    }

    @ParameterizedTest
    @CsvSource({"TMP_*, TMP_, true", "TMP_*, TMP_ORDERS, true", "TMP_*, XTMP_ORDERS, false",
            "*LOG*, LOG, true", "*LOG*, ORDER_LOG_ARCHIVE, true", "*LOG*, ORDERS, false",
            "T?, T1, true", "T?, T, false", "T?, T12, false", "A**?*Z, ABZ, true",
            "A**?*Z, AZ, false", "*AB*CD, AABABCCD, true", "*AB*CD, ABCDE, false"})
    void matchesWildcards(String pattern, String name, boolean expected) {
        if (expected) assertTrue(new ExclusionFilter(List.of(pattern)).excludes(name));
        else assertFalse(new ExclusionFilter(List.of(pattern)).excludes(name));
    }

    @Test
    void treatsRegexAndSqlLikeCharactersLiterally() {
        ExclusionFilter filter = new ExclusionFilter(List.of("A.[B](C){2}+^$|_%"));
        assertTrue(filter.excludes("A.[B](C){2}+^$|_%"));
        assertFalse(filter.excludes("AxBCCanything"));
        assertFalse(new ExclusionFilter(List.of("TMP_%")).excludes("TMP_ORDERS"));
    }

    @Test
    void escapesWildcardsBackslashesAndOrdinaryCharacters() {
        ExclusionFilter filter = new ExclusionFilter(List.of("A\\*B", "Q\\?", "PATH\\\\END", "\\Orders"));
        assertTrue(filter.excludes("A*B"));
        assertFalse(filter.excludes("AXYZB"));
        assertTrue(filter.excludes("Q?"));
        assertFalse(filter.excludes("Q1"));
        assertTrue(filter.excludes("PATH\\END"));
        assertTrue(filter.excludes("Orders"));
        assertFalse(filter.excludes("\\Orders"));
    }

    @Test
    void combinesEscapedAndUnescapedWildcards() {
        ExclusionFilter filter = new ExclusionFilter(List.of("*\\**\\??"));
        assertTrue(filter.excludes("BEFORE*AFTER?X"));
        assertTrue(filter.excludes("*?X"));
        assertFalse(filter.excludes("BEFORE*AFTER?"));
        assertFalse(filter.excludes("BEFOREAFTER?X"));
    }

    @Test
    void questionMarkMatchesOneUnicodeCodePoint() {
        ExclusionFilter filter = new ExclusionFilter(List.of("T?"));
        assertTrue(filter.excludes("TÄ"));
        assertTrue(filter.excludes("T\uD83D\uDE80"));
        assertFalse(filter.excludes("T\uD83D\uDE80X"));
        assertTrue(new ExclusionFilter(List.of("\uD83D\uDE80*")).excludes("\uD83D\uDE80Änderungen"));
    }

    @Test
    void ignoresUnicodeCaseForLiteralAndEscapedCharacters() {
        ExclusionFilter filter = new ExclusionFilter(List.of("Änderung*", "\\Ö?", "Σ", "\uD801\uDC00"));
        assertTrue(filter.excludes("änderungen"));
        assertTrue(filter.excludes("ö1"));
        assertTrue(filter.excludes("σ"));
        assertTrue(filter.excludes("ς"));
        assertTrue(filter.excludes("\uD801\uDC28"));
        assertFalse(new ExclusionFilter(List.of("STRASSE")).excludes("Straße"));
    }

    @Test
    void combinesAllPatternsWithOr() {
        ExclusionFilter filter = new ExclusionFilter(List.of("FIRST", "TMP_*", "*_ARCHIVE"));
        assertTrue(filter.excludes("FIRST"));
        assertTrue(filter.excludes("TMP_ORDERS"));
        assertTrue(filter.excludes("ORDERS_ARCHIVE"));
        assertFalse(filter.excludes("ORDERS"));
    }

    @Test
    void snapshotsTheInputList() {
        List<String> patterns = new ArrayList<>(List.of("OLD"));
        ExclusionFilter filter = new ExclusionFilter(patterns);
        patterns.set(0, "NEW");
        patterns.clear();
        assertTrue(filter.excludes("OLD"));
        assertFalse(filter.excludes("NEW"));
        assertFalse(filter.isEmpty());
    }

    @Test
    void rejectsNullList() {
        assertThrows(IllegalArgumentException.class, () -> new ExclusionFilter(null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\u2003", "A\nB", "A\rB", "A\u0000B", "A\u007fB",
            "A\u0085B", "TRAILING\\", "\\"})
    void rejectsInvalidPatterns(String pattern) {
        assertThrows(IllegalArgumentException.class, () -> new ExclusionFilter(Arrays.asList(pattern)));
    }

    @Test
    void rejectsNullObjectName() {
        assertThrows(IllegalArgumentException.class, () -> ExclusionFilter.none().excludes(null));
    }

    @Test
    void repeatedWildcardsDoNotCauseExponentialBacktracking() {
        ExclusionFilter filter = new ExclusionFilter(List.of("*A".repeat(100) + "B"));
        assertTimeout(Duration.ofSeconds(1), () -> assertFalse(filter.excludes("A".repeat(128))));
    }
}
