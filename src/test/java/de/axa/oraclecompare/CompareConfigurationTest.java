package de.axa.oraclecompare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft die Dateikonfiguration einschließlich Properties-Maskierung und unveränderlicher Filter. */
class CompareConfigurationTest {
    @TempDir Path directory;

    @Test
    void loadsUtf8SchemaNamesPathsAndNumberedPatternsWithoutSplittingNames() throws Exception {
        Path file = Files.writeString(directory.resolve("oracle-compare.properties"), """
                reference.schema=Quelle Ä
                target.schema=Ziel
                output.path=sql/Abgleich ü.sql
                report.path=reports/Bericht ü.md
                exclude.10=REPORT\\\\*
                exclude.2=Name, mit Komma
                exclude.1=tmp_*
                """);

        CompareConfiguration config = CompareConfiguration.load(file);

        assertEquals("Quelle Ä", config.referenceSchema());
        assertEquals("Ziel", config.targetSchema());
        assertEquals(Path.of("sql/Abgleich ü.sql"), config.outputFile());
        assertEquals(Path.of("reports/Bericht ü.md"), config.reportFile());
        assertEquals(List.of("tmp_*", "Name, mit Komma", "REPORT\\*"), config.excludedObjects());
        ExclusionFilter filter = new ExclusionFilter(config.excludedObjects());
        assertTrue(filter.excludes("TMP_DATA"));
        assertTrue(filter.excludes("REPORT*"));
        assertFalse(filter.excludes("REPORT_A"));
        assertThrows(UnsupportedOperationException.class, () -> config.excludedObjects().add("*"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reference.schema", "target.schema", "output.path"})
    void rejectsMissingRequiredValues(String name) {
        Properties properties = valid();
        properties.remove(name);
        IOException failure = assertThrows(IOException.class, () -> CompareConfiguration.from(properties));
        assertTrue(failure.getMessage().contains(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"exclude.0", "exclude.-1", "exclude.foo", "output.charset", "jdbc.password"})
    void rejectsUnknownOrMisspelledSettings(String key) {
        Properties properties = valid();
        properties.setProperty(key, "value");
        assertThrows(IOException.class, () -> CompareConfiguration.from(properties));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "NAME\\"})
    void rejectsInvalidExclusionBeforeConnecting(String pattern) {
        Properties properties = valid();
        properties.setProperty("exclude.1", pattern);
        assertThrows(IOException.class, () -> CompareConfiguration.from(properties));
    }

    @Test
    void reportsMissingAndMalformedFiles() throws Exception {
        assertThrows(IOException.class, () -> CompareConfiguration.load(directory.resolve("missing.properties")));
        Path file = Files.writeString(directory.resolve("malformed.properties"), "reference.schema=\\uXXXX");
        assertThrows(IOException.class, () -> CompareConfiguration.load(file));
    }

    @Test
    void defaultsReportPathNextToSqlForExistingConfiguration() throws Exception {
        assertEquals(Path.of("sync.sql.md"), CompareConfiguration.from(valid()).reportFile());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "/", "\u0000", "sync.sql", "sub/../sync.sql"})
    void rejectsInvalidOrConflictingReportPath(String value) {
        Properties properties = valid();
        properties.setProperty("report.path", value);
        assertThrows(IOException.class, () -> CompareConfiguration.from(properties));
    }

    private static Properties valid() {
        Properties properties = new Properties();
        properties.setProperty("reference.schema", "SOURCE");
        properties.setProperty("target.schema", "TARGET");
        properties.setProperty("output.path", "sync.sql");
        return properties;
    }
}
